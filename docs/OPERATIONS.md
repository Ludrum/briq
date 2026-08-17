# Operations

Day-to-day use, and how to undo any of it.

## The CLI

```bash
sudo briqctl status                      # current profile and health
sudo briqctl list                        # available profiles, * = active
sudo briqctl apply deep-focus            # switch (20-30s)
sudo briqctl render offline              # preview rules, changes nothing
sudo briqctl clients                     # AdGuard clients and brick scope
sudo briqctl add-client <name> <id>...   # register a device (replaces ids)
sudo briqctl add-client-id <name> <id>   # add an id, keep existing
sudo briqctl restore                     # re-apply last-known-good rules
```

## The web page

`http://<PI_IP>:8088` — or just `http://<PI_IP>` if you installed the
nftables template, which redirects port 80.

It can **only make the brick stricter**, and needs no token to do so. It
offers the profiles that are a strict superset of the current one. It refuses
a sideways move too: `social` cannot become `video`, because `video` does not
block Instagram, so that would be a relaxation.

The "stricter" test is derived from the actual domain sets, not from a
hand-maintained ranking, so editing a profile file cannot silently turn a
relaxation into something the page will accept.

Lifting always needs the QR code or an NFC tag.

## Read-only endpoints, for device automations

All are GET-safe, change nothing, and need no token.

| Endpoint | Returns |
|---|---|
| `/profile.json` | `{"profile","level","restricted","since","since_s"}` — **use this one**, ~250 ms |
| `/profile` | `deep-focus` — plain text, no trailing newline |
| `/level` | `2` — 0 unbricked, 1 social/video, 2 deep-focus, 3 offline |
| `/status.json` | full health, ~1.5 s — too slow for an app-launch trigger |
| `/profiles.json` | the profile list with hue/chroma/level, what the app derives its colours from |
| `/schedules.json` | scheduled bricks, and which profiles may be scheduled |
| `/healthz` | liveness |

`since_s` comes from the Pi's clock, which is what makes the elapsed timer
read the same on every device no matter which one caused the change.

### iOS Shortcuts, and three traps (workaround for Instagram App, due to caching)

Idea: Because Instagram caches quite alot of content (persists even when refreshing while blocked),
this shortcut checks on opening if Instagram is supposed to be blocked right now and then immideatly
opens another app, to prevent the user from scrolling.

Automation → App → *(the app)* → When Opened → Run Immediately.

1. **Get Contents of URL** → `http://<PI_IP>/profile.json`
2. **Get Dictionary Value** → key `level`
3. **If** → *Dictionary Value* → **is greater than** → `0`
4. → **Open App** → Settings

- **Type the URL by hand.** Pasting from a formatted source gives Shortcuts
  "Rich Text", and it refuses to convert that to a URL. Check for a leftover
  variable pill hiding at the start of the field.
- **If the If action only offers "has any value"**, it has not typed the
  value. Pulling it out of a dictionary fixes that. Seeing "is greater than"
  at all is proof the wiring is right.

This is friction, not a hard block: it bounces you out of the app and you can
reopen. Only an app holding Apple's Screen Time entitlement can truly prevent
a launch. What it does buy is that the block follows the Pi's profile with
nothing to switch on the device.

On Android, reading `/profile.json` needs an automation app such as Tasker.

## Scheduled bricks

`http://<PI_IP>:8088/schedules` — a time, a set of weekdays, a profile.

At that minute the controller applies it **only if it is strictly stricter
than what is active**, so a schedule can arm a restriction but never lift one.
There is no end time by design: a scheduled `deep-focus` at 06:00 holds until
you unbrick by hand. A schedule that could loosen would be an automatic way
out, set once and then forgotten.

Adding one is free. Removing one is free until it has run for the first time —
after that it is part of your week, and taking it away is a loosening, so it
needs the tag.

- If the Pi is down at the scheduled minute it still fires when it comes back,
  but only within 30 minutes (`CATCHUP_S` in `scheduler.py`) — a Pi that wakes
  at noon does not impose the brick you wanted at six.
- A schedule landing while you are already stricter is skipped and logged as
  `schedule.skipped`.
- State is `/var/lib/briq-control/schedules.json`. Deleting that file removes
  every schedule and is safe.
- The watcher is a daemon thread inside `controller.py`, not cron, because the
  "never loosen" check needs the controller's own logic.

## Editing the domain lists

Lists are plain data in `/opt/briq-control/profiles/`, one bare domain per
line, `#` for comments.

```bash
sudo nano /opt/briq-control/profiles/social.list
sudo briqctl render social      # validate + preview, changes nothing
sudo briqctl apply social       # only if render looked right
```

`render` rejects malformed domains before anything is written, so a typo
cannot reach AdGuard.

`deep-focus.list` is defined as `@include social` + `@include video` — edit
the two source lists, not it. `offline.list` uses `@blockall` plus `@allow`
exceptions.

Add domains you actually observe in the query log rather than guessing. TikTok
in particular rotates CDN names.

Two entries are deliberate and worth knowing about: `offline` allowlists NTP,
because without working time sync devices fail TLS validation and look broken
rather than blocked; and `social` does **not** block WhatsApp. Add it yourself
if you want it cut off.

## Adding a profile

Drop a new `.list` into `profiles/` and give it a hue, chroma and level in the
`PROFILES` dict in `controller.py`. The app derives every colour from
`/profiles.json`, so a new profile needs no app release.

## Rotating the token

If the QR sheet is photographed, shared, or you just want a fresh one:

```bash
sudo python3 -c "import secrets; print('BRIQ_TOKEN='+secrets.token_urlsafe(32))"
sudo nano /etc/briq-control/controller.env     # replace the BRIQ_TOKEN line
sudo systemctl restart briq-controller
sudo /opt/briq-control/gen-qr.py               # regenerate, then reprint
```

Rewrite the NFC tags too. Do not lock a tag until you have tested it.

## Services and logs

```bash
systemctl status AdGuardHome briq-controller nftables
sudo systemctl restart AdGuardHome        # DNS backend, ~20s before it answers
sudo systemctl restart briq-controller
sudo journalctl -u briq-controller -f
sudo tail -f /var/log/briq-control/briq.log   # audit log
```

The audit log records timestamp, profile, previous profile, source IP and
result. Tokens and secret URLs are never written to it or to the journal.

State lives in `/var/lib/briq-control/`: `current`, `since`,
`last-known-good.json`, `schedules.json`, `settings.json`.

## Rollback

| Undo | How |
|---|---|
| Any profile | `sudo briqctl apply unbricked` |
| A failed apply | Automatic — it restores the previous rules and reports the failure |
| Restore rules by hand | `sudo briqctl restore` |
| Remove Briq, keep the Pi as your DNS server | `sudo systemctl disable --now briq-controller` then `sudo briqctl apply unbricked` |
| Drop the firewall | `sudo nft flush ruleset` (returns at reboot unless you also `systemctl disable nftables`) |
| Restore the desktop | `sudo systemctl set-default graphical.target && sudo systemctl enable --now lightdm` |
| **Stop all household DNS** | `sudo systemctl stop AdGuardHome` **and** reset the router's local DNS server field |

That last row is the one to write on paper before you start. If the Pi dies,
nobody has working internet until the router's DNS field goes back.

Before changing AdGuard's config, take a backup of
`/opt/AdGuardHome/AdGuardHome.yaml`; restoring is a `cp` and a restart.

## Troubleshooting

**A profile is refused.** `clients.conf` lists no clients, or none of them
exist in AdGuard. `sudo briqctl clients`. This is intentional: an unscoped
rule would hit the whole house.

**A profile applied but a device is not blocked.** Its query is arriving from
an address you did not register — almost always a rotating temporary IPv6
address. Check the AdGuard query log for the source address, and re-read the
IPv6 section of [SETUP.md](SETUP.md).

**A blocked app still works.** Expected. DNS blocking cannot tear down an
established connection. Force-quit the app. If it still works, it is using
cached content, hard-coded IPs, or its own DNS-over-HTTPS.
The shortcut hack above helps with that, although it can also not remove
the problem entirely.

**`fritz.box` (or your router's name) resolves to a stranger.** The upstream
conditional-forwarding line from §2 of SETUP.md is missing.

**Everything is slow.** If you opted into AdGuard's blocklists, large
third-party ones will push a small Pi into swap and slow every lookup in the
house. Keep the list count modest, or drop them — they are not part of Briq
and removing them changes nothing about the profiles.

**The status page takes a minute.** That is the apply, and it is honest about
it — the controller verifies the rules are live before reporting success. Do
not re-scan.
