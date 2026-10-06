#!/usr/bin/env python3
"""
Briq controller - LAN-only HTTP front end for profile selection.

Contract:
  GET  /                        redirect to /status
  GET  /status                  status page. Offers ESCALATION ONLY: it can
                                make the brick stricter without a token, never
                                looser. Loosening always needs the token, which
                                lives on the printed QR sheet / NFC tag.
  GET  /status.json             machine-readable status
  GET  /select/<profile>/<tok>  confirmation page, absolute selection
  POST /select/<profile>/<tok>  apply that exact profile (idempotent)
  GET  /toggle/<profile>/<tok>  confirmation page, toggle semantics
  POST /toggle/<profile>/<tok>  if <profile> is active -> unbricked, else
                                -> <profile>. Debounced against double scans.
  POST /escalate/<profile>      token-free, but only to a strictly stricter
                                profile, and only with a same-origin nonce
  GET  /unlock/<tok>            the single NFC tag. Carries the token and
                                nothing else, so one tag authorises ANY
                                profile including looser ones - possession
                                of the tag at that moment is the whole
                                permission. Lists what can be selected;
                                selection itself is still a POST to
                                /select/<profile>/<tok>.
  GET  /nonce                   JSON, mints one escalation nonce. Same pool
                                and same single-use rule as the status page,
                                so a native client can take the token-free
                                escalation path without scraping HTML.
  GET  /profiles.json           JSON, the presentation table below: level,
                                hue, chroma and description per profile. One
                                source of truth for every client's palette.
  GET  /schedules               scheduled bricks: the list and the form that
                                adds one. Token-free, because a schedule can
                                only ever arm a restriction.
  GET  /schedules.json          the same list as data
  POST /schedules               add one. Needs a nonce, not a token.
  POST /schedules/<id>/delete   remove one. Free while the schedule has never
                                run; once it has, this is a loosening and
                                answers 403 needs_tag.
  POST /schedules/<id>/delete/<tok>
                                remove one with the tag's authority, armed or
                                not.
  POST /settings                display-only preferences, nonce and no token.
                                Currently one: whether the elapsed timer runs
                                while unbricked. Served inside /status.json.
  GET  /healthz                 liveness probe

/status.json and /profile.json both carry `since` (unix time the active
profile was applied) and `since_s` (seconds, from this clock). The elapsed
time lives here rather than on each client because bricking from the iPad has
to read the same on the phone.

Any endpoint that answers with a page will instead answer with JSON when the
request carries `Accept: application/json` - including the error paths, so a
client can tell "would loosen the brick" from "throttled" from "already
applying" without parsing prose. Browsers are unaffected: they never send it.

GET never changes state, so a link preview or browser prefetch cannot flip
the profile. Requests are accepted only from localhost and the configured
trusted LAN prefixes, enforced here as well as in nftables.
"""

import hmac
import html
import ipaddress
import json
import os
import secrets
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote

sys.path.insert(0, "/opt/briq-control")
import briqctl  # noqa: E402
import scheduler  # noqa: E402

TOKEN = os.environ.get("BRIQ_TOKEN", "")
PORT = int(os.environ.get("BRIQ_PORT", "8088"))
TRUSTED = [n.strip() for n in os.environ.get(
    "BRIQ_TRUSTED_NETS", "127.0.0.1/32,::1/128").split(",") if n.strip()]

_NETS = []
for _n in TRUSTED:
    try:
        _NETS.append(ipaddress.ip_network(_n, strict=False))
    except ValueError:
        print("ignoring invalid trusted net %r" % _n, file=sys.stderr)

# A double tap on an NFC tag must not toggle twice and land back where it
# started. Within this window a repeated toggle of the same profile is a no-op.
TOGGLE_DEBOUNCE_S = 12

# --------------------------------------------------------------------------
# profile presentation
#
# Colour encodes one thing: how restricted you currently are. The hues run
# open -> closed, and `level` drives a meter so the state is never carried by
# colour alone. Lightness is authored separately for light and dark rather
# than inverted, so the accent stays legible on both.
# --------------------------------------------------------------------------
PROFILES = {
    "unbricked":  {"level": 0, "hue": 168, "c": 0.10,
                   "desc": "Everything allowed."},
    "social":     {"level": 1, "hue": 72,  "c": 0.13,
                   "desc": "Instagram, Facebook, TikTok, X and Reddit blocked."},
    "video":      {"level": 1, "hue": 305, "c": 0.13,
                   "desc": "YouTube blocked. Search, Gmail and Maps still work."},
    "deep-focus": {"level": 2, "hue": 268, "c": 0.14,
                   "desc": "Social and YouTube both blocked."},
    "offline":    {"level": 3, "hue": 20,  "c": 0.15,
                   "desc": "Everything blocked but a small emergency allowlist."},
}
MAX_LEVEL = 3
FALLBACK = {"level": 0, "hue": 250, "c": 0.02, "desc": ""}


def meta(name):
    return PROFILES.get(name, FALLBACK)


# --------------------------------------------------------------------------
# display settings
#
# Kept on the Pi, like everything else clients render from. One boolean is not
# worth a preference store per device, and two surfaces disagreeing about
# whether the timer is showing would read as a bug rather than as a setting.
# --------------------------------------------------------------------------
SETTINGS_FILE = os.path.join(briqctl.STATE_DIR, "settings.json")
SETTING_DEFAULTS = {"timer_when_unbricked": True}
_slock = threading.Lock()


def settings():
    out = dict(SETTING_DEFAULTS)
    try:
        with open(SETTINGS_FILE, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return out
    for key in out:
        if isinstance(data.get(key), bool):
            out[key] = data[key]
    return out


def set_setting(key, value):
    if key not in SETTING_DEFAULTS:
        raise KeyError(key)
    with _slock:
        cur = settings()
        cur[key] = bool(value)
        tmp = SETTINGS_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(cur, fh, indent=1, sort_keys=True)
        os.replace(tmp, SETTINGS_FILE)
    return cur


def show_timer(profile):
    """Unbricked is a state you can be in for weeks; counting it is optional."""
    return profile != "unbricked" or settings()["timer_when_unbricked"]


def elapsed_label(secs):
    """1:42:53. Minutes and seconds below the hour, days above the day."""
    if secs is None or secs < 0:
        return ""
    days, rem = divmod(int(secs), 86400)
    hours, rem = divmod(rem, 3600)
    mins, sec = divmod(rem, 60)
    if days:
        return "%dd %d:%02d:%02d" % (days, hours, mins, sec)
    if hours:
        return "%d:%02d:%02d" % (hours, mins, sec)
    return "%d:%02d" % (mins, sec)


def since_label(epoch):
    """
    When it started, in words.

    The page cannot tick - there is no script on it and the CSP would refuse
    one - so the running total goes stale the moment it is drawn. An absolute
    start time does not, which is why both are shown.
    """
    if not epoch:
        return ""
    t = time.localtime(epoch)
    now = time.localtime()
    if (t.tm_year, t.tm_yday) == (now.tm_year, now.tm_yday):
        when = "today"
    elif epoch > time.time() - 6 * 86400:
        when = time.strftime("%a", t)
    else:
        when = time.strftime("%d %b", t)
    return "%s %02d:%02d" % (when, t.tm_hour, t.tm_min)


def palette_css():
    """One class per profile, composed for light and dark separately."""
    light, dark = [], []
    for name, m in PROFILES.items():
        h, c = m["hue"], m["c"]
        light.append(
            ".p-%s{--accent:oklch(0.48 %.2f %d);--ink:oklch(0.32 %.2f %d);"
            "--wash:oklch(0.972 %.3f %d);--edge:oklch(0.88 %.2f %d)}"
            % (name, c, h, min(c, 0.08), h, min(c * 0.22, 0.03), h, min(c * 0.5, 0.07), h))
        dark.append(
            ".p-%s{--accent:oklch(0.80 %.2f %d);--ink:oklch(0.90 %.2f %d);"
            "--wash:oklch(0.255 %.3f %d);--edge:oklch(0.42 %.2f %d)}"
            % (name, c, h, min(c, 0.06), h, min(c * 0.30, 0.04), h, min(c * 0.5, 0.07), h))
    return "".join(light) + "@media(prefers-color-scheme:dark){" + "".join(dark) + "}"


CSS = """
:root{color-scheme:light dark;
 --bg:oklch(0.985 0 0);--card:oklch(1 0 0);--line:oklch(0.90 0 0);
 --fg:oklch(0.22 0 0);--dim:oklch(0.48 0 0);
 --accent:oklch(0.48 0 0);--ink:oklch(0.32 0 0);
 --wash:oklch(0.97 0 0);--edge:oklch(0.88 0 0)}
@media(prefers-color-scheme:dark){:root{
 --bg:oklch(0.17 0 0);--card:oklch(0.215 0 0);--line:oklch(0.32 0 0);
 --fg:oklch(0.95 0 0);--dim:oklch(0.72 0 0)}}
*{box-sizing:border-box}
body{font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
 margin:0;padding:1.25rem 1.25rem 2.5rem;max-width:30rem;margin-inline:auto;
 background:var(--bg);color:var(--fg);-webkit-text-size-adjust:100%}
h1{font-size:1.2rem;letter-spacing:-0.01em;margin:.2rem 0 1rem;font-weight:650}
h2{font-size:.8rem;letter-spacing:.02em;text-transform:none;color:var(--dim);
 font-weight:600;margin:1.6rem 0 .5rem}
.state{background:var(--wash);border:1px solid var(--edge);border-radius:16px;
 padding:1.1rem 1.15rem;animation:rise .5s cubic-bezier(.16,1,.3,1) both}
@keyframes rise{from{opacity:0;transform:translateY(6px)}}
@media(prefers-reduced-motion:reduce){.state{animation:none}}
.label{font-size:.74rem;letter-spacing:.06em;text-transform:uppercase;
 color:var(--dim);font-weight:650}
.now{font-size:1.9rem;line-height:1.15;font-weight:700;letter-spacing:-0.02em;
 color:var(--accent);margin:.3rem 0 .1rem;word-break:break-word}
.state p{margin:.35rem 0 0;color:var(--ink);font-size:.93rem}
.since{margin:.75rem 0 0;font-size:1.15rem;font-weight:650;color:var(--ink);
 font-variant-numeric:tabular-nums;letter-spacing:-0.01em}
.since span{margin-left:.45rem;font-size:.78rem;font-weight:500;
 color:var(--dim);letter-spacing:0}
.meter{display:flex;gap:.3rem;margin:.85rem 0 0;align-items:center}
.seg{height:.38rem;flex:1;border-radius:99px;background:var(--edge)}
.seg.on{background:var(--accent)}
.meter span{font-size:.72rem;color:var(--dim);margin-left:.15rem;
 white-space:nowrap;font-variant-numeric:tabular-nums}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;
 padding:.9rem 1rem;margin:.7rem 0}
table{width:100%;border-collapse:collapse;font-size:.88rem}
td{padding:.34rem 0;border-bottom:1px solid var(--line);color:var(--dim)}
tr:last-child td{border-bottom:0}
td:last-child{text-align:right;color:var(--fg);font-weight:550}
form{margin:0}
button{width:100%;padding:.95rem 1rem;font:inherit;font-weight:650;
 border:1px solid transparent;border-radius:12px;cursor:pointer;
 background:var(--accent);color:var(--bg);margin:.45rem 0;
 transition:filter .15s ease}
button:hover{filter:brightness(1.08)}
button:active{filter:brightness(.94)}
button:focus-visible,a:focus-visible{outline:2px solid var(--accent);
 outline-offset:2px}
.esc{background:var(--card);color:var(--accent);border-color:var(--edge);
 display:flex;justify-content:space-between;align-items:baseline;gap:.6rem;
 text-align:left}
.esc small{font-weight:500;color:var(--dim);font-size:.78rem}
a.btn{display:block;text-align:center;padding:.8rem;border:1px solid var(--line);
 border-radius:12px;text-decoration:none;color:var(--dim);margin:.45rem 0}
.note{font-size:.84rem;color:var(--dim);margin:.6rem 0 0}
.sched{display:flex;align-items:baseline;gap:.6rem;padding:.6rem 0;
 border-bottom:1px solid var(--line)}
.sched:last-child{border-bottom:0;padding-bottom:.1rem}
.sched .at{font-weight:700;font-variant-numeric:tabular-nums;
 letter-spacing:-0.01em;color:var(--accent)}
.sched .grow{flex:1;min-width:0}
.sched .grow small{display:block;color:var(--dim);font-size:.76rem}
.rm{width:auto;margin:0;padding:.35rem .7rem;font-size:.78rem;font-weight:550;
 background:var(--card);color:var(--dim);border-color:var(--line)}
.locked{font-size:.74rem;color:var(--dim);white-space:nowrap}
fieldset{border:1px solid var(--line);border-radius:14px;margin:.8rem 0;
 padding:.5rem .8rem .8rem}
legend{font-size:.72rem;letter-spacing:.06em;text-transform:uppercase;
 color:var(--dim);font-weight:650;padding:0 .3rem}
.days{display:flex;gap:.3rem}
.days label{flex:1}
/* Hidden to the eye, not to the keyboard or a screen reader: a 0x0 control
   drops out of the accessibility tree, so clip a 1px one instead. */
.days input,.profs input{position:absolute;width:1px;height:1px;opacity:0;
 overflow:hidden;clip-path:inset(50%)}
.days span{display:block;text-align:center;padding:.68rem .1rem;
 font-size:.8rem;border:1px solid var(--line);border-radius:10px;cursor:pointer}
.days input:checked+span{background:var(--accent);color:var(--bg);
 border-color:var(--accent);font-weight:650}
.days input:focus-visible+span,.profs input:focus-visible+span{
 outline:2px solid var(--accent);outline-offset:2px}
.profs label{display:block}
.profs span{display:flex;justify-content:space-between;align-items:baseline;
 gap:.6rem;padding:.65rem .8rem;border:1px solid var(--line);
 border-radius:12px;margin:.35rem 0;cursor:pointer}
.profs input:checked+span{border-color:var(--accent);background:var(--wash);
 color:var(--ink);font-weight:650}
.profs small{color:var(--dim);font-weight:500;font-size:.78rem}
input[type=time]{font:inherit;font-variant-numeric:tabular-nums;width:100%;
 padding:.6rem .7rem;border:1px solid var(--line);border-radius:10px;
 background:var(--card);color:var(--fg)}
.ok{color:oklch(0.52 0.13 155)}.bad{color:oklch(0.55 0.18 25)}
@media(prefers-color-scheme:dark){.ok{color:oklch(0.80 0.13 155)}
 .bad{color:oklch(0.75 0.16 25)}}
footer{color:var(--dim);font-size:.76rem;margin-top:2rem;text-align:center}
"""


def page(title, body, profile=None):
    cls = " class='p-%s'" % html.escape(profile) if profile in PROFILES else ""
    return ("<!doctype html><html lang=en%s><meta charset=utf-8>"
            "<meta name=viewport content='width=device-width,initial-scale=1'>"
            "<title>%s</title><style>%s%s</style>%s"
            "<footer>Briq &middot; LAN only</footer></html>"
            % (cls, html.escape(title), CSS, palette_css(), body)).encode("utf-8")


def sched_items():
    """Every schedule, as the wire format, in the order a clock reads."""
    items = [scheduler.describe(s) for s in scheduler.load()]
    items.sort(key=lambda s: (s["hour"], s["minute"], s["profile"]))
    return items


def sched_json(s):
    """One schedule plus the palette its row is drawn from."""
    m = meta(s["profile"])
    return dict(s, level=m["level"], hue=m["hue"], chroma=m["c"],
                desc=m["desc"])


def sched_rows_html(nonce=None, token=None):
    """
    The list of scheduled bricks.

    Removal is offered only where it is legitimate: with a nonce while the
    schedule has never run, and with `token` - set only on the /unlock page -
    once it has. A row with no offer says why, rather than presenting a
    button that would be refused.
    """
    items = sched_items()
    if not items:
        return ("<p class=note style='margin:0'>Nothing scheduled. A schedule "
                "can tighten the brick at a set time; it can never lift it.</p>")
    out = []
    for s in items:
        may = nonce and (token or not s["armed"])
        if may:
            action = ("/schedules/%s/delete/%s" % (s["id"], token) if s["armed"]
                      else "/schedules/%s/delete" % s["id"])
            act = ("<form method=POST action='%s'>"
                   "<input type=hidden name=nonce value='%s'>"
                   "<button class=rm type=submit>Remove</button></form>"
                   % (html.escape(action), html.escape(nonce)))
        elif s["armed"]:
            act = "<span class=locked>tag to remove</span>"
        else:
            act = ""
        out.append(
            "<div class='sched p-%s'><span class=at>%s</span>"
            "<span class=grow>%s<small>%s &middot; next %s</small></span>%s</div>"
            % (html.escape(s["profile"]), html.escape(s["time"]),
               html.escape(s["profile"]), html.escape(s["days_label"]),
               html.escape(s["next_label"]), act))
    return "".join(out)


def meter(level):
    segs = "".join("<i class='seg%s'></i>" % (" on" if i < level else "")
                   for i in range(MAX_LEVEL))
    word = ["unrestricted", "limited", "focused", "locked down"][min(level, 3)]
    return ("<div class=meter>%s<span>%s</span></div>"
            % (segs.replace("<i ", "<div ").replace("></i>", "></div>"), word))


# --------------------------------------------------------------------------
# escalation nonces (token-free path only)
# --------------------------------------------------------------------------
_nonces = {}
_nlock = threading.Lock()


def new_nonce():
    n = secrets.token_urlsafe(18)
    now = time.time()
    with _nlock:
        for k, t in [(k, t) for k, t in _nonces.items() if now - t > 900]:
            _nonces.pop(k, None)
        _nonces[n] = now
    return n


def burn_nonce(n):
    """Single use. Proves the POST came from a page we actually served."""
    with _nlock:
        t = _nonces.pop(n, None)
    return t is not None and time.time() - t <= 900


_throttle = {}
_tlock = threading.Lock()
_last_toggle = {}          # profile -> time the last toggle FINISHED
_inflight = set()          # profiles whose toggle is currently applying


def throttled(ip, limit=10, window=60):
    now = time.time()
    with _tlock:
        hits = [t for t in _throttle.get(ip, []) if now - t < window]
        hits.append(now)
        _throttle[ip] = hits
        if len(_throttle) > 256:
            for k in [k for k, v in _throttle.items()
                      if not any(now - t < window for t in v)]:
                _throttle.pop(k, None)
        return len(hits) > limit


def trusted(ip):
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if addr.is_loopback:
        return True
    if getattr(addr, "ipv4_mapped", None):
        addr = addr.ipv4_mapped
    return any(addr in net for net in _NETS)


def toggle_target(profile, current):
    """Pressing a profile again returns to unbricked."""
    return "unbricked" if current == profile else profile


def _stricter_or_false(name, current):
    """is_stricter, but an unreadable profile is 'not offerable' not a 500."""
    try:
        return briqctl.is_stricter(name, current)
    except briqctl.BriqError:
        return False


class Handler(BaseHTTPRequestHandler):
    server_version = "brick"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def client_ip(self):
        return self.client_address[0]

    def send(self, code, body, ctype="text/html; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Content-Security-Policy",
                         "default-src 'none'; style-src 'unsafe-inline'; "
                         "form-action 'self'; base-uri 'none'")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def wants_json(self):
        """
        True when the caller asked for JSON outright. Deliberately NOT a
        wildcard match: browsers send `Accept: text/html,...,*/*`, and
        treating `*/*` as consent would replace the status page with JSON
        for every ordinary visitor.
        """
        return "application/json" in (self.headers.get("Accept") or "")

    def send_json(self, code, obj):
        self.send(code, json.dumps(obj).encode(),
                  "application/json; charset=utf-8")

    def deny(self, code, msg, profile=None, reason=None):
        """
        Refuse a request. `reason` is a stable machine-readable slug for the
        JSON path; `msg` stays the human sentence shown in a browser. The
        slug is what a client should branch on - the prose is free to change.
        """
        if self.wants_json():
            return self.send_json(code, {"error": reason or "denied",
                                         "detail": msg,
                                         "profile": briqctl.current_profile()})
        self.send(code, page("Briq", "<h1>%s</h1>" % html.escape(msg), profile))

    def gate(self):
        if not trusted(self.client_ip()):
            briqctl.log("http.denied_source", source=self.client_ip())
            self.deny(403, "Forbidden - not on the trusted LAN.",
                      reason="untrusted_source")
            return False
        return True

    def route(self, kind):
        """/<kind>/<profile>/<token> -> (profile, token) or None."""
        parts = [p for p in self.path.split("?")[0].split("/") if p]
        if len(parts) != 3 or parts[0] != kind:
            return None
        return unquote(parts[1]), unquote(parts[2])

    def check_token(self, tok):
        return bool(TOKEN) and hmac.compare_digest(tok, TOKEN)

    # ---- views -------------------------------------------------------
    def status_payload(self):
        """Health, plus the two things a client needs to draw the timer."""
        return dict(briqctl.status(), settings=settings())

    def status_body(self, note=""):
        st = briqctl.status()
        cur = st["profile"]
        m = meta(cur)

        rows = "".join(
            "<tr><td>%s</td><td>%s</td></tr>" % (html.escape(k), v)
            for k, v in [
                ("Backend", "<span class=ok>reachable</span>"
                    if st.get("backend_ok") else "<span class=bad>DOWN</span>"),
                ("DNS", "<span class=ok>answering</span>"
                    if st.get("dns_ok") else "<span class=bad>NOT ANSWERING</span>"),
                ("Briq rules", html.escape(str(st.get("managed_rules", 0)))),
                ("Your other rules", html.escape(str(st.get("other_user_rules", "?")))),
                ("Bricked devices",
                 html.escape(", ".join(st.get("clients", [])) or "none")),
            ])

        # Escalation only: offer profiles that are strictly stricter than now.
        opts = [n for n in briqctl.list_profiles() if _stricter_or_false(n, cur)]
        opts.sort(key=lambda n: (meta(n)["level"], n))

        if opts:
            nonce = new_nonce()
            btns = "".join(
                "<form method=POST action='/escalate/%s'>"
                "<input type=hidden name=nonce value='%s'>"
                "<button class='esc p-%s' type=submit>"
                "<span>%s</span><small>%s</small></button></form>"
                % (html.escape(n), html.escape(nonce), html.escape(n),
                   html.escape(n), html.escape(meter_word(n)))
                for n in opts)
            esc = ("<h2>Restrict further</h2>%s"
                   "<p class=note>Lifting a restriction needs the QR code or "
                   "NFC tag. That is deliberate.</p>" % btns)
        else:
            esc = ("<h2>Restrict further</h2><div class=card>"
                   "<p class=note style='margin:0'>Nothing stricter available. "
                   "Use the QR code or NFC tag to change profile.</p></div>")

        # Read-only here: the status page stays a glance. Adding and removing
        # live on /schedules, one tap away.
        sched = ("<h2>Scheduled bricks</h2><div class=card>%s</div>"
                 "<a class=btn href='/schedules'>%s</a>"
                 % (sched_rows_html(),
                    "Change schedules" if sched_items() else "Add a schedule"))

        since = st.get("since") or 0
        timer = ""
        if show_timer(cur) and since:
            timer = ("<div class=since>%s<span>since %s</span></div>"
                     % (html.escape(elapsed_label(st.get("since_s"))),
                        html.escape(since_label(since))))

        on = settings()["timer_when_unbricked"]
        pref = ("<h2>Timer</h2>"
                "<form method=POST action='/settings'>"
                "<input type=hidden name=nonce value='%s'>"
                "<input type=hidden name=timer_when_unbricked value='%s'>"
                "<button class=esc type=submit><span>%s</span>"
                "<small>%s</small></button></form>"
                % (html.escape(new_nonce()), "off" if on else "on",
                   "Hide it while unbricked" if on else "Show it while unbricked",
                   "counting now" if on else "hidden while unbricked"))

        return ("<h1>Briq</h1>%s"
                "<div class=state><div class=label>Current profile</div>"
                "<div class=now>%s</div><p>%s</p>%s%s</div>"
                "<div class=card><table>%s</table></div>%s%s%s"
                % (note, html.escape(cur), html.escape(m["desc"]),
                   meter(m["level"]), timer, rows, esc, sched, pref))

    def schedules_body(self, note=""):
        """
        The list and the form that adds to it.

        Token-free by design: arming a future restriction is the same kind of
        act as tightening now, so it costs the same - nothing.
        """
        nonce = new_nonce()
        names = sorted(scheduler.schedulable(briqctl.list_profiles()),
                       key=lambda n: (meta(n)["level"], n))
        default = "deep-focus" if "deep-focus" in names else (
            names[0] if names else "")
        profs = "".join(
            "<label class='p-%s'><input type=radio name=profile value='%s'%s>"
            "<span>%s<small>%s</small></span></label>"
            % (html.escape(n), html.escape(n),
               " checked" if n == default else "",
               html.escape(n), html.escape(meter_word(n)))
            for n in names)
        days = "".join(
            "<label><input type=checkbox name=days value='%d'%s>"
            "<span>%s</span></label>"
            % (i, " checked" if i < 5 else "", scheduler.DAY_NAMES[i])
            for i in range(7))
        form = ("<form method=POST action='/schedules'>"
                "<input type=hidden name=nonce value='%s'>"
                "<fieldset><legend>At</legend>"
                "<input type=time name=time value='06:00' required></fieldset>"
                "<fieldset><legend>On</legend><div class=days>%s</div></fieldset>"
                "<fieldset class=profs><legend>Switch to</legend>%s</fieldset>"
                "<button type=submit>Schedule it</button></form>"
                % (html.escape(nonce), days, profs))
        return ("<h1>Scheduled bricks</h1>%s<div class=card>%s</div>"
                "<p class=note>A schedule tightens at the time you set and "
                "holds until you unbrick by hand. It never lifts a restriction "
                "on its own, and it is skipped if you are already stricter. "
                "Once one has run, removing it needs the tag.</p>"
                "<h2>Add a schedule</h2>%s"
                "<a class=btn href='/status'>Back to status</a>"
                % (note, sched_rows_html(nonce), form))

    def do_GET(self):
        if not self.gate():
            return
        path = self.path.split("?")[0]

        if path == "/healthz":
            return self.send(200, b"ok\n", "text/plain; charset=utf-8")

        unlock = [p for p in path.split("/") if p]
        if len(unlock) == 2 and unlock[0] == "unlock":
            tok = unquote(unlock[1])
            if not self.check_token(tok):
                briqctl.log("http.bad_token", source=self.client_ip(),
                             kind="unlock")
                return self.deny(403, "Invalid tag.", reason="bad_token")
            cur = briqctl.current_profile()
            names = sorted(briqctl.list_profiles(),
                           key=lambda n: (meta(n)["level"], n))
            if self.wants_json():
                # Every profile, not just the stricter ones: the tag is the
                # credential that makes loosening legitimate.
                return self.send_json(200, {
                    "profile": cur,
                    "profiles": [
                        {"name": n, "level": meta(n)["level"],
                         "hue": meta(n)["hue"], "chroma": meta(n)["c"],
                         "desc": meta(n)["desc"], "current": n == cur}
                        for n in names],
                })
            # Browser fallback, so the tag still works without the app.
            btns = "".join(
                "<form method=POST action='/select/%s/%s'>"
                "<input type=hidden name=confirm value=yes>"
                "<button class='esc p-%s' type=submit%s>"
                "<span>%s</span><small>%s</small></button></form>"
                % (html.escape(n), html.escape(tok), html.escape(n),
                   " disabled" if n == cur else "",
                   html.escape(n),
                   "current" if n == cur else html.escape(meter_word(n)))
                for n in names)
            # The tag also authorises removing a schedule that has already
            # run - the one schedule operation the token-free page refuses.
            armed = [s for s in sched_items() if s["armed"]]
            sched = ("<h2>Scheduled bricks</h2><div class=card>%s</div>"
                     % sched_rows_html(new_nonce(), token=tok)) if armed else ""
            body = ("<h1>Tag scanned</h1>"
                    "<div class=card><p class=note style='margin:0'>The tag is "
                    "in your hand, so any profile is available - including "
                    "lifting the brick.</p></div>"
                    "<h2>Choose a profile</h2>%s%s"
                    "<a class=btn href='/status'>Cancel</a>" % (btns, sched))
            return self.send(200, page("Tag scanned", body, cur))

        if path == "/nonce":
            # Minting is the whole point, so this GET does change server
            # state - but only by adding a short-lived single-use string.
            # It confers nothing on its own: /escalate still refuses
            # anything that is not strictly stricter than the current
            # profile, so a prefetched nonce cannot loosen the brick.
            cur = briqctl.current_profile()
            return self.send_json(200, {
                "nonce": new_nonce(),
                "expires_in": 900,
                "profile": cur,
                # Which escalations this nonce could actually be spent on,
                # so a client does not have to reimplement is_stricter().
                "escalations": sorted(
                    (n for n in briqctl.list_profiles()
                     if _stricter_or_false(n, cur)),
                    key=lambda n: (meta(n)["level"], n)),
            })

        if path == "/profiles.json":
            # The presentation table, verbatim. Clients that cannot parse
            # oklch() - a native app, say - get the raw components and the
            # same lightness rules the CSS uses, so their palette is derived
            # from these numbers rather than hand-copied out of the page.
            return self.send_json(200, {
                "max_level": MAX_LEVEL,
                "level_words": ["unrestricted", "limited", "focused",
                                "locked down"],
                "lightness": {"light": {"accent": 0.48, "ink": 0.32,
                                        "wash": 0.972, "edge": 0.88},
                              "dark": {"accent": 0.80, "ink": 0.90,
                                       "wash": 0.255, "edge": 0.42}},
                "profiles": {
                    name: {"level": m["level"], "hue": m["hue"],
                           "chroma": m["c"], "desc": m["desc"],
                           # unbricked is the escape hatch: its tag is an
                           # absolute /select, everything else toggles.
                           "kind": "select" if name == "unbricked" else "toggle"}
                    for name, m in PROFILES.items()},
            })

        if path in ("/schedules", "/schedules.json"):
            if path.endswith(".json") or self.wants_json():
                return self.send_json(200, {
                    "now": time.strftime("%Y-%m-%dT%H:%M"),
                    "tz": time.strftime("%Z"),
                    "max": scheduler.MAX_SCHEDULES,
                    "day_names": scheduler.DAY_NAMES,
                    # Which profiles may be scheduled at all. A profile that
                    # blocks nothing is absent: scheduling it would be a
                    # scheduled unbrick.
                    "schedulable": sorted(
                        scheduler.schedulable(briqctl.list_profiles()),
                        key=lambda n: (meta(n)["level"], n)),
                    "schedules": [sched_json(s) for s in sched_items()],
                })
            return self.send(200, page("Scheduled bricks", self.schedules_body(),
                                       briqctl.current_profile()))

        if path in ("/", "/status"):
            cur = briqctl.current_profile()
            if self.wants_json():
                return self.send_json(200, self.status_payload())
            return self.send(200, page("Briq", self.status_body(), cur))

        if path == "/profile":
            # Plain text, one word, NO trailing newline. Exists so an Apple
            # Shortcut or an Android automation can read the active profile
            # with one "Get Contents of URL" and compare it with "is equal
            # to" - a trailing newline would make every such test fail.
            return self.send(200, briqctl.current_profile().encode(),
                             "text/plain; charset=utf-8")

        if path == "/level":
            # Numeric restriction level 0-3, for automations that want
            # "at least this strict" rather than an exact profile match.
            # Also bare, for the same reason.
            return self.send(200,
                             str(meta(briqctl.current_profile())["level"]).encode(),
                             "text/plain; charset=utf-8")

        if path == "/profile.json":
            # Cheap sibling of /status.json: reads only the state file, with
            # no DNS probe and no AdGuard call, so an app-launch automation
            # is not held up. Shortcuts parses application/json into a real
            # dictionary, which sidesteps its "has any value" typing problem
            # with plain-text results.
            cur = briqctl.current_profile()
            lvl = meta(cur)["level"]
            since = briqctl.profile_since()
            return self.send(200, json.dumps({
                "profile": cur,
                "level": lvl,
                "restricted": lvl > 0,
                # How long you have been in this profile, from the Pi's clock
                # so every client agrees no matter which one caused it.
                "since": since,
                "since_s": max(0, int(time.time()) - since) if since else -1,
            }).encode(), "application/json; charset=utf-8")

        if path == "/status.json":
            return self.send(200,
                             json.dumps(self.status_payload(), indent=2).encode(),
                             "application/json; charset=utf-8")

        for kind in ("select", "toggle"):
            sel = self.route(kind)
            if not sel:
                continue
            profile, token = sel
            if not self.check_token(token):
                briqctl.log("http.bad_token", source=self.client_ip(),
                             profile=profile, kind=kind)
                return self.deny(403, "Invalid link.", reason="bad_token")
            if profile not in briqctl.list_profiles():
                return self.deny(404, "No such profile.", reason="unknown_profile")

            cur = briqctl.current_profile()
            target = toggle_target(profile, cur) if kind == "toggle" else profile
            tm = meta(target)

            if self.wants_json():
                # The confirmation page, as data. A client that scans a tag
                # can render "this will switch you to X" immediately, while
                # the POST it fires in parallel is still applying.
                return self.send_json(200, {
                    "profile": cur, "target": target, "kind": kind,
                    "noop": target == cur,
                    "loosens": meta(target)["level"] < meta(cur)["level"],
                })

            if kind == "toggle" and target == cur:
                body = ("<h1>Already set</h1><div class=state>"
                        "<div class=label>Current profile</div>"
                        "<div class=now>%s</div><p>%s</p>%s</div>"
                        "<a class=btn href='/status'>Back to status</a>"
                        % (html.escape(cur), html.escape(tm["desc"]),
                           meter(tm["level"])))
                return self.send(200, page("Briq", body, cur))

            if target == "unbricked":
                head, btn = "Lift all restrictions?", "Lift restrictions"
            else:
                head = btn = "Switch to %s" % target
                head += "?"
            body = ("<h1>%s</h1>"
                    "<div class=state><div class=label>Will become</div>"
                    "<div class=now>%s</div><p>%s</p>%s</div>"
                    "<div class=card><table><tr><td>Now</td><td>%s</td></tr>"
                    "</table></div>"
                    "<form method=POST action='%s'>"
                    "<input type=hidden name=confirm value=yes>"
                    "<button class='p-%s' type=submit>%s</button></form>"
                    "<a class=btn href='/status'>Cancel</a>"
                    % (html.escape(head), html.escape(target),
                       html.escape(tm["desc"]), meter(tm["level"]),
                       html.escape(cur), html.escape(self.path),
                       html.escape(target), html.escape(btn)))
            return self.send(200, page("Confirm", body, target))

        return self.deny(404, "Not found.", reason="not_found")

    def do_HEAD(self):
        self.do_GET()

    def body_params(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(min(length, 4096)).decode("utf-8", "replace")
        return parse_qs(raw)

    def apply(self, target, note_from):
        try:
            res = briqctl.apply_profile(target, source=self.client_ip())
        except briqctl.BriqError as exc:
            briqctl.log("http.apply_error", source=self.client_ip(),
                         profile=target, error=str(exc)[:300])
            # Two failures matter to a client and read alike in prose: a
            # rollback (the brick is unchanged and healthy) and a lock
            # collision (someone else is mid-apply, retry later).
            msg = str(exc)
            if "rolled back" in msg:
                reason = "rolled_back"
            elif "in progress" in msg:
                reason = "busy"
            else:
                reason = "apply_failed"
            self.deny(500, "Could not apply: %s" % exc, reason=reason)
            return None
        if self.wants_json():
            self.send_json(200, dict(res, applied=True))
            return res
        note = ("<div class=card><b class=ok>Applied.</b> %s &rarr; %s</div>"
                % (html.escape(res["previous"]), html.escape(res["profile"])))
        self.send(200, page("Applied", self.status_body(note), res["profile"]))
        return res

    def schedules_post(self, parts):
        """
        /schedules                    add
        /schedules/<id>/delete        remove, while it has never run
        /schedules/<id>/delete/<tok>  remove, with the tag's authority

        The nonce requirement is the same one /escalate carries: it proves the
        request came from something we served, and it is not a credential.
        The token is the credential, and it is only needed on the path that
        takes a restriction away.
        """
        params = self.body_params()

        if len(parts) == 1:
            if not burn_nonce(params.get("nonce", [""])[0]):
                return self.deny(403, "Expired page. Reload and try again.",
                                 reason="bad_nonce")
            if throttled(self.client_ip()):
                return self.deny(429, "Too many changes. Wait a minute.",
                                 reason="throttled")
            raw = (params.get("time", [""])[0] or "").strip()
            if raw:
                bits = raw.split(":")
                hour, minute = bits[0], (bits[1] if len(bits) > 1 else "0")
            else:
                hour = params.get("hour", [""])[0]
                minute = params.get("minute", ["0"])[0]
            # Checkboxes arrive as repeats, a native client sends one field.
            days = []
            for chunk in params.get("days", []):
                for d in chunk.split(","):
                    if d.strip().lstrip("-").isdigit():
                        days.append(int(d.strip()))
            try:
                rec = scheduler.add(params.get("profile", [""])[0],
                                    hour, minute, days,
                                    source=self.client_ip())
            except scheduler.ScheduleError as exc:
                return self.deny(400, exc.detail, reason=exc.reason)
            if self.wants_json():
                return self.send_json(200, sched_json(scheduler.describe(rec)))
            note = ("<div class=card><b class=ok>Scheduled.</b> %s at %02d:%02d, "
                    "%s</div>" % (html.escape(rec["profile"]), rec["hour"],
                                  rec["minute"],
                                  html.escape(scheduler.days_label(rec["days"]))))
            return self.send(200, page("Scheduled bricks",
                                       self.schedules_body(note),
                                       briqctl.current_profile()))

        if len(parts) in (3, 4) and parts[2] == "delete":
            sched_id = unquote(parts[1])
            token = unquote(parts[3]) if len(parts) == 4 else None
            if token is not None:
                if not self.check_token(token):
                    briqctl.log("http.bad_token", source=self.client_ip(),
                                 kind="schedule_delete")
                    return self.deny(403, "Invalid link.", reason="bad_token")
            elif not burn_nonce(params.get("nonce", [""])[0]):
                return self.deny(403, "Expired page. Reload and try again.",
                                 reason="bad_nonce")
            try:
                scheduler.remove(sched_id, authorised=token is not None,
                                 source=self.client_ip())
            except scheduler.ScheduleError as exc:
                code = {"needs_tag": 403, "unknown_schedule": 404}.get(
                    exc.reason, 400)
                return self.deny(code, exc.detail, reason=exc.reason)
            if self.wants_json():
                return self.send_json(200, {"removed": sched_id})
            return self.send(200, page(
                "Scheduled bricks",
                self.schedules_body("<div class=card><b class=ok>Removed.</b>"
                                    "</div>"),
                briqctl.current_profile()))

        return self.deny(404, "Not found.", reason="not_found")

    def do_POST(self):
        if not self.gate():
            return
        path = self.path.split("?")[0]

        # --- token-free escalation -------------------------------------
        parts = [p for p in path.split("/") if p]
        if len(parts) == 2 and parts[0] == "escalate":
            target = unquote(parts[1])
            params = self.body_params()
            if not burn_nonce(params.get("nonce", [""])[0]):
                return self.deny(403, "Expired page. Reload and try again.",
                                 reason="bad_nonce")
            if target not in briqctl.list_profiles():
                return self.deny(404, "No such profile.", reason="unknown_profile")
            cur = briqctl.current_profile()
            try:
                allowed = briqctl.is_stricter(target, cur)
            except briqctl.BriqError as exc:
                return self.deny(500, "Could not evaluate: %s" % exc,
                                 reason="evaluate_failed")
            if not allowed:
                briqctl.log("http.escalate_refused", source=self.client_ip(),
                             profile=target, previous=cur)
                return self.deny(
                    403, "That would loosen the brick. Use the QR code or NFC tag.",
                    reason="would_loosen")
            if throttled(self.client_ip()):
                return self.deny(429, "Too many changes. Wait a minute.",
                                 reason="throttled")
            briqctl.log("http.escalate", source=self.client_ip(),
                         profile=target, previous=cur)
            return self.apply(target, "escalate") and None

        # --- scheduled bricks -------------------------------------------
        if parts and parts[0] == "schedules":
            return self.schedules_post(parts)

        # --- display settings -------------------------------------------
        # Changes nothing about the brick, so a nonce is the whole gate.
        if parts == ["settings"]:
            params = self.body_params()
            if not burn_nonce(params.get("nonce", [""])[0]):
                return self.deny(403, "Expired page. Reload and try again.",
                                 reason="bad_nonce")
            changed = {}
            for key in SETTING_DEFAULTS:
                if key not in params:
                    continue
                raw = params[key][0].strip().lower()
                if raw not in ("on", "off", "true", "false", "1", "0", "yes", "no"):
                    return self.deny(400, "Not a setting value.",
                                     reason="bad_setting")
                changed = set_setting(key, raw in ("on", "true", "1", "yes"))
            if not changed:
                return self.deny(400, "No known setting given.",
                                 reason="unknown_setting")
            briqctl.log("settings.changed", source=self.client_ip(), **changed)
            if self.wants_json():
                return self.send_json(200, changed)
            return self.send(200, page("Briq", self.status_body(),
                                       briqctl.current_profile()))

        # --- token paths ------------------------------------------------
        for kind in ("select", "toggle"):
            sel = self.route(kind)
            if not sel:
                continue
            profile, token = sel
            if not self.check_token(token):
                briqctl.log("http.bad_token", source=self.client_ip(),
                             profile=profile, kind=kind)
                return self.deny(403, "Invalid link.", reason="bad_token")
            if self.body_params().get("confirm", [""])[0] != "yes":
                return self.deny(400, "Missing confirmation.", reason="missing_confirm")
            if profile not in briqctl.list_profiles():
                return self.deny(404, "No such profile.", reason="unknown_profile")

            cur = briqctl.current_profile()
            target = toggle_target(profile, cur) if kind == "toggle" else profile

            if kind == "toggle":
                # A double tap must never toggle twice. Two cases to cover:
                # the second tap arriving while the first apply is still
                # running (applies take tens of seconds on this hardware),
                # and one arriving shortly after it finished. The window is
                # therefore measured from COMPLETION, not from the start.
                with _tlock:
                    busy = profile in _inflight
                    recent = (time.time() - _last_toggle.get(profile, 0)
                              < TOGGLE_DEBOUNCE_S)
                    if not busy and not recent:
                        _inflight.add(profile)
                if busy or recent:
                    briqctl.log("http.toggle_debounced",
                                 source=self.client_ip(), profile=profile,
                                 reason="in-flight" if busy else "recent")
                    if self.wants_json():
                        # 200, not an error: the tap was handled correctly.
                        # `in_flight` tells a client whether to keep showing
                        # a spinner (the first tap is still applying) or to
                        # settle (it already finished moments ago).
                        return self.send_json(200, {
                            "applied": False, "debounced": True,
                            "in_flight": busy, "profile": cur,
                            "retry_after": TOGGLE_DEBOUNCE_S})
                    note = ("<div class=card>Repeat scan ignored, so a double "
                            "tap cannot undo itself.</div>")
                    return self.send(200, page("Briq",
                                               self.status_body(note), cur))

            if throttled(self.client_ip()):
                briqctl.log("http.throttled", source=self.client_ip())
                if kind == "toggle":
                    with _tlock:
                        _inflight.discard(profile)
                        _last_toggle[profile] = time.time()
                return self.deny(429, "Too many changes. Wait a minute.",
                                 reason="throttled")

            try:
                self.apply(target, kind)
            finally:
                if kind == "toggle":
                    with _tlock:
                        _inflight.discard(profile)
                        _last_toggle[profile] = time.time()
            return None

        return self.deny(404, "Not found.", reason="not_found")


def meter_word(name):
    return ["unrestricted", "limited", "focused", "locked down"][
        min(meta(name)["level"], 3)]


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    address_family = socket.AF_INET6

    def server_bind(self):
        # Must be cleared BEFORE bind(), otherwise the kernel rejects it and
        # IPv4 clients cannot reach the controller at all.
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        super().server_bind()


def main():
    if not TOKEN or len(TOKEN) < 24:
        print("BRIQ_TOKEN missing or too short; refusing to start", file=sys.stderr)
        return 1
    srv = Server(("::", PORT), Handler)
    # In-process rather than a cron line or a systemd timer: a schedule has to
    # be able to see the current profile and refuse to loosen it, which is the
    # controller's own logic. It is a daemon thread, so it dies with the
    # server rather than holding a restart open.
    scheduler.start()
    briqctl.log("controller.start", port=PORT, trusted=TRUSTED,
                 schedules=len(scheduler.load()))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
