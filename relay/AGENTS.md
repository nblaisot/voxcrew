# AGENTS.md — VoxCrew `relay/` package

When asked to **install, deploy, upgrade, or debug** this relay on any host
(Ubuntu, Mac Mini, macOS, Windows PC), follow:

→ **[`docs/relay-deploy.md`](../docs/relay-deploy.md)** (canonical, copy-paste ready)

Do **not** invent a presence/WATCH API, managed cloud, or FCM wake.
Do **not** commit `RELAY_SECRET` or `certs/`.

## Minimal agent checklist

1. Node.js ≥ 18 + openssl available.
2. `cd relay && npm install && npm run gen-cert && npm test`.
3. Set `RELAY_SECRET` (and optional `RELAY_PORT=8443`).
4. `npm start` smoke test → then install systemd / launchd / Windows service per deploy doc.
5. Router port-forward TCP 8443; share `wss://HOST:8443` + secret + cert fingerprint with phones.

Protocol summary: `hello` → `dial` by UUID → opaque binary LanFrames. See `README.md`.
