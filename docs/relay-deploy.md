# Deploy the VoxCrew relay (AI / operator guide)

**Audience:** humans and coding agents (Cursor, etc.) installing this service on
any always-on host: Ubuntu/Debian, macOS (Mac Mini or Mac), or a Windows PC.

**What this is:** optional self-hosted TLS WebSocket audio relay under `relay/`.
Phones keep LAN discovery; Tailscale stays session-scoped; this relay is a third
**Cloud** dial path keyed by peer **UUID** only (no peer LAN/Tailscale IPs).

**Non-goals:** no FCM/wake, no presence API, no managed cloud (Firebase/Cloud Run).

---

## Agent quick start (copy-paste)

Run from the **repository root**. Prefer a dedicated machine that stays powered.

```bash
# 1) Prerequisites: Node.js 18+ and openssl on PATH
node -v && openssl version

# 2) Install deps
cd relay
npm install

# 3) TLS material (self-signed is OK for MVP)
npm run gen-cert
# Note the printed SHA-256 fingerprint (hex, no colons).

# 4) Configure secret (never commit this)
export RELAY_SECRET='replace-with-a-long-crew-passphrase'
export RELAY_PORT=8443

# 5) Start (foreground smoke test)
npm start
# Expect: voxcrew-relay listening on wss port 8443
```

Then:

1. Open TCP `8443` on the host firewall.
2. Port-forward WAN TCP `8443` → this host’s LAN IP `8443` on the router (Freebox, etc.).
3. Give phones a public `wss://HOST:8443` URL + the same `RELAY_SECRET` + cert fingerprint
   (app **Menu → Relay**, or `voxcrew://relay-config?...` deep link).

Stop with `Ctrl+C`. For production, install a systemd / launchd / Task Scheduler unit
(see platform sections below) so the process restarts on reboot.

---

## Success criteria

| Check | Passes when |
|-------|-------------|
| Process up | Log line `voxcrew-relay listening on wss port …` |
| Local TLS | `openssl s_client -connect 127.0.0.1:8443 -servername localhost </dev/null` shows the cert |
| Unit tests | `cd relay && npm test` — all green |
| Phone A | Relay settings show “Relay connected” |
| Phone B | Same URL/secret; when both apps run, Cloud path can connect (icon = Cloud) |
| Dial fail | If peer app is killed → dial fails / backoff (expected; no online list) |

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|--------|
| Node.js | **≥ 18** (20+ preferred) | `node` and `npm` on PATH |
| OpenSSL | any modern | `npm run gen-cert` |
| Git | optional | clone or copy the `relay/` folder |
| Public reachability | required for remote Cloud | stable hostname or public IP + port forward |

**Hardware:** a 2012+ Mac Mini, any always-on Mac/PC, or a cheap Ubuntu VPS/NUC is enough.
Opus audio is ~24 kbps/stream; a home fiber uplink is fine for a small crew.

**Do not** put `RELAY_SECRET`, private keys, or `relay/certs/` in Git.

---

## Repository layout

```
relay/
  package.json          # npm start / test / gen-cert
  src/server.mjs        # TLS WSS server
  scripts/gen-self-signed.sh
  certs/                # generated locally (gitignored)
  deploy/
    env.example
    voxcrew-relay.service      # systemd (Linux)
    com.nblaisot.voxcrew.relay.plist  # launchd (macOS)
  README.md             # protocol + short setup
  AGENTS.md             # pointer for coding agents
```

---

## Configuration (environment)

| Variable | Required | Default | Meaning |
|----------|----------|---------|---------|
| `RELAY_SECRET` | **yes** | — | Shared crew passphrase (auth on `hello`) |
| `RELAY_PORT` or `PORT` | no | `8443` | Listen port |
| `RELAY_CERT` | no | `relay/certs/cert.pem` | TLS certificate PEM |
| `RELAY_KEY` | no | `relay/certs/key.pem` | TLS private key PEM |
| `RELAY_ALLOW_INSECURE` | no | unset | `1` = plain `ws://` (**lab only**, never expose publicly) |

Generate a strong secret, e.g.:

```bash
openssl rand -base64 32
```

Fingerprint for the Android deep link / settings (hex, lowercase, no colons):

```bash
openssl x509 -in relay/certs/cert.pem -noout -fingerprint -sha256 \
  | sed 's/^.*=//' | tr -d ':' | tr 'A-F' 'a-f'
```

### Deep link template

URL-encode the `wss://` URL. Example:

```
voxcrew://relay-config?url=wss%3A%2F%2FYOUR_HOST%3A8443&secret=YOUR_SECRET&certSha256=YOUR_FINGERPRINT
```

---

## Network / router

1. Prefer a **DHCP reservation** for the relay host’s LAN IP.
2. Router port forward: **TCP external 8443 → host:8443** (Freebox OS → Port forwarding, etc.).
3. Host firewall: allow inbound TCP 8443.
4. Public URL forms:
   - Freebox / DynDNS hostname: `wss://something.freeboxos.fr:8443`
   - Public IP: `wss://203.0.113.10:8443` (updates if IP changes)
   - Reverse proxy (optional): terminate TLS at Caddy/nginx and proxy to the Node port
     (then use a Let’s Encrypt cert and you can omit `certSha256` TOFU — still set secret).

**CGNAT / no public port:** Cloud path will not work from the internet without a tunnel
(Tailscale Funnel, Cloudflare Tunnel, etc.). Prefer a real port forward when possible.

---

## Platform: Ubuntu / Debian

### Install Node (example: Node 20 via NodeSource)

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl openssl
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs
node -v   # v20.x
```

Or use `nvm` / distro packages if they provide Node ≥ 18.

### Install relay

```bash
# Example install path — adjust to where you cloned the repo
sudo mkdir -p /opt/voxcrew
sudo chown "$USER":"$USER" /opt/voxcrew
cd /opt/voxcrew
# If full repo: git clone <url> .   OR copy only the relay/ tree:
#   rsync -a /path/to/voxcrew/relay/ /opt/voxcrew/relay/
cd /opt/voxcrew/relay   # or: cd /opt/voxcrew && cd relay
npm install --omit=dev
npm run gen-cert
```

### Env file

```bash
sudo mkdir -p /etc/voxcrew
sudo cp deploy/env.example /etc/voxcrew/relay.env
sudo chmod 600 /etc/voxcrew/relay.env
sudo nano /etc/voxcrew/relay.env   # set RELAY_SECRET; fix RELAY_CERT/KEY paths if needed
```

### systemd unit

```bash
# Edit User=, WorkingDirectory=, and EnvironmentFile= paths in the unit if needed
sudo cp deploy/voxcrew-relay.service /etc/systemd/system/voxcrew-relay.service
sudo systemctl daemon-reload
sudo systemctl enable --now voxcrew-relay
sudo systemctl status voxcrew-relay --no-pager
journalctl -u voxcrew-relay -f
```

### Firewall (ufw)

```bash
sudo ufw allow 8443/tcp
sudo ufw reload
```

---

## Platform: macOS (Mac Mini or Mac)

### Install Node

```bash
# Homebrew
brew install node openssl@3
node -v
```

Or install the official pkg from https://nodejs.org (LTS).

### Install relay

```bash
mkdir -p ~/Services/voxcrew-relay
# Copy or clone so that package.json is at ~/Services/voxcrew-relay/package.json
cd ~/Services/voxcrew-relay
npm install --omit=dev
npm run gen-cert
```

### Env + launchd

```bash
mkdir -p ~/Library/Application\ Support/voxcrew
cp deploy/env.example ~/Library/Application\ Support/voxcrew/relay.env
chmod 600 ~/Library/Application\ Support/voxcrew/relay.env
# Edit RELAY_SECRET and absolute RELAY_CERT / RELAY_KEY paths

# Customize the plist: replace REPLACE_HOME and ensure node path is correct
NODE_BIN="$(command -v node)"
sed -e "s|REPLACE_HOME|$HOME|g" -e "s|REPLACE_NODE|$NODE_BIN|g" \
  deploy/com.nblaisot.voxcrew.relay.plist \
  > ~/Library/LaunchAgents/com.nblaisot.voxcrew.relay.plist

launchctl unload ~/Library/LaunchAgents/com.nblaisot.voxcrew.relay.plist 2>/dev/null || true
launchctl load ~/Library/LaunchAgents/com.nblaisot.voxcrew.relay.plist
launchctl start com.nblaisot.voxcrew.relay
# Logs: ~/Library/Logs/voxcrew-relay.out.log / .err.log
```

**Power:** System Settings → Energy → prevent automatic sleeping when display is off
(or equivalent on older macOS) so the Mini stays reachable.

**Firewall:** System Settings → Network → Firewall → allow incoming for `node` if enabled.

---

## Platform: Windows PC

### Install Node

1. Install **Node.js LTS** from https://nodejs.org (includes npm).
2. Install **Git for Windows** if you need OpenSSL via Git Bash, **or** install Win64 OpenSSL.
3. Open **PowerShell** or **Git Bash**.

### Install + run (Git Bash recommended for `gen-cert`)

```bash
cd /c/Services/voxcrew-relay   # adjust
npm install --omit=dev
npm run gen-cert
export RELAY_SECRET='your-crew-passphrase'
export RELAY_PORT=8443
npm start
```

PowerShell equivalent (after certs exist):

```powershell
$env:RELAY_SECRET = "your-crew-passphrase"
$env:RELAY_PORT = "8443"
Set-Location C:\Services\voxcrew-relay
npm start
```

### Keep it running

Options:

- **Task Scheduler**: trigger “At startup” → action `node src\server.mjs` with working
  directory `C:\Services\voxcrew-relay` and environment variables set in the task.
- **nssm** (Non-Sucking Service Manager): wrap `node.exe` as a Windows service.
- Leave a logged-in session with `npm start` only for lab use.

### Firewall

```powershell
New-NetFirewallRule -DisplayName "VoxCrew relay" -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow
```

Also port-forward on the Windows PC’s router.

---

## Verify from another machine

```bash
# Replace HOST
nc -vz HOST 8443 || true
openssl s_client -connect HOST:8443 -servername HOST </dev/null 2>/dev/null | openssl x509 -noout -fingerprint -sha256
```

Phones on cellular (not the same Wi‑Fi as the relay) are the real Cloud test.

---

## Upgrade

```bash
cd /path/to/relay
git pull   # if full repo
npm install --omit=dev
# restart: systemctl restart voxcrew-relay   OR   launchctl kickstart -k gui/$(id -u)/com.nblaisot.voxcrew.relay
```

Certs and `/etc/voxcrew/relay.env` (or macOS Application Support env) are **not** overwritten
by `git pull` if they live outside the repo or under gitignored `certs/`.

---

## Security checklist

- [ ] `RELAY_SECRET` is long, unique, and shared only with the crew
- [ ] `RELAY_ALLOW_INSECURE` is **unset** on any internet-facing host
- [ ] Private key `certs/key.pem` mode `600`, not world-readable
- [ ] Router forward limited to TCP 8443 (or your chosen port)
- [ ] Secret never logged; never committed

See also `docs/security.md` and `docs/architecture.md` (Cloud path notes).

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `RELAY_SECRET is required` | env not set | export / fix env file / systemd `EnvironmentFile` |
| `Missing TLS certs` | no `npm run gen-cert` | generate certs; check `RELAY_CERT`/`RELAY_KEY` |
| Phone “Relay not connected” | wrong URL, blocked port, bad secret, cert pin mismatch | verify port forward; re-share fingerprint; check journal/logs |
| `dial_fail` / no Cloud icon | peer app not running or not hello_ok on Mini | both apps must be open and relay-authenticated |
| Works on Wi‑Fi, not on LTE | WAN port forward / CGNAT | confirm public reachability of `HOST:8443` |
| EADDRINUSE | port taken | change `RELAY_PORT` and forward that port instead |

---

## Protocol reminder (do not invent APIs)

| Message | Role |
|---------|------|
| `hello` | Auth + bind socket to `uid` |
| `dial` | Bridge by peer UUID |
| binary | Opaque LanProtocol frames |
| `peer_gone` | Peer control socket dropped |

No `WATCH` / `PRESENCE` / online list. Discovery on phones remains **LAN-only**.

---

## Related files

- `relay/README.md` — short protocol + setup
- `relay/AGENTS.md` — agent entrypoint for this package
- `docs/architecture.md` — Local / VPN / Cloud path model
- `AGENTS.md` — product stack rules (repo root)
