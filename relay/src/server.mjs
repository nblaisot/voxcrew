/**
 * Minimal TLS WebSocket relay for VoxCrew.
 *
 * Protocol: hello / dial / overlay_announce / opaque LanFrame binary — no presence/WATCH.
 * Identity on the Mini is the peer UUID. Peer Tailscale IPs are never written to disk;
 * optional overlayHost/tcpPort may exist only in RAM for the live WebSocket session so
 * clients can upgrade Cloud → direct VPN.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.RELAY_PORT || process.env.PORT || 8443);
const SECRET = process.env.RELAY_SECRET || '';
const CERT_PATH = process.env.RELAY_CERT || new URL('../certs/cert.pem', import.meta.url).pathname;
const KEY_PATH = process.env.RELAY_KEY || new URL('../certs/key.pem', import.meta.url).pathname;
const ALLOW_INSECURE = process.env.RELAY_ALLOW_INSECURE === '1';

/**
 * @typedef {{
 *   uid: string,
 *   displayName: string,
 *   ws: import('ws').WebSocket,
 *   bridges: Set<string>,
 *   overlayHost?: string,
 *   tcpPort?: number,
 * }} Client
 */

/** @type {Map<string, Client>} */
const clients = new Map();

export function timingSafeEqualString(a, b) {
  const ba = Buffer.from(String(a), 'utf8');
  const bb = Buffer.from(String(b), 'utf8');
  if (ba.length !== bb.length) {
    // Still compare to avoid trivial timing leak on length.
    crypto.timingSafeEqual(ba, ba);
    return false;
  }
  return crypto.timingSafeEqual(ba, bb);
}

export function encodeBinaryEnvelope(peerUid, frameBytes) {
  const uidBuf = Buffer.from(peerUid, 'utf8');
  if (uidBuf.length > 0xffff) throw new Error('peerUid too long');
  const out = Buffer.allocUnsafe(2 + uidBuf.length + frameBytes.length);
  out.writeUInt16BE(uidBuf.length, 0);
  uidBuf.copy(out, 2);
  Buffer.from(frameBytes).copy(out, 2 + uidBuf.length);
  return out;
}

export function decodeBinaryEnvelope(buf) {
  if (!Buffer.isBuffer(buf)) buf = Buffer.from(buf);
  if (buf.length < 2) return null;
  const uidLen = buf.readUInt16BE(0);
  if (buf.length < 2 + uidLen) return null;
  const peerUid = buf.subarray(2, 2 + uidLen).toString('utf8');
  const frame = buf.subarray(2 + uidLen);
  return { peerUid, frame };
}

/**
 * HTTPS landing page for WhatsApp / Chrome. Custom schemes (voxcrew://) are not
 * auto-linked there; https://HOST/invite?... is. The page then opens the app.
 */
export function buildInviteHtml({ deepLink, wssUrl, intentLink }) {
  const safeDeep = escapeHtml(deepLink);
  const safeIntent = escapeHtml(intentLink || deepLink);
  const safeWss = escapeHtml(wssUrl || '');
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>VoxCrew relay</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 28rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.4; }
    a.btn { display: block; text-align: center; background: #e85d04; color: #fff; text-decoration: none;
            padding: 0.9rem 1rem; border-radius: 0.5rem; font-weight: 600; margin: 1.25rem 0; }
    code { word-break: break-all; font-size: 0.8rem; }
    .muted { color: #555; font-size: 0.9rem; }
  </style>
</head>
<body>
  <h1>VoxCrew crew relay</h1>
  <p>Tap below to open VoxCrew and apply this relay (install the app first).</p>
  <a class="btn" id="open" href="${safeIntent}">Open in VoxCrew</a>
  <p class="muted">If nothing happens: open VoxCrew → Menu → Relay → paste this link:</p>
  <p><code id="deep">${safeDeep}</code></p>
  ${safeWss ? `<p class="muted">Relay: <code>${safeWss}</code></p>` : ''}
  <script>
    (function () {
      var deep = ${JSON.stringify(deepLink)};
      var intent = ${JSON.stringify(intentLink || deepLink)};
      var a = document.getElementById('open');
      a.addEventListener('click', function (e) {
        // Prefer Android intent:// (Chrome); fall back to custom scheme.
        a.href = intent;
        setTimeout(function () { window.location.href = deep; }, 800);
      });
    })();
  </script>
</body>
</html>`;
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

export function handleHttpRequest(req, res) {
  const host = req.headers.host || 'localhost';
  let parsed;
  try {
    parsed = new URL(req.url || '/', `https://${host}`);
  } catch {
    res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('bad request\n');
    return;
  }

  const path = parsed.pathname.replace(/\/+$/, '') || '/';
  if (req.method === 'GET' && (path === '/invite' || path === '/relay-config')) {
    const wssUrl = parsed.searchParams.get('url') || '';
    const secret = parsed.searchParams.get('secret') || '';
    const certSha256 = parsed.searchParams.get('certSha256') || '';
    if (!wssUrl || !secret) {
      res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('Missing url or secret query params.\n');
      return;
    }
    const deep = new URL('voxcrew://relay-config');
    deep.searchParams.set('url', wssUrl);
    deep.searchParams.set('secret', secret);
    if (certSha256) deep.searchParams.set('certSha256', certSha256);
    const deepLink = deep.toString();
    // Chrome prefers intent:// with an explicit package over raw custom schemes.
    const q = deepLink.substring(deepLink.indexOf('?') + 1);
    const intentLink =
      `intent://relay-config?${q}#Intent;scheme=voxcrew;package=com.nblaisot.voxcrew;end`;
    const html = buildInviteHtml({ deepLink, intentLink, wssUrl });
    res.writeHead(200, {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'no-store',
    });
    res.end(html);
    return;
  }

  if (req.method === 'GET' && (path === '/' || path === '/health')) {
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('voxcrew-relay\n');
    return;
  }

  res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
  res.end('not found\n');
}

function sendJson(ws, obj) {
  if (ws.readyState === 1) ws.send(JSON.stringify(obj));
}

/** @param {unknown} msg */
export function parseOverlayEndpoint(msg) {
  if (!msg || typeof msg !== 'object') return null;
  const host = String(msg.overlayHost || '').trim();
  const port = Number(msg.tcpPort);
  if (!host || !Number.isInteger(port) || port < 1 || port > 65535) return null;
  return { overlayHost: host, tcpPort: port };
}

function dialOkPayload(peerUid, peer) {
  /** @type {Record<string, unknown>} */
  const payload = { type: 'dial_ok', peerUid };
  if (peer?.overlayHost && peer?.tcpPort) {
    payload.peerOverlayHost = peer.overlayHost;
    payload.peerTcpPort = peer.tcpPort;
  }
  return payload;
}

function applyOverlay(client, endpoint) {
  if (!endpoint) return false;
  const changed =
    client.overlayHost !== endpoint.overlayHost || client.tcpPort !== endpoint.tcpPort;
  client.overlayHost = endpoint.overlayHost;
  client.tcpPort = endpoint.tcpPort;
  return changed;
}

function notifyBridgedPeersOfOverlay(client) {
  if (!client.overlayHost || !client.tcpPort) return;
  for (const peerUid of client.bridges) {
    const peer = clients.get(peerUid);
    if (!peer) continue;
    sendJson(peer.ws, {
      type: 'peer_overlay',
      peerUid: client.uid,
      overlayHost: client.overlayHost,
      tcpPort: client.tcpPort,
    });
  }
}

function unregister(client) {
  if (!client?.uid) return;
  if (clients.get(client.uid) === client) {
    clients.delete(client.uid);
  }
  for (const peerUid of [...client.bridges]) {
    const peer = clients.get(peerUid);
    if (peer) {
      peer.bridges.delete(client.uid);
      sendJson(peer.ws, { type: 'peer_gone', peerUid: client.uid });
    }
  }
  client.bridges.clear();
}

/**
 * @param {import('ws').WebSocket} ws
 * @param {string} expectedSecret
 */
export function attachClient(ws, expectedSecret) {
  /** @type {Client | null} */
  let client = null;

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      if (!client) return;
      const env = decodeBinaryEnvelope(data);
      if (!env) return;
      const peer = clients.get(env.peerUid);
      if (!peer || !client.bridges.has(env.peerUid)) return;
      // Forward as if from this uid toward the peer.
      peer.ws.send(encodeBinaryEnvelope(client.uid, env.frame), { binary: true });
      return;
    }

    let msg;
    try {
      msg = JSON.parse(String(data));
    } catch {
      return;
    }
    if (!msg || typeof msg !== 'object') return;

    if (msg.type === 'hello') {
      const uid = String(msg.uid || '').trim();
      const displayName = String(msg.displayName || uid).trim() || uid;
      const secret = String(msg.secret ?? '');
      if (!uid || !expectedSecret || !timingSafeEqualString(secret, expectedSecret)) {
        sendJson(ws, { type: 'hello_reject', reason: 'unauthorized' });
        ws.close();
        return;
      }
      const previous = clients.get(uid);
      if (previous && previous.ws !== ws) {
        try {
          previous.ws.close(1000, 'replaced');
        } catch {
          /* ignore */
        }
        unregister(previous);
      }
      client = { uid, displayName, ws, bridges: new Set() };
      applyOverlay(client, parseOverlayEndpoint(msg));
      clients.set(uid, client);
      sendJson(ws, { type: 'hello_ok', uid });
      return;
    }

    if (!client) {
      sendJson(ws, { type: 'hello_reject', reason: 'hello_required' });
      return;
    }

    if (msg.type === 'overlay_announce') {
      if (applyOverlay(client, parseOverlayEndpoint(msg))) {
        notifyBridgedPeersOfOverlay(client);
      }
      return;
    }

    if (msg.type === 'dial') {
      const peerUid = String(msg.peerUid || '').trim();
      if (!peerUid || peerUid === client.uid) {
        sendJson(ws, { type: 'dial_fail', peerUid, reason: 'invalid_peer' });
        return;
      }
      const peer = clients.get(peerUid);
      if (!peer) {
        sendJson(ws, { type: 'dial_fail', peerUid, reason: 'peer_absent' });
        return;
      }
      client.bridges.add(peerUid);
      peer.bridges.add(client.uid);
      sendJson(ws, dialOkPayload(peerUid, peer));
      // Peer learns we want a bridge so both sides can exchange Hello.
      sendJson(peer.ws, dialOkPayload(client.uid, client));
      return;
    }
  });

  ws.on('close', () => unregister(client));
  ws.on('error', () => unregister(client));
}

export function createRelayServer({
  secret,
  port = PORT,
  certPath = CERT_PATH,
  keyPath = KEY_PATH,
  allowInsecure = ALLOW_INSECURE,
} = {}) {
  if (!secret) {
    throw new Error('RELAY_SECRET is required');
  }

  let server;
  if (allowInsecure) {
    server = http.createServer(handleHttpRequest);
  } else {
    const cert = fs.readFileSync(certPath);
    const key = fs.readFileSync(keyPath);
    server = https.createServer({ cert, key }, handleHttpRequest);
  }

  const wss = new WebSocketServer({ server });
  wss.on('connection', (ws) => attachClient(ws, secret));

  return {
    server,
    wss,
    clients,
    listen: () =>
      new Promise((resolve) => {
        server.listen(port, () => resolve(server.address()));
      }),
    close: () =>
      new Promise((resolve, reject) => {
        wss.close();
        server.close((err) => (err ? reject(err) : resolve()));
      }),
  };
}

async function main() {
  if (!SECRET) {
    console.error('Set RELAY_SECRET');
    process.exit(1);
  }
  if (!ALLOW_INSECURE && (!fs.existsSync(CERT_PATH) || !fs.existsSync(KEY_PATH))) {
    console.error(`Missing TLS certs. Run: npm run gen-cert\nExpected ${CERT_PATH} and ${KEY_PATH}`);
    process.exit(1);
  }
  const relay = createRelayServer({ secret: SECRET });
  const addr = await relay.listen();
  console.log(
    `voxcrew-relay listening on ${ALLOW_INSECURE ? 'ws' : 'wss'} port ${typeof addr === 'object' && addr ? addr.port : PORT}`,
  );
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/'));
if (isMain || process.argv[1]?.endsWith('server.mjs')) {
  main().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
