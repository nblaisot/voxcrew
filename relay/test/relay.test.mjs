import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import test from 'node:test';
import { WebSocket } from 'ws';
import {
  attachClient,
  buildInviteHtml,
  createRelayServer,
  decodeBinaryEnvelope,
  encodeBinaryEnvelope,
  parseOverlayEndpoint,
  timingSafeEqualString,
} from '../src/server.mjs';

test('timingSafeEqualString accepts equal secrets', () => {
  assert.equal(timingSafeEqualString('crew-secret', 'crew-secret'), true);
  assert.equal(timingSafeEqualString('crew-secret', 'wrong'), false);
});

test('binary envelope round-trips', () => {
  const frame = Buffer.from([1, 2, 3, 4]);
  const packed = encodeBinaryEnvelope('peer-a', frame);
  const decoded = decodeBinaryEnvelope(packed);
  assert.equal(decoded.peerUid, 'peer-a');
  assert.deepEqual(Buffer.from(decoded.frame), frame);
});

test('invite html includes deep link button', () => {
  const html = buildInviteHtml({
    deepLink: 'voxcrew://relay-config?url=wss%3A%2F%2Fx&secret=s',
    wssUrl: 'wss://x',
  });
  assert.match(html, /Open in VoxCrew/);
  assert.match(html, /voxcrew:\/\/relay-config/);
});

function onceMessage(ws) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('timeout')), 3000);
    ws.once('message', (data, isBinary) => {
      clearTimeout(t);
      resolve({ data, isBinary });
    });
  });
}

test('hello rejects bad secret and dial bridges when both registered', async () => {
  const relay = createRelayServer({
    secret: 'test-secret',
    port: 0,
    allowInsecure: true,
  });
  const addr = await relay.listen();
  const port = typeof addr === 'object' && addr ? addr.port : 0;
  const url = `ws://127.0.0.1:${port}`;

  const inviteRes = await fetch(`http://127.0.0.1:${port}/invite?url=wss://x&secret=s`);
  assert.equal(inviteRes.status, 200);
  const inviteHtml = await inviteRes.text();
  assert.match(inviteHtml, /Open in VoxCrew/);

  const bad = new WebSocket(url);
  await new Promise((r) => bad.once('open', r));
  bad.send(JSON.stringify({ type: 'hello', uid: 'a', displayName: 'A', secret: 'nope' }));
  const rejectMsg = JSON.parse(String((await onceMessage(bad)).data));
  assert.equal(rejectMsg.type, 'hello_reject');
  bad.close();

  const a = new WebSocket(url);
  const b = new WebSocket(url);
  await Promise.all([
    new Promise((r) => a.once('open', r)),
    new Promise((r) => b.once('open', r)),
  ]);
  a.send(JSON.stringify({
    type: 'hello',
    uid: 'uid-a',
    displayName: 'A',
    secret: 'test-secret',
    overlayHost: '100.64.0.1',
    tcpPort: 47101,
  }));
  b.send(JSON.stringify({
    type: 'hello',
    uid: 'uid-b',
    displayName: 'B',
    secret: 'test-secret',
    overlayHost: '100.64.0.2',
    tcpPort: 47101,
  }));
  assert.equal(JSON.parse(String((await onceMessage(a)).data)).type, 'hello_ok');
  assert.equal(JSON.parse(String((await onceMessage(b)).data)).type, 'hello_ok');

  a.send(JSON.stringify({ type: 'dial', peerUid: 'uid-missing' }));
  const fail = JSON.parse(String((await onceMessage(a)).data));
  assert.equal(fail.type, 'dial_fail');

  const bWait = onceMessage(b);
  a.send(JSON.stringify({ type: 'dial', peerUid: 'uid-b' }));
  const okA = JSON.parse(String((await onceMessage(a)).data));
  const okB = JSON.parse(String((await bWait).data));
  assert.equal(okA.type, 'dial_ok');
  assert.equal(okA.peerUid, 'uid-b');
  assert.equal(okA.peerOverlayHost, '100.64.0.2');
  assert.equal(okA.peerTcpPort, 47101);
  assert.equal(okB.type, 'dial_ok');
  assert.equal(okB.peerUid, 'uid-a');
  assert.equal(okB.peerOverlayHost, '100.64.0.1');
  assert.equal(okB.peerTcpPort, 47101);

  const peerOverlayWait = onceMessage(b);
  a.send(JSON.stringify({
    type: 'overlay_announce',
    overlayHost: '100.64.0.9',
    tcpPort: 47101,
  }));
  const peerOverlay = JSON.parse(String((await peerOverlayWait).data));
  assert.equal(peerOverlay.type, 'peer_overlay');
  assert.equal(peerOverlay.peerUid, 'uid-a');
  assert.equal(peerOverlay.overlayHost, '100.64.0.9');
  assert.equal(peerOverlay.tcpPort, 47101);

  const frame = Buffer.from([7, 8, 9]);
  const bFrame = onceMessage(b);
  a.send(encodeBinaryEnvelope('uid-b', frame), { binary: true });
  const forwarded = await bFrame;
  assert.equal(forwarded.isBinary, true);
  const env = decodeBinaryEnvelope(forwarded.data);
  assert.equal(env.peerUid, 'uid-a');
  assert.deepEqual(Buffer.from(env.frame), frame);

  a.close();
  b.close();
  await relay.close();
});

test('mutual roster interest notifies both sides; one-way does not', async () => {
  const relay = createRelayServer({
    secret: 'test-secret',
    port: 0,
    allowInsecure: true,
  });
  const addr = await relay.listen();
  const port = typeof addr === 'object' && addr ? addr.port : 0;
  const url = `ws://127.0.0.1:${port}`;

  const a = new WebSocket(url);
  const b = new WebSocket(url);
  await Promise.all([
    new Promise((r) => a.once('open', r)),
    new Promise((r) => b.once('open', r)),
  ]);
  a.send(JSON.stringify({ type: 'hello', uid: 'uid-a', displayName: 'A', secret: 'test-secret' }));
  b.send(JSON.stringify({ type: 'hello', uid: 'uid-b', displayName: 'B', secret: 'test-secret' }));
  assert.equal(JSON.parse(String((await onceMessage(a)).data)).type, 'hello_ok');
  assert.equal(JSON.parse(String((await onceMessage(b)).data)).type, 'hello_ok');

  // One-way: A knows B, B has not declared interest — no match.
  const noMatchWait = Promise.race([
    onceMessage(a).then(() => 'a'),
    onceMessage(b).then(() => 'b'),
    new Promise((r) => setTimeout(() => r('timeout'), 200)),
  ]);
  a.send(JSON.stringify({ type: 'roster_interest', uids: ['uid-b'] }));
  assert.equal(await noMatchWait, 'timeout');

  // Mutual: both know each other → both get roster_match.
  const matchA = onceMessage(a);
  const matchB = onceMessage(b);
  b.send(JSON.stringify({ type: 'roster_interest', uids: ['uid-a'] }));
  const msgA = JSON.parse(String((await matchA).data));
  const msgB = JSON.parse(String((await matchB).data));
  assert.equal(msgA.type, 'roster_match');
  assert.equal(msgA.peerUid, 'uid-b');
  assert.equal(msgA.displayName, 'B');
  assert.equal(msgB.type, 'roster_match');
  assert.equal(msgB.peerUid, 'uid-a');
  assert.equal(msgB.displayName, 'A');

  // Disconnect clears the departed peer's interest. A still lists B; fresh B has none → no rematch.
  b.close();
  await new Promise((r) => setTimeout(r, 50));

  const b2 = new WebSocket(url);
  await new Promise((r) => b2.once('open', r));
  b2.send(JSON.stringify({ type: 'hello', uid: 'uid-b', displayName: 'B', secret: 'test-secret' }));
  assert.equal(JSON.parse(String((await onceMessage(b2)).data)).type, 'hello_ok');

  const noRematch = Promise.race([
    onceMessage(a).then(() => 'a'),
    onceMessage(b2).then(() => 'b'),
    new Promise((r) => setTimeout(() => r('timeout'), 200)),
  ]);
  assert.equal(await noRematch, 'timeout');

  a.close();
  b2.close();
  await relay.close();
});

test('parseOverlayEndpoint rejects invalid ports', () => {
  assert.equal(parseOverlayEndpoint({ overlayHost: '100.1.1.1', tcpPort: 0 }), null);
  assert.deepEqual(
    parseOverlayEndpoint({ overlayHost: '100.1.1.1', tcpPort: 47101 }),
    { overlayHost: '100.1.1.1', tcpPort: 47101 },
  );
});

// silence unused import in case tree-shaking complains in editors
void createServer;
void attachClient;
