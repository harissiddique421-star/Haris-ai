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
  assert.equal(agentFor('research_report'), 'research');
  assert.equal(agentFor('build_website'), 'document');
  assert.equal(agentFor('send_document'), 'document');
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
test('command center: a switched-off module refuses its actions; status and resume stay on', async () => {
  const calls: any[] = [];
  const o = new Orchestrator(async (_n, a) => { calls.push(a); return { status: 'done' }; });
  o.policy = { autonomy: 'balanced', paused: false, modulesOff: ['media', 'health' as any] };
  o.userSpoke();
  assert.equal((await o.dispatch('mobile_action', { action: 'search_youtube', query: 'naat' })).status, 'disabled');
  assert.equal((await o.dispatch('mobile_action', { action: 'torch', on: true })).status, 'done');
  assert.equal((await o.dispatch('mobile_action', { action: 'self_report' })).status, 'done', 'health can never be switched off');
  assert.equal(calls.length, 2);
});
test('command center: health rows report problems and never call missing data fine', async () => {
  const { moduleHealth, healthSummary, normalizeOff, MODULES } = await import('../src/mobile/commandCenter');
  assert.deepEqual(normalizeOff(['media', 'media', 'health', 'nope', 5]), ['media']);
  assert.ok(!MODULES.some(m => m.agent === 'health'));
  const rows = moduleHealth({ keyConfigured: true, keyVerified: false, providerErrorCode: 'QUOTA_EXCEEDED', autonomy: { level: 'maximum', paused: true, modulesOff: ['media'] },
    voiceId: { registered: true, on: true }, wake: { on: true, running: false, status: 'stopped' }, notificationAccess: false, phoneControl: true, providers: { kind: 'openrouter', ready: false } },
    new Map([['device', { runs: 4, failures: 3, totalMs: 400, lastMs: 100 }]]) as any);
  const get = (m: string) => rows.find(r => r.module === m)!;
  assert.equal(get('Autonomy').health, 'warn'); assert.match(get('Autonomy').detail, /Paused/);
  assert.equal(get('Gemini').health, 'warn'); assert.match(get('Gemini').detail, /QUOTA_EXCEEDED/);
  assert.equal(get('Second provider').health, 'warn');
  assert.equal(get('Wake word').health, 'warn');
  assert.equal(get('Notifications').health, 'off');
  assert.equal(get('Media').health, 'off');
  assert.equal(get('Phone controls').health, 'warn', '3 of 4 failed');
  assert.equal(get('Voice ID').health, 'ok');
  assert.match(healthSummary(rows), /need attention/);
  const empty = moduleHealth({});
  assert.equal(empty.find(r => r.module === 'Gemini')!.health, 'off');
  assert.equal(empty.find(r => r.module === 'Screen control')!.health, 'off');
});
test('second brain: deleting a note needs the user, reading notes needs no question', async () => {
  const calls: any[] = [];
  const o = new Orchestrator(async (_n, a) => { calls.push(a); return { status: 'done' }; });
  assert.equal(agentFor('note'), 'memory');
  o.userSpoke(); await new Promise(r => setTimeout(r, 2)); o.phonePrompted();
  assert.equal((await o.dispatch('mobile_action', { action: 'note', command: 'delete', query: 'invoice' })).status, 'needs_user');
  assert.equal((await o.dispatch('mobile_action', { action: 'note', command: 'pending' })).status, 'done', 'reading is not risky');
  assert.equal(calls.length, 1);
  o.policy = { autonomy: 'assisted', paused: false }; o.userSpoke();
  assert.equal((await o.dispatch('mobile_action', { action: 'note', command: 'find', query: 'invoice' })).status, 'done', 'find needs no Assisted question');
  assert.equal((await o.dispatch('mobile_action', { action: 'note', command: 'save', text: 'idea: x' })).status, 'needs_confirmation', 'saving is a change, so Assisted asks');
});
test('second brain: Settings search and groups', async () => {
  const { noteMatches, noteGroups } = await import('../src/mobile/notes');
  const n = { id: 1, kind: 'task', title: 'Send invoice', text: 'Send the invoice to Ali', tags: ['work'] };
  assert.ok(noteMatches(n, 'invoice')); assert.ok(noteMatches(n, 'inv ali')); assert.ok(noteMatches(n, '#work'));
  assert.ok(!noteMatches(n, 'voice'), 'matches word starts only'); assert.ok(!noteMatches(n, 'invoice bob'));
  const groups = noteGroups([n, { ...n, id: 2, done: true }, { id: 3, kind: 'idea', title: 'i', text: 'i' }]);
  assert.deepEqual(groups.map(g => g[0]), ['Open tasks', 'Ideas', 'Done']);
});
