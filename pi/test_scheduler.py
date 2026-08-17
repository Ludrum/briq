#!/usr/bin/env python3
"""Offline checks for the firing rules. Never touches AdGuard."""
import sys
import tempfile
from datetime import datetime, timedelta

sys.path.insert(0, "/opt/briq-control")
import briqctl
import scheduler

scheduler.SCHED_FILE = tempfile.mktemp(suffix="-sched.json")

applied = []
current = ["unbricked"]
fail_with = [None]

briqctl.log = lambda *a, **k: None
briqctl.current_profile = lambda: current[0]
briqctl.list_profiles = lambda: ["unbricked", "social", "video", "deep-focus", "offline"]
scheduler._restrictive = lambda n: n != "unbricked"
LEVEL = {"unbricked": 0, "social": 1, "video": 1, "deep-focus": 2, "offline": 3}
briqctl.is_stricter = lambda new, cur: LEVEL[new] > LEVEL[cur]


def fake_apply(name, source="local", force=False):
    if fail_with[0]:
        raise briqctl.BriqError(fail_with[0])
    prev, current[0] = current[0], name
    applied.append((name, source))
    return {"profile": name, "previous": prev, "changed": True}


briqctl.apply_profile = fake_apply

ok = [0]
def check(label, cond):
    ok[0] += 0 if cond else 1
    print(("PASS  " if cond else "FAIL  ") + label)


# Monday 2026-08-17 06:00 is the instant under test.
MON = datetime(2026, 8, 17, 6, 0)
rec = scheduler.add("deep-focus", 6, 0, [0, 1, 2, 3, 4])
sid = rec["id"]

scheduler.tick(MON - timedelta(minutes=1))
check("does not fire a minute early", not applied)

scheduler.tick(MON)
check("fires at the scheduled minute", applied == [("deep-focus", "schedule")])
check("marks armed", scheduler.get(sid)["armed"] and scheduler.get(sid)["fired"] == 1)

scheduler.tick(MON + timedelta(seconds=20))
scheduler.tick(MON + timedelta(minutes=5))
check("never fires the same instant twice", len(applied) == 1)

# Manual unbrick after the schedule ran: it must stay unbricked.
current[0] = "unbricked"
scheduler.tick(MON + timedelta(minutes=20))
check("an unbrick after firing is not undone", current[0] == "unbricked")

# Next week's occurrence is a different instant.
scheduler.tick(MON + timedelta(days=7))
check("fires again the following Monday", len(applied) == 2)

# Saturday is not in the day set.
current[0] = "unbricked"
scheduler.tick(datetime(2026, 8, 22, 6, 0))
check("silent on a day it is not set for", len(applied) == 2)

# Already stricter: firing would relax.
current[0] = "offline"
scheduler.tick(datetime(2026, 8, 18, 6, 0))
check("skipped while already stricter", len(applied) == 2 and current[0] == "offline")
check("a skip still counts as having run", scheduler.get(sid)["fired"] == 2)

# Pi was down at 06:00 and came back at 06:20, then at 08:00.
current[0] = "unbricked"
scheduler.tick(datetime(2026, 8, 19, 6, 20))
check("catches up inside the window", len(applied) == 3)
current[0] = "unbricked"
scheduler.tick(datetime(2026, 8, 20, 8, 0))
check("does not impose a long-missed brick", len(applied) == 3)
check("a missed instant is not marked", scheduler.get(sid)["last_key"] == "2026-08-19T06:00")

# A collision with a manual apply must be retried, not swallowed.
current[0] = "unbricked"
fail_with[0] = "another profile change is in progress"
scheduler.tick(datetime(2026, 8, 21, 6, 0))
check("a busy Pi leaves the instant unmarked", scheduler.get(sid)["last_key"] == "2026-08-19T06:00")
fail_with[0] = None
scheduler.tick(datetime(2026, 8, 21, 6, 1))
check("and the next tick fires it", len(applied) == 4)

# A rollback must not spin.
current[0] = "unbricked"
fail_with[0] = "apply failed, rolled back to unbricked: boom"
scheduler.tick(datetime(2026, 8, 24, 6, 0))
check("a rollback marks the instant", scheduler.get(sid)["last_key"] == "2026-08-24T06:00")
fail_with[0] = None

# Just before midnight, noticed after it.
r2 = scheduler.add("social", 23, 50, [0])
scheduler.tick(datetime(2026, 8, 25, 0, 5))
check("fires an instant that fell on the far side of midnight",
      applied[-1][0] == "social")

check("Mon–Fri label", scheduler.days_label([0, 1, 2, 3, 4]) == "Mon–Fri")
check("every day label", scheduler.days_label(list(range(7))) == "Every day")
check("gap label", scheduler.days_label([0, 2, 4]) == "Mon, Wed, Fri")
check("run label", scheduler.days_label([1, 2, 3]) == "Tue–Thu")
check("next fire skips to the day set",
      scheduler.next_fire({"hour": 6, "minute": 0, "days": [0]},
                          datetime(2026, 8, 18, 7, 0)) == datetime(2026, 8, 24, 6, 0))

for bad, why in [
    (("unbricked", 6, 0, [0]), "not_restrictive"),
    (("deep-focus", 25, 0, [0]), "bad_time"),
    (("deep-focus", 6, 0, []), "bad_days"),
    (("deep-focus", 6, 0, [9]), "bad_days"),
    (("nope", 6, 0, [0]), "unknown_profile"),
]:
    try:
        scheduler.add(*bad)
        check("refuses %s" % why, False)
    except scheduler.ScheduleError as exc:
        check("refuses %s" % why, exc.reason == why)

try:
    scheduler.remove(sid)
    check("armed schedule needs the tag", False)
except scheduler.ScheduleError as exc:
    check("armed schedule needs the tag", exc.reason == "needs_tag")
scheduler.remove(sid, authorised=True)
check("the tag removes it", scheduler.get(sid) is None)
try:
    scheduler.remove(r2["id"])          # it fired at the midnight check above
    check("firing arms a schedule for good", False)
except scheduler.ScheduleError as exc:
    check("firing arms a schedule for good", exc.reason == "needs_tag")
scheduler.remove(r2["id"], authorised=True)

fresh = scheduler.add("social", 9, 30, [3])
scheduler.remove(fresh["id"])
check("an unarmed one goes freely", scheduler.get(fresh["id"]) is None)

print("\n%d failed" % ok[0])
sys.exit(1 if ok[0] else 0)
