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
test('self_report belongs to the health agent and every run is reported for self-development', async () => {
  assert.equal(agentFor('self_report'), 'health');
  const seen: [string, boolean][] = [];
  const o = new Orchestrator(async (_n, args: any) => args.action === 'torch' ? { status: 'done' } : { status: 'failed' });
  o.onRecord = (agent, _ms, failed) => seen.push([agent, failed]);
  await o.dispatch('mobile_action', { action: 'torch', on: true }, true);
  await o.dispatch('mobile_action', { action: 'battery' }, true);
  assert.deepEqual(seen, [['device', false], ['device', true]]);
  o.onRecord = () => { throw new Error('broken observer'); };
  assert.equal((await o.dispatch('mobile_action', { action: 'torch', on: false }, true)).status, 'done');
});
test('master pause: nothing runs except ending the pause', async () => {
  const calls: any[] = [];
  const o = new Orchestrator(async (_n, a) => { calls.push(a); return { status: 'done' }; });
  o.policy = { autonomy: 'balanced', paused: true };
  assert.equal((await o.dispatch('mobile_action', { action: 'torch', on: true })).status, 'paused');
  assert.equal((await o.dispatch('mobile_action', { action: 'open_app', app: 'YouTube' }, true)).status, 'paused', 'typed too');
  assert.equal(calls.length, 0);
  o.userSpoke();
  assert.equal((await o.dispatch('mobile_action', { action: 'set_autonomy', level: 'resume' })).status, 'done');
  assert.equal(calls.length, 1);
});
test('assisted mode: an action runs only after a yes to that exact request', async () => {
  const calls: any[] = [];
  const o = new Orchestrator(async (_n, a) => { calls.push(a); return { status: 'done' }; });
  o.policy = { autonomy: 'assisted', paused: false };
  o.userSpoke();
  assert.equal((await o.dispatch('mobile_action', { action: 'battery' })).status, 'done', 'reading needs no question');
  assert.equal((await o.dispatch('mobile_action', { action: 'voice_note', command: 'latest' })).status, 'done');
  const ask = await o.dispatch('mobile_action', { action: 'open_app', app: 'YouTube' });
  assert.equal(ask.status, 'needs_confirmation'); assert.equal(calls.length, 2);
  // Same token before the user answered: asked again, not run.
  assert.equal((await o.dispatch('mobile_action', { action: 'open_app', app: 'YouTube', confirm_token: ask.confirm_token })).status, 'needs_confirmation');
  const ask2 = await o.dispatch('mobile_action', { action: 'open_app', app: 'YouTube' });
  await new Promise(r => setTimeout(r, 2)); o.userSpoke();
  // A token for another request does not work.
  assert.equal((await o.dispatch('mobile_action', { action: 'open_app', app: 'Chrome', confirm_token: ask2.confirm_token })).status, 'needs_confirmation');
  const ok = await o.dispatch('mobile_action', { action: 'open_app', app: 'YouTube', confirm_token: ask2.confirm_token });
  assert.equal(ok.status, 'done'); assert.deepEqual(calls[calls.length - 1], { action: 'open_app', app: 'YouTube' }, 'token removed before running');
  assert.equal((await o.dispatch('mobile_action', { action: 'open_app', app: 'YouTube', confirm_token: ask2.confirm_token })).status, 'needs_confirmation', 'a token works once');
  assert.equal((await o.dispatch('mobile_action', { action: 'voice_note', command: 'clear' })).status, 'needs_confirmation', 'deleting is not reading');
  assert.equal((await o.dispatch('mobile_action', { action: 'whatsapp_message', recipient: 'Ali', message: 'hi' })).status, 'done', 'the phone asks for messages itself: never twice');
  assert.equal((await o.dispatch('mobile_action', { action: 'torch', on: true }, true)).status, 'done', 'typed by the user');
});
test('balanced and maximum: no extra question; set_autonomy only from the user', async () => {
  const o = new Orchestrator(async () => ({ status: 'done' }));
  o.policy = { autonomy: 'maximum', paused: false };
  assert.equal((await o.dispatch('mobile_action', { action: 'open_app', app: 'YouTube' })).status, 'done');
  o.userSpoke(); await new Promise(r => setTimeout(r, 2)); o.phonePrompted();
  assert.equal((await o.dispatch('mobile_action', { action: 'set_autonomy', level: 'maximum' })).status, 'needs_user');
  assert.equal(agentFor('set_autonomy'), 'health');
});
