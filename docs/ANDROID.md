# The Briq Android app

The app's whole job is to collapse an NFC scan from three steps — accept the
link, open the page, press the button — down to one: tap the tag, the profile
changes.

It is sideloaded, not on Play. There are no accounts and no onboarding.

Requires Android 10 (API 29) or newer. NFC is optional; without it you lose
the tag path but keep the app.

## 1. Configure

One file decides where the app looks for the Pi. It feeds three places at
once: the host list in `BriqApi.kt`, the NFC intent filters in the manifest,
and the cleartext allowlist in `network_security_config.xml`.

```bash
cd android
cp briq.properties.example briq.properties
nano briq.properties
```

```properties
briq.lanHost=192.168.1.2      # the Pi's reserved LAN address
briq.tailscaleHost=           # blank unless you follow TAILSCALE.md
briq.port=8088                # must match BRIQ_PORT in controller.env
```

`briq.properties` is gitignored. If it is missing the build falls back to the
committed `.example`, so a fresh clone still compiles — it just cannot reach
anything.

> Use the Pi's **IP address, not a hostname**. The `offline` profile denies
> DNS, so a hostname would stop resolving exactly when you need the way out.

## 2. Build

Android Studio: open the `android/` directory and run. Or from the command
line:

```bash
cd android
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Install it with
`adb install -r <path>`, or copy it to the phone and open it.

Release builds are signed with the debug key on purpose — this is sideloaded
to your own device, and the debug key is the only key there is. If you want a
real one, add a `signingConfig` in `app/build.gradle.kts`; `*.jks` and
`*.keystore` are already gitignored.

You need a JDK 17 and an Android SDK with API 36. Android Studio supplies
both; `local.properties` (gitignored) points at the SDK, and Android Studio
writes it for you.

## 3. Use

**Home** is one object and one sentence — it answers "how bricked am I" before
you have finished looking at it. Under the object, the Pi's own count of how
long the current profile has been active.

- **Long-press home** → stricter profiles only, no credential. This is the
  free direction.
- **Scan a tag** → the same sheet, but *every* profile including looser ones,
  because holding the tag is the permission.
- **FAB** → details, schedules, host and health.

An apply takes 20–30 seconds and survives the app being closed: it runs in a
foreground service with a notification, and finishing posts a result. There is
no progress bar, because the duration genuinely is not known.

## 4. What the app deliberately cannot do

It never persists the token. It holds one between the scan and the single
action that scan authorises, then drops it — never into a `Bundle`, never to
disk. The app on its own cannot loosen the brick. That is the point.

## Design notes

Nothing in the UI is a hand-picked hex. Each profile carries a hue and a
chroma, served by `/profiles.json`, and the app derives the background ramp,
the glow, the dot field, the type colours, the object shading and the pill
fills from those two numbers. So adding a profile on the Pi needs no app
release, and changing a hue cannot leave one surface behind.

The colour space is OKLCH, because perceived lightness is constant across
hues there — red at L=0.48 is exactly as dark as green at L=0.48, which is
what lets one set of lightness rules produce five equally legible palettes.
Compose has no `oklch()`, so `Profiles.kt` converts OKLCH → sRGB directly.

Restriction level is never carried by colour alone: the level segments differ
in **width as well as brightness**, and the schedule day chips differ in fill,
border *and* weight. One channel is never enough.

### Bundled fonts

Wix Madefor Display, KoHo and Outfit ship as TTFs in
`app/src/main/res/font/`, all under the [SIL Open Font License
1.1](../FONT-LICENSES.md). They are bundled rather than downloaded
because the app has to render correctly on a network where the Pi — and
possibly everything else — is unreachable.
