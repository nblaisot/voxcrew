# AGENTS.md — VoxCrew `relay/` package

When asked to **install, deploy, upgrade, or debug** this relay on any host
(Ubuntu, Mac Mini, macOS, Windows PC, **or optional Google Cloud Run**), follow:

→ **[`docs/relay-deploy.md`](../docs/relay-deploy.md)** (canonical, copy-paste ready)

Do **not** invent a public presence/WATCH API or FCM wake.
Do **not** persist online lists or peer IPs.
Do **not** commit `RELAY_SECRET` or `certs/`.
Cloud Run is an alternate **host** for this same package (home uplink down), not a
second product backend — keep `--max-instances=1` (in-memory peer map).

**Narrow exception to “no presence”:** session-scoped **mutual roster interest** only.
Clients may publish known crew UUIDs (`roster_interest`); the Mini notifies both sides
(`roster_match`) when A∈B.interest ∧ B∈A.interest and both are currently `hello_ok`.
Interest is RAM-only and cleared on disconnect — never a public online directory.

## Minimal agent checklist

### Home / always-on

1. Node.js ≥ 18 + openssl available.
2. `cd relay && npm install && npm run gen-cert && npm test`.
3. Set `RELAY_SECRET` (and optional `RELAY_PORT=8443`).
4. `npm start` smoke test → then install systemd / launchd / Windows service per deploy doc.
5. Router port-forward TCP 8443; share `wss://HOST:8443` + secret + cert fingerprint with phones.

### Cloud Run (optional)

1. `gcloud` authenticated + billing account.
2. `bash relay/deploy/cloudrun-deploy.sh` (see doc for env overrides).
3. Set Billing **Spend Cap** on Cloud Run for the project (CLI creates alerts only).
4. Share `wss://….run.app` + secret with phones (**omit** `certSha256`).

Protocol summary: `hello` → optional `roster_interest` / `roster_match` → `dial` by UUID → opaque binary LanFrames. See `README.md`.
