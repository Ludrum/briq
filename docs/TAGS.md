# QR codes and NFC tags

These are the physical objects that hold the token. They are the only way to
loosen the brick, and that is deliberate: you have to walk to them.

> **Treat the printed sheet and any `select`/`toggle` tag as a password.** The
> token is printed into the QR code and written onto the tag in plain,
> readable NDEF. Anyone on your LAN who photographs the sheet can lift your
> brick. See [OPERATIONS.md](OPERATIONS.md) for rotating the token.

---

## 1. The printed QR sheet

On the Pi:

```bash
sudo apt install qrencode
sudo /opt/briq-control/gen-qr.py
```

That writes one PNG per profile plus a printable `sheet.html` into
`/opt/briq-control/qr/`. Copy it off the Pi and print it:

```bash
scp pi:/opt/briq-control/qr/sheet.html .
```

The token is never written to stdout, to a filename or to any log, and the
images are produced locally by `qrencode` — nothing is uploaded anywhere.

`sheet.html` and `qr/` are gitignored. Do not commit them.

Cut the cards up and put them where the friction belongs: a drawer, another
room, wherever "go and get it" is a real decision.

### How the codes behave

Most are **toggles** — scan once to turn a profile on, scan the same code
again to lift it. So one `deep-focus` card both starts and ends a session.

`unbricked` is the exception and is **absolute**: it is your way back, so
scanning it must always land on `unbricked` and can never toggle away into
something stricter.

Opening a code's URL shows a confirmation page and **changes nothing**. The
profile only switches when you press the button, so a link preview or a
browser prefetch cannot flip your profile.

---

## 2. NFC tags

You need NTAG213 tags or better, and a writer app — [NFC
Tools](https://www.wakdev.com/en/apps/nfc-tools.html) is the usual choice.

### Which kind of tag to write

| Write this URI record | What a scan does | Carries the token? |
|---|---|---|
| `briq://escalate/<profile>` | switches to that profile, tightening only | **no** |
| `briq://select/<profile>/<TOKEN>` | that exact profile, absolute | yes |
| `briq://toggle/<profile>/<TOKEN>` | that profile, or back to `unbricked` | yes |
| `briq://unlock/<TOKEN>` | the master tag: opens the profile picker | yes |

**Prefer `escalate` for any tag you keep somewhere handy.** It carries no
credential at all — the controller refuses any escalation that is not strictly
stricter — so a tag someone reads or clones only lets them brick your phone
*harder*. A `select` or `toggle` tag has the master token on it in plain NDEF;
leaving one on your desk puts the way out of the brick on your desk too, and a
desk is not a walk.

### Writing one

1. **Add record → URL/URI** → paste the string above verbatim.
2. **Add record → Android Application Record** → `de.lukas.briq`.
   The AAR makes dispatch to the app guaranteed rather than merely likely.
3. Write to the tag.

**Do not lock a tag until you have tested it.**

### Why the private `briq://` scheme

An `http://` record is in principle any browser's URL, so Android
disambiguates and the scan costs a confirmation tap. Nothing but this app can
claim `briq://`, so those records dispatch immediately with the phone merely
unlocked. That is the whole point of the app.

The trade: `briq://` tags do nothing on a phone without Briq installed. The
printed QR sheet stays the fallback, and it still carries `http://` URLs that
work in any browser.

### http tags (no app required)

If you would rather not install the app, write plain URL records instead:

```
http://<PI_IP>:8088/toggle/<profile>/<TOKEN>
http://<PI_IP>:8088/select/unbricked/<TOKEN>
```

Use the **IP form, not a hostname** — it keeps working in `offline`, where DNS
is denied.

---

## 3. Double-scan protection

A repeat scan within 12 seconds of the last change is ignored, and a scan
arriving while a change is still applying is ignored too. NFC tags often read
twice on one tap; without this, a single tap would toggle straight back.

## 4. Expect the wait

A profile change takes **20–30 seconds**.
The controller waits and verifies the rules are actually live in the filtering
engine before reporting success, rather than returning early and lying.

Do not re-scan because it looks stuck.
