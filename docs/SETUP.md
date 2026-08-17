# Setup

Start to finish this takes about an hour, most of it waiting for AdGuard Home
to install and for the first profile to apply.

Work through the sections in order. Sections 1–6 are on the Pi and the router;
after §6 the brick works from a browser. The app, the QR sheet and the NFC
tags come after that, in [ANDROID.md](ANDROID.md) and [TAGS.md](TAGS.md).

**Placeholders used below.** Replace them with your own values everywhere:

| Placeholder | Meaning | Example |
|---|---|---|
| `<PI_IP>` | the Pi's reserved LAN address | `192.168.1.2` |
| `<LAN_SUBNET>` | your LAN in CIDR form | `192.168.1.0/24` |
| `<ROUTER_IP>` | your router's LAN address | `192.168.1.1` |
| `<ROUTER_LOCAL_NAME>` | the hostname your router answers to on the LAN | `fritz.box`, `routerlogin.net` |

---

## 0. What you need

- A Raspberry Pi (or similar 24/7 on device) on your home LAN, running a Debian-based OS (Raspberry Pi OS
  is fine) with Python 3.9 or newer. It does not need to be fast: this was
  built and run on a 2012 Pi 1 Model B.
- Admin access to your router. **Several steps here cannot be done from the
  Pi.**.
- Optional: NFC tag for smooth, brick-like experience (QR codes are offered as alternative).

> **Read this before continuing.** Once §5 is done, if the Pi dies, nobody in
> the house has working internet until you set the router's DNS field back.
> Write that rollback step on paper. On a Pi booting from an SD card, this is
> not a theoretical failure mode.

---

## 1. Prepare the Pi

Give the Pi a fixed address **in your router**, not on the Pi itself — a DHCP
reservation for its MAC address. Everything downstream points at `<PI_IP>`,
and a moved lease breaks all of it at once.

Find the MAC with:

```bash
ip link show eth0
```

Then, on a low-memory Pi, free some RAM by dropping the desktop (optional, and
reversible — see [OPERATIONS.md](OPERATIONS.md)):

```bash
sudo systemctl set-default multi-user.target
sudo systemctl disable --now lightdm
```

Check that port 53 is free before installing a DNS server:

```bash
sudo ss -lunp | grep ':53 ' ; sudo ss -ltnp | grep ':53 '
```

Anything listening there — `dnsmasq`, `systemd-resolved`, an existing Pi-hole
— must be dealt with first.

---

## 2. Install AdGuard Home

Follow the official installer:

```bash
curl -s -S -L https://raw.githubusercontent.com/AdguardTeam/AdGuardHome/master/scripts/install.sh | sh -s -- -v
```

Then open `http://<PI_IP>:3000` and complete the setup wizard:

- **Admin interface**: port `3000`, all interfaces.
- **DNS server**: port `53`, all interfaces.
- Set an admin username and a strong password. You will need both in §4.

Afterwards, in the AdGuard UI:

1. **Filters → DNS blocklists** — **optional, and nothing to do with Briq.**
   AdGuard Home can also block ads for your whole household. If you want
   that, enable *AdGuard DNS filter* and add *AdAway Default Blocklist* for
   mobile ad domains. If you do not, skip this step entirely — Briq works
   exactly the same either way.

   Briq never reads, writes or toggles these lists. They are AdGuard's own
   feature, they apply to every device using the Pi for DNS, and they are
   unaffected by which profile is active.
2. **Settings → DNS settings → Upstream DNS servers** — set an encrypted
   upstream and, critically, forward your router's local zone back to the
   router:

   ```
   https://dns10.quad9.net/dns-query
   [/<ROUTER_LOCAL_NAME>/]<ROUTER_IP>
   ```

   `<ROUTER_LOCAL_NAME>` is whatever hostname your router answers to on the
   LAN, and your DHCP search domain if you have one. List several inside the
   one bracket, slash-separated.

   > **Do not skip this.** Once the Pi is the household resolver, your
   > router's local name gets resolved on the public internet instead. Several
   > of the common ones are registered public domains owned by someone else,
   > so anyone typing it would reach a stranger's server — and any router
   > password typed there would leave your network. This also repairs the
   > `offline` escape hatch, which allowlists the router by name.

   Check it afterwards: the name must come back as `<ROUTER_IP>`, not a
   public address.

3. **Settings → DNS settings → Private reverse DNS servers** — set
   `<ROUTER_IP>`. This makes the query log show real device names instead of
   bare addresses, which is what lets you identify your devices in §6.

4. **Do NOT enable AdGuard's DHCP server.** The controller's client scoping
   depends on the router keeping DHCP.

Verify:

```bash
dig <ROUTER_LOCAL_NAME> @<PI_IP>   # should return <ROUTER_IP>
dig example.com @<PI_IP>      # should resolve normally
```

---

## 3. Install the controller

```bash
git clone https://github.com/Ludrum/briq.git
cd briq

# a dedicated unprivileged service account
sudo useradd --system --no-create-home --shell /usr/sbin/nologin briq

# the code, root-owned so the service cannot rewrite its own scripts
sudo install -d -m 755 /opt/briq-control
sudo cp -r pi/controller.py pi/briqctl.py pi/scheduler.py pi/check_since.py \
          pi/gen-qr.py pi/profiles /opt/briq-control/
sudo chmod 755 /opt/briq-control/*.py
sudo chown -R root:root /opt/briq-control

# writable state and logs, owned by the service account
sudo install -d -m 750 -o briq -g briq /var/lib/briq-control
sudo install -d -m 750 -o briq -g briq /var/log/briq-control

# the CLI wrapper
sudo install -m 755 pi/briqctl /usr/local/bin/briqctl

# log rotation
sudo install -m 644 pi/logrotate.briq-control /etc/logrotate.d/briq-control
```

The scope file is deliberately root-owned, so the service account cannot widen
the set of devices it applies to:

```bash
sudo install -m 644 -o root -g root pi/clients.conf.example /opt/briq-control/clients.conf
```

---

## 4. Configure the secrets

```bash
sudo install -d -m 700 /etc/briq-control
sudo install -m 600 pi/controller.env.example /etc/briq-control/controller.env
sudo nano /etc/briq-control/controller.env
```

Fill in every value. Generate the master token with:

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
```

Set `AGH_USER` / `AGH_PASS` to the AdGuard admin credentials from §2, and
`BRIQ_TRUSTED_NETS` to `<LAN_SUBNET>,127.0.0.1/32,::1/128`. Set
`BRIQ_HOST_FOR_QR` to `<PI_IP>:8088`.

> `BRIQ_HOST_FOR_QR` must be an **IP address, not a hostname.** The `offline`
> profile denies DNS, so a hostname would stop resolving exactly when you need
> the way out of `offline` to work.

The file is `0600 root:root` on purpose. systemd reads it as root and passes
the values into the service's environment, so the `briq` user never gets to
read the file itself.

Now start the service:

```bash
sudo install -m 644 pi/briq-controller.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now briq-controller
systemctl status briq-controller
```

`http://<PI_IP>:8088/status` should now load.

---

## 5. Router changes

**These cannot be done from the Pi.** Every router words them differently, so
what follows is what to change, not where to click. Look under LAN, DHCP or
Network settings; the manual is the authority for your model.

### a) Give the Pi a fixed IPv4 address — required

Bind the Pi's MAC address to `<PI_IP>` so it always gets the same lease.
Routers call this a DHCP reservation, a static lease, or address binding.

Everything downstream points at `<PI_IP>` — the app, the QR codes, the NFC
tags, the firewall — and a moved lease breaks all of it at once.

Setting a static address on the Pi itself works too, but reserving it in the
router is safer: the router then knows not to hand that address to anything
else.

### b) Point the household at the Pi for DNS

Set the DNS server the router advertises to its DHCP clients to `<PI_IP>`.

Until you do this, only devices you configure by hand use the Pi.

Two things not to touch:

- **Do not change the router's own internet/WAN DNS.** That is where the Pi
  itself, and the router, get their upstream answers.
- **Do not enable DHCP in AdGuard Home.** The router must keep DHCP, or the
  client scoping in §6 stops working.

Some routers will not let you advertise a DNS server other than themselves.
If yours refuses, set `<PI_IP>` as the DNS server on each device by hand
instead — the bricked devices are the ones that matter.

### c) IPv6 — do this or clients will bypass the brick

Most routers advertise **themselves** as the IPv6 DNS server. Any device that
prefers IPv6 then resolves through the router, never reaches the Pi, and so
misses the brick entirely.

Worse, devices use *temporary* IPv6 addresses that rotate roughly daily, and
iOS and Android give you no way to turn that off. You cannot register a moving
address, so a bricked device would slip out of scope within a day — silently,
without anyone trying to.

**The simple fix: do not advertise any IPv6 DNS server at all.** Clients then
fall back to IPv4 DNS, which is the Pi, and every query arrives from the
stable reserved address.

This costs you nothing. DNS *transport* and DNS *records* are independent:
AAAA answers returned over IPv4 are byte-identical to those returned over
IPv6. Your devices still reach IPv6 sites over IPv6; they simply ask over
IPv4.

If your router insists on advertising itself, the alternative is to give the
Pi a static ULA address (`fd00::53`, say), tell the router to always assign
ULA addresses, and then set that as each device's IPv6 DNS server by hand.

> **Never use the Pi's global IPv6 address.** ISP prefixes rotate, so it would
> break within days.

---

## 6. Register your devices

**Nothing is bricked until you do this.** Until `clients.conf` lists a client,
every profile except `unbricked` is refused on purpose — an unscoped rule
would hit the entire house, so the tool fails closed rather than guess.

1. Give each device you want bricked a **DHCP reservation** in the router,
   exactly as in §5a. An unreserved address could later be handed to a guest's
   phone, bricking a device that is not yours.
2. Find each device's address: put it on the Wi-Fi and watch
   `http://<PI_IP>:3000` → Query log. Reverse lookups from §2.3 make devices
   appear by name.
3. Register each one:

```bash
sudo briqctl add-client my-phone   <ADDRESS>
sudo briqctl add-client my-tablet  <ADDRESS>
sudo briqctl clients
```

> **MAC addresses do not work here.** AdGuard Home can only match a client by
> MAC when *it* is the network's DHCP server, which it must not be. A MAC-only
> client silently matches nothing, so the brick would look configured while
> doing nothing at all. This was measured, not assumed.

Only addresses explicitly listed on the AdGuard client match. A query from any
other address is treated as an unknown client and passes straight through.

---

## 7. Firewall (optional)

The controller's `/escalate` path is deliberately unauthenticated, so it is
worth limiting who can reach it at all.

```bash
sudo apt install nftables
cp pi/nftables.conf.example /tmp/nftables.conf
nano /tmp/nftables.conf        # four "CHANGE ME" markers
sudo install -m 755 /tmp/nftables.conf /etc/nftables.conf
sudo systemctl enable --now nftables
sudo nft list ruleset | head -40
```

Check `sudo nft list ruleset` **before** installing this if you already have a
firewall — the template assumes there is nothing to preserve.

The template opens SSH, DNS, 3000 and 8088 to your LAN only, redirects port 80
to the controller as a convenience, and keeps forwarding closed. It flushes
only its own `inet briq` table, never the whole ruleset — a global
`flush ruleset` would delete tailscaled's chains on every restart.

---

## 7b. Reach it as `briq.home` (optional)

`http://<PI_IP>:8088` works everywhere and always. This gives you a name
instead, which is nicer to type and to put on a bookmark.

It takes two pieces, and you need both:

1. **The name → the Pi.** In AdGuard Home, **Filters → DNS rewrites → Add**:

   | Domain | Answer |
   |---|---|
   | `briq.home` | `<PI_IP>` |

   Any name works as long as it is not a real public domain. `.home` is safe;
   avoid `.dev` and `.app`, which are real TLDs that browsers force to HTTPS.

2. **Port 80 → port 8088.** This is what lets you leave the `:8088` off, and
   it is already in the nftables template above. Without it the name still
   works, but only as `http://briq.home:8088`.

Check it:

```bash
dig +short briq.home @<PI_IP>                        # -> <PI_IP>
curl -s -o /dev/null -w '%{http_code}\n' http://briq.home/profile   # -> 200
```

> **Type `http://` in front of it.** A single word with no dot and no scheme
> is treated as a search term by most browsers, so `briq.home` alone may drop
> you into your search engine instead. `http://briq.home` is unambiguous.
> Bookmark it that way and the problem disappears.

This is convenience only. The QR codes and NFC tags deliberately use the raw
IP, so the way out of `offline` never depends on DNS resolving.

If you use a name other than `briq.home`, change the `@allow briq.home` line
in `pi/profiles/offline.list` to match — otherwise the name stops resolving in
the `offline` profile, which is exactly when you want it.

---

## 8. Check it works

```bash
sudo briqctl status
sudo briqctl list
sudo briqctl apply social      # takes 20-30s
```

From a **registered** device:

```bash
nslookup instagram.com <PI_IP>     # 0.0.0.0
nslookup example.com   <PI_IP>     # resolves normally
```

From an **unregistered** device, both should resolve normally — that is the
household staying unaffected.

Then lift it:

```bash
sudo briqctl apply unbricked
```

The web page at `http://<PI_IP>:8088` can only make the brick *stricter*, and
needs no credential to do so. Lifting always needs the token, which is what
[TAGS.md](TAGS.md) is about.

**Next:** [ANDROID.md](ANDROID.md) → [TAGS.md](TAGS.md) →
[OPERATIONS.md](OPERATIONS.md).
