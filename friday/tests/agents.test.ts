import test from 'node:test';
import assert from 'node:assert/strict';
import { Orchestrator, agentFor } from '../src/mobile/agents';
(globalThis as any).performance ??= { now: () => Date.now() };
test('each action has its agent', () => {
  assert.equal(agentFor('whatsapp_message'), 'communication');
  assert.equal(agentFor('search_youtube'), 'media');
  assert.equal(agentFor('torch'), 'device');
  assert.equal(agentFor('read_screen'), 'accessibility');
  assert.equal(agentFor('remember'), 'memory');
  assert.equal(agentFor('make_document'), 'document');
  assert.equal(agentFor('search_web'), 'research');
  assert.equal(agentFor('health_check'), 'health');
  assert.equal(agentFor('something_new'), 'core');
});
test('security: a phone prompt alone cannot trigger risky actions', async () => {
  const calls: any[] = [];
  const o = new Orchestrator(async (_n, a) => { calls.push(a); return { status: 'needs_confirmation' }; });
  o.userSpoke(); await new Promise(r => setTimeout(r, 2)); o.phonePrompted();
  const blocked = await o.dispatch('mobile_action', { action: 'whatsapp_message', recipient: 'Ali', message: 'hi' });
  assert.equal(blocked.status, 'needs_user'); assert.equal(calls.length, 0);
  assert.equal((await o.dispatch('mobile_action', { action: 'torch', on: true })).status, 'needs_confirmation');
  await new Promise(r => setTimeout(r, 2)); o.userSpoke();
  assert.equal((await o.dispatch('mobile_action', { action: 'whatsapp_message', recipient: 'Ali', message: 'hi' })).status, 'needs_confirmation');
  o.phonePrompted();
  assert.equal((await o.dispatch('mobile_action', { action: 'call', recipient: 'Ali' }, true)).status, 'needs_confirmation', 'typed is the user');
  assert.equal((await o.dispatch('mobile_action', { action: 'call', recipient: 'Ali', confirm_token: 't' })).status, 'needs_confirmation', 'phone token passes');
});
test('duplicate in-flight requests run once; failures become results', async () => {
  let runs = 0;
  const o = new Orchestrator(async () => { runs++; await new Promise(r => setTimeout(r, 20)); return { status: 'opened' }; });
  const [a, b] = await Promise.all([o.dispatch('mobile_action', { action: 'open_app', app: 'YouTube' }), o.dispatch('mobile_action', { action: 'open_app', app: 'YouTube' })]);
  assert.equal(runs, 1); assert.equal(a.status, 'opened'); assert.equal(b.status, 'opened');
  const bad = new Orchestrator(async () => { throw new Error('boom'); });
  assert.equal((await bad.dispatch('mobile_action', { action: 'torch' })).status, 'error');
  assert.match(bad.summary(), /device 1 run, .* 1 failed/);
});
test('SOS and message replies: owned by communication, never from a phone prompt alone', async () => {
  assert.equal(agentFor('emergency'), 'communication'); assert.equal(agentFor('reply_message'), 'communication');
  assert.equal(agentFor('prayer_times'), 'core'); assert.equal(agentFor('qibla'), 'core');
  const calls: any[] = [];
  const o = new Orchestrator(async (_n, a) => { calls.push(a); return { status: 'needs_confirmation' }; });
  o.userSpoke(); await new Promise(r => setTimeout(r, 2)); o.phonePrompted();
  // A message read aloud from a notification must not make FRIDAY reply or send an SOS by itself.
  assert.equal((await o.dispatch('mobile_action', { action: 'reply_message', message: 'ok' })).status, 'needs_user');
  assert.equal((await o.dispatch('mobile_action', { action: 'emergency' })).status, 'needs_user');
  assert.equal(calls.length, 0);
  assert.equal((await o.dispatch('mobile_action', { action: 'prayer_times' })).status, 'needs_confirmation', 'harmless actions still run');
});
