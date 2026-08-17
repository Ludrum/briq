#!/usr/bin/env python3
"""
Briq profile engine.

Applies a named profile by rewriting ONLY the region of AdGuard Home's user
rules that sits between the BRIQ MANAGED markers. Everything outside those
markers is preserved byte for byte, so unrelated user rules, filter lists,
clients, rewrites and settings are never touched.

Profiles are data (profiles/*.list). Adding or changing blocked domains must
never require editing this file.

Rules are scoped with AdGuard's $client= modifier so they apply only to the
clients listed in clients.conf. Every other device on the LAN is unaffected
by any profile, and resolves through the Pi exactly as it did before.

Anything else AdGuard is configured to do - blocklists in particular - is
independent of this file and of every profile. Briq does not enable, disable
or read those.
"""

import base64
import fcntl
import json
import os
import re
import shutil
import subprocess
import sys
import time
import ipaddress
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

BASE = "/opt/briq-control"
PROFILE_DIR = os.path.join(BASE, "profiles")
CLIENTS_FILE = os.path.join(BASE, "clients.conf")
STATE_DIR = "/var/lib/briq-control"
LOCK_FILE = os.path.join(STATE_DIR, "briq.lock")
CURRENT_FILE = os.path.join(STATE_DIR, "current")
SINCE_FILE = os.path.join(STATE_DIR, "since")
LKG_FILE = os.path.join(STATE_DIR, "last-known-good.json")
LOG_FILE = "/var/log/briq-control/briq.log"

BEGIN = "! BRIQ MANAGED BEGIN"
END = "! BRIQ MANAGED END"

AGH_URL = os.environ.get("AGH_URL", "http://127.0.0.1:3000")
AGH_USER = os.environ.get("AGH_USER", "")
AGH_PASS = os.environ.get("AGH_PASS", "")

# Conservative: a client token is either a bare label, an IPv4/IPv6 literal,
# or a CIDR. Anything else is rejected rather than escaped, because a
# mis-escaped $client= value silently changes who a rule applies to.
CLIENT_RE = re.compile(r"^[A-Za-z0-9._:/-]+$")
DOMAIN_RE = re.compile(r"^(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\.(?!-)[A-Za-z0-9-]{1,63}(?<!-))*$")


class BriqError(Exception):
    pass


def log(event, **fields):
    """Append one structured audit line. Never logs tokens or secret URLs."""
    rec = {"ts": datetime.now(timezone.utc).isoformat(timespec="seconds"), "event": event}
    rec.update(fields)
    line = json.dumps(rec, sort_keys=True)
    try:
        os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
        with open(LOG_FILE, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except OSError:
        print(line, file=sys.stderr)


# --------------------------------------------------------------------------
# profile + client data
# --------------------------------------------------------------------------

def load_clients():
    """Read clients.conf -> list of $client= tokens (AdGuard client names)."""
    if not os.path.exists(CLIENTS_FILE):
        raise BriqError("clients.conf missing at %s" % CLIENTS_FILE)
    out = []
    with open(CLIENTS_FILE, encoding="utf-8") as fh:
        for num, raw in enumerate(fh, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if not CLIENT_RE.match(line):
                raise BriqError(
                    "clients.conf line %d: %r contains characters that are not "
                    "safe in a $client= modifier" % (num, line)
                )
            if line not in out:
                out.append(line)
    if not out:
        raise BriqError(
            "clients.conf lists no clients. Refusing to build profile rules: "
            "an empty $client= would change scope unpredictably."
        )
    return out


def list_profiles():
    if not os.path.isdir(PROFILE_DIR):
        return []
    return sorted(
        f[:-5] for f in os.listdir(PROFILE_DIR)
        if f.endswith(".list") and not is_fragment(f[:-5])
    )


def is_stricter(new, current):
    """
    True only if switching new<-current can never loosen the brick.

    This is what makes the token-free page on /status safe to expose: it can
    tighten, never relax. The answer is derived from the actual domain sets,
    not a hand-maintained ranking, so editing a profile file cannot silently
    make a relaxation look like an escalation.

      unbricked -> social       True   (adds domains)
      social    -> deep-focus   True   (superset)
      social    -> video        False  (video does not block Instagram)
      deep-focus-> social       False  (drops domains)
      anything  -> offline      True   (blocks everything)
      offline   -> anything     False
    """
    if new == current:
        return False
    nb, na, nall = load_profile(new)
    cb, ca, call = load_profile(current)
    if call and not nall:
        return False                      # leaving a catch-all always loosens
    if nall and not call:
        return True                       # entering a catch-all always tightens
    if nall and call:
        return set(na) < set(ca)          # both deny-all: fewer exceptions wins
    return set(nb) > set(cb)              # otherwise require a strict superset


def agh_clients():
    """Persistent clients known to AdGuard Home."""
    data = agh("/control/clients")
    out = {}
    for c in (data.get("clients") or []):
        out[c.get("name", "")] = list(c.get("ids") or [])
    return out


def add_client(name, ids):
    """
    Register a persistent AdGuard client and add it to clients.conf.

    Identifiers may be IP addresses or CIDRs. Do NOT use a MAC: AdGuard
    Home can only match one while it is itself the network DHCP server,
    which it is not, so a MAC silently matches nothing (see docs/SETUP.md).

    An IP is only safe once it is reserved in the router, otherwise the
    router may later hand that address to an unrelated device and brick
    someone else's machine.

    This REPLACES the client's identifiers. To keep the existing ones and
    add another - a Tailscale address, say - use add_client_id().
    """
    if not CLIENT_RE.match(name):
        raise BriqError("client name %r must be letters, digits, . _ - : /" % name)
    if not ids:
        raise BriqError("need at least one IP, CIDR or MAC for %r" % name)
    for i in ids:
        if not re.match(r"^[0-9A-Fa-f.:/]+$", i):
            raise BriqError("identifier %r does not look like an IP, CIDR or MAC" % i)

    existing = agh_clients()
    if name in existing:
        agh("/control/clients/update",
            {"name": name, "data": {"name": name, "ids": ids,
                                    "use_global_settings": True,
                                    "filtering_enabled": True,
                                    "safebrowsing_enabled": False,
                                    "parental_enabled": False,
                                    "use_global_blocked_services": True,
                                    "blocked_services": []}})
        action = "updated"
    else:
        agh("/control/clients/add",
            {"name": name, "ids": ids, "use_global_settings": True,
             "filtering_enabled": True, "safebrowsing_enabled": False,
             "parental_enabled": False, "use_global_blocked_services": True,
             "blocked_services": []})
        action = "added"

    lines = []
    if os.path.exists(CLIENTS_FILE):
        with open(CLIENTS_FILE, encoding="utf-8") as fh:
            lines = fh.read().splitlines()
    if not any(l.strip() == name for l in lines):
        lines.append(name)
        tmp = CLIENTS_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines).rstrip("\n") + "\n")
        os.replace(tmp, CLIENTS_FILE)

    log("client.%s" % action, client=name, ids=len(ids))
    return {"client": name, "action": action, "ids": ids}


def add_client_id(name, ids):
    """
    Add identifiers to an existing client, keeping the ones already there.

    A device needs one identifier per path it can reach the Pi by. The
    laptop has a LAN address and a 100.x.y.z Tailscale address, and the
    same profile has to apply either way - otherwise leaving the house
    silently lifts the brick.

    Tailscale addresses are worth trusting more than DHCP ones: they are
    assigned per device by the coordination server and do not change, so
    unlike a router reservation there is nothing to fall out of.
    """
    if not CLIENT_RE.match(name):
        raise BriqError("client name %r must be letters, digits, . _ - : /" % name)
    if not ids:
        raise BriqError("need at least one IP or CIDR to add to %r" % name)
    for i in ids:
        if not re.match(r"^[0-9A-Fa-f.:/]+$", i):
            raise BriqError("identifier %r does not look like an IP or CIDR" % i)

    existing = agh_clients()
    if name not in existing:
        raise BriqError(
            "no AdGuard client named %r. Create it first:\n"
            "  sudo briqctl add-client %s <ip>" % (name, name))

    merged = list(existing[name])
    added = []
    for i in ids:
        if i not in merged:
            merged.append(i)
            added.append(i)

    if not added:
        return {"client": name, "action": "unchanged", "ids": merged}

    agh("/control/clients/update",
        {"name": name, "data": {"name": name, "ids": merged,
                                "use_global_settings": True,
                                "filtering_enabled": True,
                                "safebrowsing_enabled": False,
                                "parental_enabled": False,
                                "use_global_blocked_services": True,
                                "blocked_services": []}})

    log("client.id_added", client=name, added=len(added), total=len(merged))
    return {"client": name, "action": "ids_added", "added": added, "ids": merged}


def is_fragment(name):
    """
    True if this file is a building block for @include, not a real profile.

    Fragments are hidden from the profile list and cannot be selected, so a
    shared domain set does not show up as something to scan a QR code for.
    """
    path = os.path.join(PROFILE_DIR, name + ".list")
    try:
        with open(path, encoding="utf-8") as fh:
            return any(line.strip() == "@fragment" for line in fh)
    except OSError:
        return False


def load_profile(name, _stack=()):
    """
    Parse a profile file into (block_domains, allow_domains, block_all).

    Supports:
      <domain>          block this domain and its subdomains
      @allow <domain>   exception, wins over blocks
      @blockall         deny everything not explicitly allowed
      @include <name>   splice in another profile
      @fragment         this file is include-only, not selectable
    """
    # Cycle detection tracks the current include PATH, not every file already
    # visited. Otherwise a diamond (deep-focus -> social -> doh-bootstrap and
    # deep-focus -> video -> doh-bootstrap) would be misreported as a cycle.
    if name in _stack:
        raise BriqError("profile include cycle: %s -> %s"
                         % (" -> ".join(_stack), name))
    _stack = _stack + (name,)

    if not re.match(r"^[a-z0-9-]+$", name):
        raise BriqError("invalid profile name %r" % name)
    path = os.path.join(PROFILE_DIR, name + ".list")
    if not os.path.exists(path):
        raise BriqError("unknown profile %r" % name)

    blocks, allows, block_all = [], [], False
    with open(path, encoding="utf-8") as fh:
        for num, raw in enumerate(fh, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue

            if line == "@blockall":
                block_all = True
                continue

            if line == "@fragment":
                continue

            if line.startswith("@include "):
                sub = line.split(None, 1)[1].strip()
                sb, sa, sall = load_profile(sub, _stack)
                # Dedupe on merge: a diamond include (deep-focus pulls in both
                # social and video, and both include doh-bootstrap) would
                # otherwise emit every shared domain twice.
                for dom in sb:
                    if dom not in blocks:
                        blocks.append(dom)
                for dom in sa:
                    if dom not in allows:
                        allows.append(dom)
                block_all = block_all or sall
                continue

            if line.startswith("@allow "):
                dom = line.split(None, 1)[1].strip().lower()
                target = allows
            else:
                dom = line.lower()
                target = blocks

            if not DOMAIN_RE.match(dom):
                raise BriqError(
                    "%s.list line %d: %r is not a valid domain" % (name, num, dom)
                )
            if dom not in target:
                target.append(dom)

    return blocks, allows, block_all


def render_rules(name, clients):
    """Build the exact rule lines for a profile. Pure function, no I/O to AGH."""
    blocks, allows, block_all = load_profile(name)

    # A profile that produces nothing (unbricked) needs no client scope, so
    # it stays usable as the escape hatch even before any client is defined.
    if not blocks and not allows and not block_all:
        return []
    if not clients:
        raise BriqError(
            "profile %r would create rules but no bricked clients are defined. "
            "Refusing: an unscoped rule would hit every device on the LAN. "
            "Run: briqctl add-client <name> <ip-or-mac>" % name)

    scope = "$client=" + "|".join(clients)
    out = []

    out.append("! profile: %s" % name)
    out.append("! generated by briq-control - edits here are overwritten")
    out.append("! scope: %d bricked client(s); all other LAN clients unaffected"
               % len(clients))

    # Exceptions first so they are easy to read; AdGuard gives @@ priority
    # regardless of ordering.
    for dom in allows:
        out.append("@@||%s^%s" % (dom, scope))
    if block_all:
        out.append("||*^%s" % scope)
    for dom in blocks:
        out.append("||%s^%s" % (dom, scope))

    for rule in out:
        if "\n" in rule or "\r" in rule or not rule.strip():
            raise BriqError("generated malformed rule %r" % rule)
    return out


# --------------------------------------------------------------------------
# AdGuard Home API
# --------------------------------------------------------------------------

def agh(path, payload=None, timeout=25):
    url = AGH_URL.rstrip("/") + path
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method="POST" if data else "GET")
    if data:
        req.add_header("Content-Type", "application/json")
    if AGH_USER:
        tok = base64.b64encode(("%s:%s" % (AGH_USER, AGH_PASS)).encode()).decode()
        req.add_header("Authorization", "Basic " + tok)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        raise BriqError("AdGuard API %s -> HTTP %s: %s"
                         % (path, exc.code, exc.read()[:200].decode("utf-8", "replace")))
    except urllib.error.URLError as exc:
        raise BriqError("AdGuard API %s unreachable: %s" % (path, exc.reason))
    if not body.strip():
        return {}
    try:
        return json.loads(body)
    except ValueError:
        return {"_raw": body}


def get_user_rules():
    st = agh("/control/filtering/status")
    if "user_rules" not in st:
        raise BriqError("AdGuard filtering status has no user_rules field")
    return list(st["user_rules"])


def set_user_rules(rules):
    agh("/control/filtering/set_rules", {"rules": rules})


def cache_clear():
    """
    Drop the DNS cache.

    Without this a profile change appears not to work: AdGuard keeps serving
    the previous answer until the TTL expires, and cache_optimistic (enabled
    here for speed on slow hardware) deliberately serves stale entries.
    """
    agh("/control/cache_clear", {})


def check_host(name, client_ip):
    """Ask the live filtering engine what it would do, for a specific client."""
    return agh("/control/filtering/check_host?name=%s&client=%s"
               % (urllib.parse.quote(name), urllib.parse.quote(client_ip)))


MAC_RE = re.compile(r"^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")


def probe_ip(clients):
    """
    Find a plain IP for one bricked client, for use with check_host.

    check_host identifies clients by address, not by persistent-client name,
    so a client defined only by MAC cannot be probed this way.
    """
    known = agh_clients()
    for name in clients:
        for ident in known.get(name, []):
            if MAC_RE.match(ident) or "/" in ident:
                continue
            try:
                ipaddress.ip_address(ident)
                return ident
            except ValueError:
                continue
    return None


def wait_effective(profile, clients, timeout=120):
    """
    Block until the new rules are actually live in the filtering engine.

    A filter reload on this hardware takes tens of seconds. Returning before
    it completes would report success while the old profile is still active.
    """
    blocks, allows, block_all = load_profile(profile)
    if block_all:
        canary, want_managed = "example.com", True
    elif blocks:
        canary, want_managed = blocks[0], True
    else:
        # unbricked: assert no $client= rule matches a formerly blocked name
        canary, want_managed = "instagram.com", False

    ip = probe_ip(clients) if clients else None
    if not ip:
        # No probeable address (e.g. MAC-only clients). Fall back to a fixed
        # settle so we still do not return while a reload is in flight.
        time.sleep(20)
        return None, "unverified (no probeable client IP)"

    deadline = time.time() + timeout
    last = "no response"
    while time.time() < deadline:
        try:
            res = check_host(canary, ip)
            last = res.get("reason", "?")
            if ("$client=" in (res.get("rule") or "")) == want_managed:
                return True, last
        except BriqError as exc:
            last = str(exc)[:80]
        time.sleep(3)
    return False, last


def split_managed(rules):
    """
    Return (before, after) - everything outside the managed markers.

    Preserves ordering and content exactly. Raises if the markers are
    malformed, so a corrupted file is never silently rewritten.
    """
    b_idx = [i for i, r in enumerate(rules) if r.strip() == BEGIN]
    e_idx = [i for i, r in enumerate(rules) if r.strip() == END]
    if len(b_idx) != len(e_idx) or len(b_idx) > 1:
        raise BriqError(
            "found %d BEGIN and %d END markers in AdGuard user rules; "
            "refusing to touch them. Fix them by hand first."
            % (len(b_idx), len(e_idx))
        )
    if not b_idx:
        return list(rules), []
    b, e = b_idx[0], e_idx[0]
    if e < b:
        raise BriqError("BRIQ MANAGED END appears before BEGIN; refusing to edit")
    return rules[:b], rules[e + 1:]


def current_profile():
    try:
        with open(CURRENT_FILE, encoding="utf-8") as fh:
            val = fh.read().strip()
        return val or "unknown"
    except OSError:
        return "unknown"


# --------------------------------------------------------------------------
# apply
# --------------------------------------------------------------------------

def apply_profile(name, source="local", force=False):
    """Apply a profile atomically under an exclusive lock, with rollback."""
    os.makedirs(STATE_DIR, exist_ok=True)
    with open(LOCK_FILE, "a+") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise BriqError("another profile change is in progress")

        prev = current_profile()

        if is_fragment(name):
            raise BriqError(
                "%r is an @include fragment, not a selectable profile" % name)

        # Validate fully BEFORE reading or mutating anything remote.
        try:
            clients = load_clients()
        except BriqError:
            clients = []          # render_rules decides whether that is fatal
        managed = render_rules(name, clients)

        existing = get_user_rules()
        before, after = split_managed(existing)

        # Keep a last-known-good snapshot of the complete previous rule set.
        with open(LKG_FILE, "w", encoding="utf-8") as fh:
            json.dump({"saved": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                       "profile": prev, "rules": existing}, fh, indent=1)

        new = list(before) + [BEGIN] + managed + [END] + list(after)

        if not force and new == existing:
            # Idempotent re-selection. Still drop the cache, because the usual
            # reason somebody scans the same tag twice is that a stale cached
            # answer made it look like nothing happened.
            try:
                cache_clear()
            except BriqError:
                pass
            log("apply.noop", profile=name, previous=prev, source=source)
            write_current(name)
            return {"profile": name, "previous": prev, "changed": False,
                    "rules": len(managed), "live": True}

        try:
            set_user_rules(new)
            verify = get_user_rules()
            vb, va = split_managed(verify)
            if vb != before or va != after:
                raise BriqError("unrelated rules changed during apply")
            if verify != new:
                raise BriqError("AdGuard did not store the rules as written")
            cache_clear()
            live, reason = wait_effective(name, clients)
            if live is False:
                raise BriqError("rules stored but not live after timeout (%s)" % reason)
            if not dns_alive():
                raise BriqError("DNS stopped answering after apply")
        except Exception as exc:
            log("apply.failed", profile=name, previous=prev, source=source,
                error=str(exc)[:300])
            try:
                set_user_rules(existing)
                log("apply.rolledback", profile=prev)
            except Exception as rexc:
                log("apply.rollback_failed", error=str(rexc)[:300])
                raise BriqError(
                    "apply failed AND rollback failed (%s). Restore by hand from %s"
                    % (rexc, LKG_FILE))
            raise BriqError("apply failed, rolled back to %s: %s" % (prev, exc))

        write_current(name)
        log("apply.ok", profile=name, previous=prev, source=source,
            rules=len(managed), clients=len(clients), live=live)
        return {"profile": name, "previous": prev, "changed": True,
                "rules": len(managed), "live": live, "verified": reason}


def write_current(name):
    # Only a real change restarts the clock. Re-selecting the profile you are
    # already on - a second tag scan, a schedule firing onto its own result -
    # must not reset "how long have I been in this", which is the one thing
    # the timer is for.
    changed = current_profile() != name

    tmp = CURRENT_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write(name + "\n")
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, CURRENT_FILE)

    if changed:
        write_since(time.time())


def write_since(when):
    tmp = SINCE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write("%d\n" % int(when))
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, SINCE_FILE)


def profile_since():
    """
    Unix time the active profile was applied, or 0 if it cannot be known.

    Kept on the Pi rather than on each client on purpose: bricking from the
    iPad has to show the same elapsed time on the phone, and only the
    controller sees every path in.
    """
    try:
        with open(SINCE_FILE, encoding="utf-8") as fh:
            return int(float(fh.read().strip()))
    except (OSError, ValueError):
        # Written before this file existed. The state file's own mtime is the
        # closest thing to the truth, and it is right in every case except a
        # no-op re-selection since the last real change.
        try:
            return int(os.path.getmtime(CURRENT_FILE))
        except OSError:
            return 0


def dns_alive(probe="dns.quad9.net"):
    """Cheap liveness check: does the resolver still answer at all?"""
    try:
        res = subprocess.run(
            ["dig", "+short", "+time=3", "+tries=1", probe, "@127.0.0.1"],
            capture_output=True, timeout=12)
        return res.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def status():
    cur = current_profile()
    since = profile_since()
    info = {"profile": cur, "profiles": list_profiles(), "clients": [],
            "dns_ok": dns_alive(), "managed_rules": 0,
            "since": since,
            # Seconds, computed here: a client that trusts its own clock
            # against the Pi's would drift, and phones are not NTP-tight.
            "since_s": max(0, int(time.time()) - since) if since else -1}
    try:
        info["clients"] = load_clients()
    except BriqError as exc:
        info["clients_error"] = str(exc)
    try:
        rules = get_user_rules()
        before, after = split_managed(rules)
        info["managed_rules"] = max(0, len(rules) - len(before) - len(after) - 2)
        info["other_user_rules"] = len(before) + len(after)
        info["backend_ok"] = True
    except BriqError as exc:
        info["backend_ok"] = False
        info["backend_error"] = str(exc)
    return info


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip())
        print("\nusage: briqctl <command>\n"
              "  status                       show current profile and health\n"
              "  list                         list available profiles\n"
              "  apply <profile>              switch to a profile\n"
              "  render <profile>             preview the rules, change nothing\n"
              "  clients                      show AdGuard clients and brick scope\n"
              "  add-client <name> <id>...    register a device as bricked\n"
              "  add-client-id <name> <id>... add an identifier, keep existing\n"
              "  restore                      re-apply the last-known-good rules")
        return 2
    cmd = sys.argv[1]
    try:
        if cmd == "clients":
            known = agh_clients()
            try:
                bricked = load_clients()
            except BriqError:
                bricked = []
            print(json.dumps({"adguard_clients": known, "bricked": bricked},
                             indent=2))
        elif cmd == "add-client":
            print(json.dumps(add_client(sys.argv[2], sys.argv[3:]), indent=2))
        elif cmd == "add-client-id":
            print(json.dumps(add_client_id(sys.argv[2], sys.argv[3:]), indent=2))
        elif cmd == "status":
            print(json.dumps(status(), indent=2))
        elif cmd == "list":
            for p in list_profiles():
                mark = "*" if p == current_profile() else " "
                print("%s %s" % (mark, p))
        elif cmd == "render":
            for r in render_rules(sys.argv[2], load_clients()):
                print(r)
        elif cmd == "apply":
            print(json.dumps(apply_profile(sys.argv[2], source="cli"), indent=2))
        elif cmd == "restore":
            with open(LKG_FILE, encoding="utf-8") as fh:
                snap = json.load(fh)
            set_user_rules(snap["rules"])
            write_current(snap.get("profile", "unknown"))
            log("restore.ok", profile=snap.get("profile"))
            print("restored snapshot from %s (profile %s)"
                  % (snap.get("saved"), snap.get("profile")))
        else:
            print("unknown command %r" % cmd, file=sys.stderr)
            return 2
    except BriqError as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 1
    except IndexError:
        print("error: missing profile argument", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
