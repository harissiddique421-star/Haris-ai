import test from 'node:test';
import assert from 'node:assert/strict';
import { addressee, turnState, openAiSchema, agentTools, repairHistory, trimHistory, toolContent, AgenticSession, STOP_WORDS, isNoise, isNotUnderstood, sameSentence } from '../src/mobile/agentic.ts';

test('turn detection waits for unfinished sentences', () => {
  assert.equal(turnState('Instagram kholo aur'), 'wait');
  assert.equal(turnState('open YouTube and'), 'wait');
  assert.equal(turnState('Ali ko message karo ke'), 'wait');
  assert.equal(turnState('FRIDAY'), 'wait');
  assert.equal(turnState('hey friday'), 'wait');
  assert.equal(turnState('mujhe batao,'), 'wait');
  assert.equal(turnState('Instagram kholo'), 'done');
  assert.equal(turnState('what is the weather today?'), 'done');
});

test('addressee detection', () => {
  const far = { sinceFridayMs: 120_000, mode: 'smart' as const };
  assert.equal(addressee('FRIDAY time kya hua hai', far), 'friday');
  assert.equal(addressee('Instagram kholo', far), 'friday');
  assert.equal(addressee('mausam kaisa hai', far), 'friday');
  assert.equal(addressee('ammi khana laga dein', far), 'other');
  assert.equal(addressee('bhai tum kal aa rahe ho na', far), 'other');
  assert.equal(addressee('haan ji', far), 'other');
  assert.equal(addressee('haan ji', { sinceFridayMs: 5_000, mode: 'smart' }), 'friday');
  assert.equal(addressee('ammi khana laga dein', { sinceFridayMs: 120_000, mode: 'all' }), 'friday');
  assert.equal(addressee('the match was really good yesterday', far), 'other');
  assert.equal(addressee('ruko', far), 'friday');
  assert.ok(STOP_WORDS.test('ruko'));
});

test('tool schema is converted to lower-case JSON schema', () => {
  assert.deepEqual(openAiSchema({ type: 'OBJECT', properties: { a: { type: 'STRING', enum: ['x'] }, b: { type: 'ARRAY', items: { type: 'NUMBER' } } } }),
    { type: 'object', properties: { a: { type: 'string', enum: ['x'] }, b: { type: 'array', items: { type: 'number' } } } });
  const t = agentTools()[0];
  assert.equal(t.function.name, 'mobile_action');
  assert.equal(t.function.parameters.type, 'object');
  assert.equal(t.function.parameters.properties.action.type, 'string');
});

test('history repair and trimming', () => {
  const m = [{ role: 'system', content: 's' }, { role: 'user', content: 'u' }, { role: 'assistant', content: null, tool_calls: [{ id: 'a', function: { name: 'mobile_action' } }, { id: 'b', function: { name: 'mobile_action' } }] }, { role: 'tool', tool_call_id: 'a', content: '{}' }, { role: 'user', content: 'ruko' }];
  const r = repairHistory(m);
  assert.deepEqual(r.map(x => x.role), ['system', 'user', 'assistant', 'tool', 'tool', 'user']);
  assert.match(r[4].content, /cancelled/);
  const long = [{ role: 'system', content: 's' }];
  for (let i = 0; i < 50; i++) long.push({ role: 'user', content: 'u' + i } as any, { role: 'assistant', content: 'a' } as any);
  const t = trimHistory(long, 20);
  assert.equal(t[0].role, 'system'); assert.equal(t[1].role, 'user'); assert.ok(t.length <= 20);
  assert.ok(toolContent({ x: 'y'.repeat(9000) }).length < 6100);
});

function harness(steps: any[]) {
  const log: string[] = []; const dispatched: any[] = []; let i = 0;
  const s = new AgenticSession({
    step: async messages => { log.push('step:' + messages.at(-1).role); return steps[i++]; },
    cancelStep: () => log.push('cancel'),
    dispatch: async (_n, args) => { dispatched.push(args); return { status: 'done', action: args.action }; },
    speak: async text => { log.push('say:' + text); },
    ear: c => { log.push('ear:' + c); return 'ok'; },
    instruction: () => 'You are FRIDAY.',
    addresseeMode: () => 'smart',
    onState: () => {}, onUser: t => log.push('user:' + t), onReply: t => log.push('reply:' + t), onNotice: () => {}, onIgnored: t => log.push('ignored:' + t), onDropped: w => log.push('drop:' + w),
  });
  return { s, log, dispatched };
}
const call = (id: string, action: string, extra = {}) => ({ id, name: 'mobile_action', args: { action, ...extra } });
const assistantFor = (calls: any[]) => ({ role: 'assistant', content: null, tool_calls: calls.map(c => ({ id: c.id, type: 'function', function: { name: c.name, arguments: JSON.stringify(c.args) } })) });

test('one command runs many tool steps, then speaks once', async () => {
  const c1 = [call('1', 'open_app', { app: 'Instagram' })], c2 = [call('2', 'read_screen')], c3 = [call('3', 'tap', { target: 'Profile' }), call('4', 'read_screen')];
  const { s, log, dispatched } = harness([
    { text: '', calls: c1, assistant: assistantFor(c1) },
    { text: '', calls: c2, assistant: assistantFor(c2) },
    { text: '', calls: c3, assistant: assistantFor(c3) },
    { text: 'Profile khul gaya, 929 followers.', calls: [], assistant: { role: 'assistant', content: 'Profile khul gaya, 929 followers.' } },
  ]);
  assert.equal(s.start(), 'ok');
  s.heard('final', 'Instagram kholo aur profile dikhao');
  await new Promise(r => setTimeout(r, 20));
  assert.deepEqual(dispatched.map(d => d.action), ['open_app', 'read_screen', 'tap', 'read_screen']);
  assert.ok(log.includes('reply:Profile khul gaya, 929 followers.'));
  assert.equal(log.filter(l => l.startsWith('say:')).length, 1);
  assert.ok(log.indexOf('ear:pause') < log.indexOf('ear:resume'));
  s.stop();
});

test('unfinished sentence waits, chatter for others is ignored', async () => {
  const { s, log } = harness([{ text: 'Ji.', calls: [], assistant: { role: 'assistant', content: 'Ji.' } }]);
  s.start();
  s.heard('final', 'ammi chai bana dein');
  assert.ok(log.includes('ignored:ammi chai bana dein'));
  s.heard('final', 'YouTube kholo aur');
  assert.ok(!log.some(l => l.startsWith('user:YouTube')));
  s.heard('final', 'gaana chalao');
  assert.ok(log.includes('user:YouTube kholo aur gaana chalao'));
  s.stop();
});

test('noise rules: fillers are dropped, real short answers stay', () => {
  for (const t of ['hmm', 'Hmmm.', 'uh', 'umm', 'ah ah', 'oh', 'a', '...', 'hahaha']) assert.ok(isNoise(t), t);
  for (const t of ['haan', 'ji', 'ok', 'nahi', 'no', 'ruko', 'YouTube kholo', 'hmm YouTube kholo']) assert.ok(!isNoise(t), t);
  assert.ok(isNotUnderstood('Maaf kijiye, samajh nahi aaya, dobara boliye.'));
  assert.ok(isNotUnderstood("Sorry, I didn't catch that."));
  assert.ok(!isNotUnderstood('YouTube khul gaya.'));
  assert.ok(sameSentence('YouTube kholo.', 'youtube kholo'));
  assert.ok(!sameSentence('', 'youtube kholo'));
});

test('noise never reaches the brain, an echoed sentence is taken once', async () => {
  const { s, log } = harness([{ text: 'Ji.', calls: [], assistant: { role: 'assistant', content: 'Ji.' } }, { text: 'Ji.', calls: [], assistant: { role: 'assistant', content: 'Ji.' } }]);
  s.start();
  s.heard('final', 'hmm');
  assert.ok(log.includes('drop:noise'));
  assert.ok(!log.some(l => l.startsWith('user:')));
  s.heard('final', 'YouTube kholo');
  s.heard('final', 'YouTube kholo.');
  await new Promise(r => setTimeout(r, 20));
  assert.equal(log.filter(l => l === 'user:YouTube kholo').length, 1);
  assert.ok(log.includes('drop:dup'));
  s.stop();
});

test('"I did not understand" is spoken at most once in 20 seconds', async () => {
  const sorry = { text: 'Samajh nahi aaya, dobara boliye.', calls: [], assistant: { role: 'assistant', content: 'Samajh nahi aaya, dobara boliye.' } };
  const { s, log } = harness([sorry, sorry]);
  s.start();
  s.heard('final', 'YouTube kholo');
  await new Promise(r => setTimeout(r, 20));
  s.heard('final', 'Instagram kholo');
  await new Promise(r => setTimeout(r, 20));
  assert.equal(log.filter(l => l.startsWith('say:Samajh')).length, 1);
  assert.ok(log.includes('drop:repeat'));
  s.stop();
});
