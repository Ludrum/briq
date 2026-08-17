#!/usr/bin/env python3
"""
Generate one QR image per profile plus a printable sheet.

The token is never written to stdout, to a filename, or to any log. Images
are produced locally with qrencode - nothing is uploaded anywhere.

Run as root:  sudo /opt/briq-control/gen-qr.py
"""

import base64
import html
import os
import subprocess
import sys

sys.path.insert(0, "/opt/briq-control")
import briqctl  # noqa: E402

OUT = "/opt/briq-control/qr"
ENVFILE = "/etc/briq-control/controller.env"

EFFECT = {
    "unbricked": "Lifts every block.",
    "social": "Instagram, Facebook, TikTok, X, Reddit stop working.",
    "video": "YouTube stops working. Search, Gmail, Maps unaffected.",
    "deep-focus": "Social AND YouTube stop working.",
    "offline": "Almost everything stops working. Emergency allowlist only.",
}
ORDER = ["unbricked", "social", "video", "deep-focus", "offline"]

# unbricked stays an ABSOLUTE selection: it is the escape hatch, so scanning
# it must always land on unbricked and can never toggle away into a stricter
# profile. Everything else toggles - scan once to apply, again to lift.
ABSOLUTE = {"unbricked"}


def load_env():
    """Read the root-only env file without echoing it."""
    env = {}
    with open(ENVFILE, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def main():
    if os.geteuid() != 0:
        print("run as root (the token file is 0600 root:root)", file=sys.stderr)
        return 1

    env = load_env()
    token = env.get("BRIQ_TOKEN", "")
    port = env.get("BRIQ_PORT", "8088")
    host = env.get("BRIQ_HOST_FOR_QR", "")
    if not token:
        print("BRIQ_TOKEN not found in %s" % ENVFILE, file=sys.stderr)
        return 1
    if not host:
        print("BRIQ_HOST_FOR_QR not set in %s" % ENVFILE, file=sys.stderr)
        return 1

    os.makedirs(OUT, exist_ok=True)
    os.chmod(OUT, 0o755)

    profiles = [p for p in ORDER if p in briqctl.list_profiles()]
    cards = []

    for name in profiles:
        kind = "select" if name in ABSOLUTE else "toggle"
        url = "http://%s:%s/%s/%s/%s" % (host, port, kind, name, token)
        png = os.path.join(OUT, "%s.png" % name)   # neutral filename, no token
        subprocess.run(["qrencode", "-o", png, "-s", "6", "-m", "2",
                        "-l", "M", url], check=True)
        os.chmod(png, 0o644)
        with open(png, "rb") as fh:
            b64 = base64.b64encode(fh.read()).decode()
        cards.append(
            "<div class=card>"
            "<img alt='QR for %s' src='data:image/png;base64,%s'>"
            "<div class=meta><h2>%s</h2><p>%s</p>"
            "<p class=note>%s Press the button on the page to confirm.</p></div>"
            "</div>"
            % (html.escape(name), b64, html.escape(name),
               html.escape(EFFECT.get(name, "")),
               html.escape("Always sets this profile. Your way back."
                           if name in ABSOLUTE else
                           "Scan to turn on. Scan again to lift it.")))

    sheet = """<!doctype html><html lang=en><meta charset=utf-8>
<title>Briq profile QR sheet</title>
<style>
body{font:14px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
 max-width:760px;margin:1.5rem auto;padding:0 1rem;color:#111;background:#fff}
h1{font-size:1.5rem;margin:0 0 .25rem}
.sub{color:#555;margin:0 0 1.25rem}
.card{display:flex;gap:1rem;align-items:center;border:1px solid #ccc;
 border-radius:10px;padding:.75rem;margin:.6rem 0;page-break-inside:avoid}
.card img{width:140px;height:140px;image-rendering:pixelated;flex:0 0 auto}
.meta h2{margin:0 0 .2rem;font-size:1.15rem;text-transform:capitalize}
.meta p{margin:.15rem 0;color:#333}
.note{color:#666;font-size:.85rem}
.warn{border:1px solid #b45309;background:#fff7ed;border-radius:10px;
 padding:.75rem;margin:1.25rem 0;color:#7c2d12;font-size:.9rem}
@media print{body{margin:0}.card{border-color:#999}}
</style>
<h1>Briq profile QR sheet</h1>
<p class=sub>Scan with the camera while on the home Wi-Fi. Each code opens a
confirmation page - the profile only changes after you press the button.</p>
%s
<div class=warn><b>Treat this sheet as a password.</b> Each code contains the
controller token. Anyone on your LAN who photographs it can change the
profile. Do not post it publicly or upload it anywhere. If a code leaks,
rotate the token (see docs/OPERATIONS.md) and reprint this sheet.</div>
</html>""" % "\n".join(cards)

    path = os.path.join(OUT, "sheet.html")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(sheet)
    os.chmod(path, 0o644)

    print("wrote %d QR images and sheet.html to %s" % (len(profiles), OUT))
    print("(token intentionally not displayed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
