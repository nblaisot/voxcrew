# VoxCrew Mac Mini relay (optional Cloud path)

Self-hosted TLS WebSocket relay. Discovery stays **LAN-only** on the phones.
Tailscale remains ephemeral `overlayHost` from a LAN beacon. This relay is a
third **dial** path keyed by **UUID** (no peer IPs stored).

## Install / deploy (start here)

**Full guide for humans and AI agents** (Ubuntu, Mac Mini, macOS, Windows):

→ **[`docs/relay-deploy.md`](../docs/relay-deploy.md)**

Agent entrypoint in this folder: [`AGENTS.md`](./AGENTS.md).

Ready-made units: [`deploy/`](./deploy/) (`env.example`, systemd, launchd).

## Path order on the phone

1. Local (LAN TCP)
2. VPN (Tailscale TCP, if `overlayHost` is on the current sighting)
3. Cloud (this relay) — dial peer UUID; bridge fails if peer is not registered

## Quick smoke test

```bash
cd relay
npm install
npm run gen-cert          # writes certs/ + prints SHA-256 fingerprint
export RELAY_SECRET='your-crew-passphrase'
export RELAY_PORT=8443
npm start
```

Then port-forward TCP `8443` and configure phones (Menu → Relay or deep link).

```
voxcrew://relay-config?url=wss%3A%2F%2FYOUR_HOST%3A8443&secret=your-crew-passphrase&certSha256=FINGERPRINT
```

## Protocol

| Message | Direction | Meaning |
|---------|-----------|---------|
| `hello` | C→S | `{uid, displayName, secret}` |
| `hello_ok` / `hello_reject` | S→C | Auth result |
| `dial` | C→S | `{peerUid}` request bridge |
| `dial_ok` / `dial_fail` | S→C | Peer registered or not |
| `peer_gone` | S→C | Peer control socket dropped |
| binary | C↔S | `[uidLen u16be][uid utf8][LanProtocol datagram frame]` |

No presence / WATCH API.

## Env

| Variable | Default | Notes |
|----------|---------|--------|
| `RELAY_SECRET` | (required) | Shared crew passphrase |
| `RELAY_PORT` / `PORT` | `8443` | Listen port |
| `RELAY_CERT` / `RELAY_KEY` | `certs/cert.pem`, `certs/key.pem` | TLS |
| `RELAY_ALLOW_INSECURE` | unset | `1` = plain `ws://` for local tests only |

## Tests

```bash
npm test
```
