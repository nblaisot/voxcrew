/**
 * Minimal TLS WebSocket relay for VoxCrew.
 *
 * Protocol (plan): hello / dial / opaque LanFrame binary — no presence/WATCH.
 * Identity on the Mini is the peer UUID; no LAN/Tailscale IPs are stored.
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

/** @typedef {{ uid: string, displayName: string, ws: import('ws').WebSocket, bridges: Set<string> }} Client */

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

function sendJson(ws, obj) {
  if (ws.readyState === 1) ws.send(JSON.stringify(obj));
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
          previous.ws.close();
        } catch {
          /* ignore */
        }
        unregister(previous);
      }
      client = { uid, displayName, ws, bridges: new Set() };
      clients.set(uid, client);
      sendJson(ws, { type: 'hello_ok', uid });
      return;
    }

    if (!client) {
      sendJson(ws, { type: 'hello_reject', reason: 'hello_required' });
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
      sendJson(ws, { type: 'dial_ok', peerUid });
      // Peer learns we want a bridge so both sides can exchange Hello.
      sendJson(peer.ws, { type: 'dial_ok', peerUid: client.uid });
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
    server = http.createServer((_req, res) => {
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end('voxcrew-relay insecure\n');
    });
  } else {
    const cert = fs.readFileSync(certPath);
    const key = fs.readFileSync(keyPath);
    server = https.createServer({ cert, key }, (_req, res) => {
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end('voxcrew-relay\n');
    });
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
