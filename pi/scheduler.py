#!/usr/bin/env python3
"""
Briq scheduler - time-of-day escalations.

A schedule ARMS a restriction; it can never lift one. At the scheduled minute
the target profile is applied only if briqctl.is_stricter() agrees the switch
cannot loosen the brick, so a schedule left running for months never becomes a
way to unbrick yourself automatically. The release stays manual, which is the
whole point of the product: a scheduled deep-focus at 06:00 holds until you
walk to the tag.

Two consequences fall out of that rule and are enforced here rather than in
the UI:

  * a profile that blocks nothing cannot be scheduled at all - it would be a
    scheduled *un*brick;
  * a schedule that fires while you are already stricter is skipped, not
    applied, because applying it would be a relaxation.

State lives in one JSON file next to `current`. It is small, rewritten
atomically, and safe to delete by hand: no schedules is a valid state.

Times are naive local time on purpose. "06:00" means the six o'clock you wake
up to, so it must follow the Pi's timezone across a DST change rather than
drift by an hour twice a year.
"""

import json
import os
import re
import secrets
import threading
import time
from datetime import datetime, timedelta

import briqctl

SCHED_FILE = os.path.join(briqctl.STATE_DIR, "schedules.json")

# The scheduler is not a cron: the Pi reboots, and an apply that collides with
# a manual one has to wait. So a missed minute is still fired when the
# controller comes back, but only for this long afterwards - waking the Pi at
# noon must not suddenly impose the brick you wanted at six.
CATCHUP_S = 30 * 60

TICK_S = 20

# One person, one device. A cap keeps a runaway client from filling the state
# file, and twenty is far past anything a human would actually keep.
MAX_SCHEDULES = 20

DAY_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,16}$")

_lock = threading.RLock()


class ScheduleError(Exception):
    """Rejected input. Carries a stable slug for the JSON error path."""

    def __init__(self, reason, detail):
        super().__init__(detail)
        self.reason = reason
        self.detail = detail


# --------------------------------------------------------------------------
# storage
# --------------------------------------------------------------------------

def _read():
    try:
        with open(SCHED_FILE, encoding="utf-8") as fh:
            data = json.load(fh)
    except FileNotFoundError:
        return []
    except (OSError, ValueError) as exc:
        # A corrupt file must not take the controller down with it, and must
        # not silently vanish either: rename it and carry on with none.
        briqctl.log("schedule.unreadable", error=str(exc)[:200])
        try:
            os.replace(SCHED_FILE, SCHED_FILE + ".corrupt")
        except OSError:
            pass
        return []
    out = []
    for s in data.get("schedules", []):
        try:
            out.append({
                "id": str(s["id"]),
                "profile": str(s["profile"]),
                "hour": int(s["hour"]),
                "minute": int(s["minute"]),
                "days": sorted({int(d) for d in s["days"] if 0 <= int(d) <= 6}),
                "created": s.get("created", ""),
                "armed": bool(s.get("armed", False)),
                "fired": int(s.get("fired", 0)),
                "last_key": s.get("last_key") or "",
                "last_fired": s.get("last_fired") or "",
            })
        except (KeyError, TypeError, ValueError):
            continue
    return out


def _write(schedules):
    tmp = SCHED_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump({"schedules": schedules}, fh, indent=1, sort_keys=True)
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, SCHED_FILE)


def load():
    with _lock:
        return _read()


# --------------------------------------------------------------------------
# labels and timing
# --------------------------------------------------------------------------

def days_label(days):
    d = sorted(set(days))
    if not d:
        return "never"
    if d == list(range(7)):
        return "Every day"
    if d == [0, 1, 2, 3, 4]:
        return "Mon–Fri"
    if d == [5, 6]:
        return "Weekends"
    if len(d) > 2 and d == list(range(d[0], d[-1] + 1)):
        return "%s–%s" % (DAY_NAMES[d[0]], DAY_NAMES[d[-1]])
    return ", ".join(DAY_NAMES[i] for i in d)


def next_fire(s, now=None):
    """The next instant this schedule is due, or None if it never is."""
    now = now or datetime.now()
    for ahead in range(0, 8):
        d = (now + timedelta(days=ahead)).replace(
            hour=s["hour"], minute=s["minute"], second=0, microsecond=0)
        if d.weekday() in s["days"] and d > now:
            return d
    return None


def next_label(dt, now=None):
    if dt is None:
        return "never"
    now = now or datetime.now()
    days = (dt.date() - now.date()).days
    when = ("today" if days == 0 else
            "tomorrow" if days == 1 else DAY_NAMES[dt.weekday()])
    return "%s %02d:%02d" % (when, dt.hour, dt.minute)


def due_instant(s, now):
    """
    The most recent instant this schedule was due at, or None.

    Yesterday is checked as well as today so a schedule set for 23:50 still
    fires when the tick that notices it happens after midnight.
    """
    for back in (0, 1):
        d = (now - timedelta(days=back)).replace(
            hour=s["hour"], minute=s["minute"], second=0, microsecond=0)
        if d.weekday() in s["days"] and d <= now:
            return d
    return None


def describe(s, now=None):
    """One schedule as the wire format every client renders from."""
    now = now or datetime.now()
    nxt = next_fire(s, now)
    return {
        "id": s["id"],
        "profile": s["profile"],
        "time": "%02d:%02d" % (s["hour"], s["minute"]),
        "hour": s["hour"],
        "minute": s["minute"],
        "days": s["days"],
        "days_label": days_label(s["days"]),
        # Armed means it has already run at least once, which is what makes
        # removing it a loosening rather than a correction.
        "armed": s["armed"],
        "fired": s["fired"],
        "last_fired": s["last_fired"],
        "next_fire": nxt.strftime("%Y-%m-%dT%H:%M") if nxt else "",
        "next_in_s": int((nxt - now).total_seconds()) if nxt else -1,
        "next_label": next_label(nxt, now),
    }


# --------------------------------------------------------------------------
# mutation
# --------------------------------------------------------------------------

def _restrictive(profile):
    """
    True if this profile actually blocks something.

    Derived from the domain sets rather than from a level table, for the same
    reason is_stricter() is: editing a profile file must not be able to turn a
    scheduled brick into a scheduled unbrick behind our back.
    """
    blocks, _allows, block_all = briqctl.load_profile(profile)
    return bool(blocks) or block_all


def schedulable(names):
    """Of `names`, the ones that can legitimately be scheduled."""
    out = []
    for n in names:
        try:
            if _restrictive(n):
                out.append(n)
        except briqctl.BriqError:
            continue
    return out


def add(profile, hour, minute, days, source="local"):
    if profile not in briqctl.list_profiles():
        raise ScheduleError("unknown_profile", "No such profile.")
    try:
        hour, minute = int(hour), int(minute)
    except (TypeError, ValueError):
        raise ScheduleError("bad_time", "Time must be HH:MM.")
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        raise ScheduleError("bad_time", "Time must be between 00:00 and 23:59.")
    days = sorted({int(d) for d in days})
    if not days or any(d < 0 or d > 6 for d in days):
        raise ScheduleError("bad_days", "Pick at least one weekday.")
    try:
        if not _restrictive(profile):
            raise ScheduleError(
                "not_restrictive",
                "%s blocks nothing, so scheduling it would be a scheduled "
                "unbrick. Lifting the brick stays manual." % profile)
    except briqctl.BriqError as exc:
        raise ScheduleError("unreadable_profile", str(exc))

    with _lock:
        schedules = _read()
        if len(schedules) >= MAX_SCHEDULES:
            raise ScheduleError("too_many",
                                "Already %d schedules." % MAX_SCHEDULES)
        for s in schedules:
            if (s["profile"] == profile and s["hour"] == hour
                    and s["minute"] == minute and s["days"] == days):
                raise ScheduleError("duplicate", "That schedule already exists.")
        rec = {
            "id": secrets.token_urlsafe(4),
            "profile": profile,
            "hour": hour,
            "minute": minute,
            "days": days,
            "created": datetime.now().strftime("%Y-%m-%dT%H:%M"),
            "armed": False,
            "fired": 0,
            "last_key": "",
            "last_fired": "",
        }
        schedules.append(rec)
        _write(schedules)
    briqctl.log("schedule.added", id=rec["id"], profile=profile,
                 at="%02d:%02d" % (hour, minute), days=days, source=source)
    return rec


def get(sched_id):
    if not ID_RE.match(sched_id or ""):
        return None
    for s in load():
        if s["id"] == sched_id:
            return s
    return None


def remove(sched_id, authorised=False, source="local"):
    """
    Delete a schedule.

    An unarmed schedule is a plan, and correcting a plan is free. Once it has
    run, deleting it removes a restriction that is already part of your week -
    so that needs the tag, exactly like every other loosening.
    """
    with _lock:
        schedules = _read()
        found = next((s for s in schedules if s["id"] == sched_id), None)
        if not found:
            raise ScheduleError("unknown_schedule", "No such schedule.")
        if found["armed"] and not authorised:
            raise ScheduleError(
                "needs_tag",
                "This schedule has already run once. Scan the tag to remove it.")
        _write([s for s in schedules if s["id"] != sched_id])
    briqctl.log("schedule.removed", id=sched_id, profile=found["profile"],
                 armed=found["armed"], source=source)
    return found


def _mark(sched_id, key, fired):
    with _lock:
        schedules = _read()
        for s in schedules:
            if s["id"] != sched_id:
                continue
            s["last_key"] = key
            s["armed"] = True
            if fired:
                s["fired"] += 1
                s["last_fired"] = datetime.now().strftime("%Y-%m-%dT%H:%M")
        _write(schedules)


# --------------------------------------------------------------------------
# the tick
# --------------------------------------------------------------------------

def fire(s, key):
    cur = briqctl.current_profile()
    try:
        tighten = briqctl.is_stricter(s["profile"], cur)
    except briqctl.BriqError as exc:
        briqctl.log("schedule.unreadable_profile", id=s["id"],
                     profile=s["profile"], error=str(exc)[:200])
        _mark(s["id"], key, fired=False)
        return

    if not tighten:
        # Already at least this restricted - or the schedule would relax you.
        # Either way it is a no-op, and it counts as having run.
        briqctl.log("schedule.skipped", id=s["id"], profile=s["profile"],
                     current=cur, reason="not_stricter")
        _mark(s["id"], key, fired=False)
        return

    briqctl.log("schedule.firing", id=s["id"], profile=s["profile"],
                 previous=cur, due=key)
    try:
        res = briqctl.apply_profile(s["profile"], source="schedule")
    except briqctl.BriqError as exc:
        msg = str(exc)
        briqctl.log("schedule.failed", id=s["id"], profile=s["profile"],
                     error=msg[:300])
        # A lock collision is temporary: someone scanned a tag in the same
        # minute. Leave the instant unmarked so the next tick retries it,
        # still inside the catch-up window. Anything else (a rollback) has
        # already been retried by apply_profile and must not spin.
        if "in progress" not in msg:
            _mark(s["id"], key, fired=False)
        return
    _mark(s["id"], key, fired=True)
    briqctl.log("schedule.applied", id=s["id"], profile=res["profile"],
                 previous=res["previous"], changed=res["changed"])


def tick(now=None):
    now = now or datetime.now()
    for s in load():
        due = due_instant(s, now)
        if due is None:
            continue
        key = due.strftime("%Y-%m-%dT%H:%M")
        if s["last_key"] == key:
            continue
        if (now - due).total_seconds() > CATCHUP_S:
            # Too old to impose retroactively. Deliberately not marked: the
            # next occurrence is a different key, so nothing is lost.
            continue
        fire(s, key)


def run_forever():
    briqctl.log("schedule.watch", count=len(load()), tick=TICK_S)
    while True:
        try:
            tick()
        except Exception as exc:                      # noqa: BLE001
            # The watcher outliving one bad tick matters more than the tick.
            briqctl.log("schedule.tick_error", error=str(exc)[:300])
        time.sleep(TICK_S)


def start():
    """Run the watcher in the background. Returns the thread."""
    t = threading.Thread(target=run_forever, name="scheduler", daemon=True)
    t.start()
    return t


if __name__ == "__main__":
    print(json.dumps([describe(s) for s in load()], indent=2))
