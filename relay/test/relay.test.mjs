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
  a.send(JSON.stringify({ type: 'hello', uid: 'uid-a', displayName: 'A', secret: 'test-secret' }));
  b.send(JSON.stringify({ type: 'hello', uid: 'uid-b', displayName: 'B', secret: 'test-secret' }));
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
  assert.equal(okB.type, 'dial_ok');
  assert.equal(okB.peerUid, 'uid-a');

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

// silence unused import in case tree-shaking complains in editors
void createServer;
void attachClient;
