#!/usr/bin/env python3
"""Checks the since-clock rules against temp files. Touches no live state."""
import os
import sys
import tempfile
import time

sys.path.insert(0, "/opt/briq-control")
import briqctl

d = tempfile.mkdtemp()
briqctl.CURRENT_FILE = os.path.join(d, "current")
briqctl.SINCE_FILE = os.path.join(d, "since")
briqctl.log = lambda *a, **k: None

bad = 0


def check(label, cond):
    global bad
    bad += 0 if cond else 1
    print(("PASS  " if cond else "FAIL  ") + label)


check("no state yet reads as unknown", briqctl.profile_since() == 0)

briqctl.write_current("deep-focus")
first = briqctl.profile_since()
check("a change starts the clock", abs(first - time.time()) < 5)

time.sleep(1.2)
briqctl.write_current("deep-focus")
check("re-selecting the same profile does not reset it",
      briqctl.profile_since() == first)

time.sleep(1.2)
briqctl.write_current("offline")
check("a real change restarts it", briqctl.profile_since() > first)

os.remove(briqctl.SINCE_FILE)
check("without the file it falls back to the state file's mtime",
      abs(briqctl.profile_since() - os.path.getmtime(briqctl.CURRENT_FILE)) < 2)

print("\n%d failed" % bad)
sys.exit(1 if bad else 0)
