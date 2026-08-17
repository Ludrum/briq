# Optional: the brick away from home

Without this, leaving the house lifts the brick — the Pi is only reachable on
your LAN. [Tailscale](https://tailscale.com/) gives the Pi a stable address
that follows you.

This works well for laptops. Phones and tablets are harder: it needs a
per-device DNS setting that iOS and Android do not expose cleanly, so treat
this as the laptop story.

> **Read the honest limit first.** Turning Tailscale off still lifts the
> brick. This is friction, not enforcement — the same class of gap as browser
> DNS-over-HTTPS. On Android, an always-on VPN with "Block connections without
> VPN" would close it; on unsupervised iOS and on Windows there is no
> equivalent.

---

## 1. Join the Pi to your tailnet

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --hostname=briq --accept-dns=false
tailscale status
```

`--accept-dns=false` is **mandatory**. Without it the Pi resolves via a
nameserver that is itself, and loops.

Note the Pi's `100.x` and `fd7a:` addresses from `tailscale status`.

## 2. Leave the admin console completely alone

Tailscale's DNS settings — global nameservers, Override local DNS, split DNS —
are **tailnet-wide with no per-device scoping**. There is no supported way to
point one device at the Pi. The per-device `--accept-dns` switch defaults to
ON, so setting a global nameserver captures every device in the tailnet,
including any added later.

If your tailnet is shared with anyone else, that also means every domain they
visit lands in your Pi's query log under their device name.

So: configure DNS **per device**, not in the console. Verify with
`tailscale dns status` on a client — it should report no resolvers configured.

## 3. Point a device at the Pi — one address per interface

Set a static DNS server on **every** adapter, and give each interface the Pi
address *that interface can actually reach*:

| Interface | DNS server |
|---|---|
| Tailscale | the Pi's `100.x` address |
| Ethernet | `<PI_IP>` |
| Wi-Fi | `<PI_IP>` |

Every entry is the same Pi, so no address in the list can resolve a blocked
domain. At home the physical adapter answers directly; away from home only the
Tailscale interface can reach anything, and it does.

### Three traps, all of them hit in practice

**Do not put the `100.x` address on a physical adapter.** Windows binds a DNS
query to the interface the server is configured on, and from Ethernet there is
no route to a `100.x` address — the query is never sent at all. At home this
hides completely, because the second entry answers over the LAN and everything
looks fine. Away from home there is no second entry and DNS fails outright.

**Set Wi-Fi too, not just Ethernet.** A laptop with static DNS on Ethernet but
DHCP on Wi-Fi takes whatever resolver a café or hotel hands out — which is
exactly the situation you added Tailscale for. On Windows, check with:

```powershell
netsh interface ipv4 show dnsservers name="Wi-Fi"
```

It must say *static*, not *from DHCP*.

**Check the digits.** A single transposed octet presents as total DNS failure
for every domain while `Resolve-DnsName -Server <pi>` keeps working perfectly,
because explicit queries bypass the DNS Client service. If system name
resolution fails but explicit queries succeed, read the address list character
by character before theorising.

### Captive portals

Hotel and café logins hijack DNS to reach their splash page, and a static
resolver they cannot intercept means no portal and no connectivity. Drop to
DHCP, log in, put it back:

```powershell
netsh interface ipv4 set dnsservers name="Wi-Fi" source=dhcp
# after logging in:
netsh interface ipv4 set dnsservers name="Wi-Fi" source=static address=<PI_TAILSCALE_IP> primary
netsh interface ipv4 add dnsservers name="Wi-Fi" address=<PI_IP> index=2
```

## 4. Register the tailnet addresses as brick clients

A device needs one identifier per path it can reach the Pi by. An
**unregistered source address is treated as an unknown client and bypasses the
brick entirely** — the same trap as registering by MAC.

Use `add-client-id`, never `add-client`: `add-client` **replaces** a client's
identifiers and would delete the LAN address. `add-client-id` merges.

```bash
sudo briqctl add-client-id my-laptop 100.x.y.z fd7a:115c:a1e0::...
sudo briqctl clients
```

The IPv6 one is not optional.

Tailscale addresses are assigned per device by the coordination server and do
not change — strictly better than a router reservation, which a device can
silently fall out of.

## 5. Firewall

Add each of *your* devices' addresses to `tailnet_mine_v4` and
`tailnet_mine_v6` in `/etc/nftables.conf` (markers 2/4 in the template), then:

```bash
sudo systemctl restart nftables
```

Also add the tailnet ranges to `BRIQ_TRUSTED_NETS` in
`/etc/briq-control/controller.env`:

```
BRIQ_TRUSTED_NETS=<LAN_SUBNET>,100.64.0.0/10,fd7a:115c:a1e0::/48,127.0.0.1/32,::1/128
```

Two conditions are required together in the firewall rules: `iifname
tailscale0` proves the packet really came through the tunnel, so a LAN host
cannot forge a `100.x` source; and set membership proves *which* device sent
it. WireGuard already drops packets whose source does not match the
authenticated peer, so within the tunnel a source match is trustworthy.

**Scope in nftables, not in a Tailscale ACL.** The ACL policy is tailnet-wide
and possibly shared; an error there affects other people's devices. The
firewall file affects only this Pi.

The controller's own `BRIQ_TRUSTED_NETS` check trusts the whole tailnet range
rather than repeating the device list — one place to edit when adding a device
instead of two. The precise list lives in nftables; the controller check is a
backstop. The residual risk if nftables were ever flushed is small, because
the unauthenticated `/escalate` path can only make the brick stricter.

## 6. Point the app at it

In `android/briq.properties`:

```properties
briq.tailscaleHost=100.x.y.z
```

Rebuild. The app then shows both hosts and falls back from LAN to Tailscale
automatically.

## 7. Verify

With `social` active and the LAN path to the Pi blocked — so the tunnel is the
only way DNS can work:

```bash
# on the Pi, simulate being away from home
sudo nft insert rule inet briq input ip saddr <LAPTOP_LAN_IP> \
    udp dport 53 drop comment "TEMP"

# ... test from the laptop, then remove it by handle:
sudo nft -a list chain inet briq input | grep TEMP
sudo nft delete rule inet briq input handle <N>
```

From the laptop, through the normal system resolver, `instagram.com` should
return `0.0.0.0` while `example.org` resolves normally.

## What would actually fix the gap

Making the Pi a Tailscale **exit node**. nftables could then drop traffic by
IP, which would also kill the long-lived connections that DNS blocking
structurally cannot touch. It needs the `forward` chain opened — deliberately
closed in the template — and it routes all mobile traffic through your home
uplink.
