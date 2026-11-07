import test from 'node:test';
import assert from 'node:assert/strict';
import { ConversationContextManager, ConversationCooldownManager, ConversationInitiativeManager, FollowUpQuestionGenerator, TopicSuggestionEngine, inQuietHours, normalizeMode, normalizeFrequency } from '../src/mobile/initiative';
import { parseMobileAction } from '../src/mobile/actions';

const base = { frequency: 'medium' as const, enabled: true, quietHours: false, quietMs: 60_000, sinceLastCheckinMs: Infinity, checkinsThisCall: 0, busy: false };
function memoryStore() { let v: string | null = null; return { get: () => v, set: (x: string) => { v = x; } }; }

test('Active is the default mode; companion is its old name', () => {
  assert.equal(normalizeMode(undefined), 'active');
  assert.equal(normalizeMode('companion'), 'active');
  assert.equal(normalizeMode('quiet'), 'quiet');
  assert.equal(normalizeMode('weird'), 'normal');
  assert.equal(normalizeFrequency('x'), 'medium');
});
test('quiet and focus modes never speak first', () => {
  const m = new ConversationInitiativeManager(new ConversationContextManager());
  assert.equal(m.decideIdle({ ...base, mode: 'quiet', quietMs: 10 * 60_000 }), 'stay_quiet');
  assert.equal(m.decideIdle({ ...base, mode: 'focus', quietMs: 10 * 60_000 }), 'stay_quiet');
});
test('normal mode checks in once per call after a quiet spell', () => {
  const m = new ConversationInitiativeManager(new ConversationContextManager());
  assert.equal(m.decideIdle({ ...base, mode: 'normal', quietMs: 20_000 }), 'stay_quiet');
  assert.equal(m.decideIdle({ ...base, mode: 'normal' }), 'check_in');
  assert.equal(m.decideIdle({ ...base, mode: 'normal', checkinsThisCall: 1, sinceLastCheckinMs: 20 * 60_000 }), 'stay_quiet');
});
test('active mode continues the call topic, follows up from memory, then offers a new topic', () => {
  const c = new ConversationContextManager(); const m = new ConversationInitiativeManager(c, new ConversationCooldownManager(memoryStore()));
  c.heard('Aaj university gaya tha');
  assert.ok(c.topics.has('university'));
  assert.equal(m.decideIdle({ ...base, mode: 'active', quietMs: 35_000 }), 'continue_topic');
  m.used('continue_topic');
  assert.equal(m.decideIdle({ ...base, mode: 'active', checkinsThisCall: 1, sinceLastCheckinMs: 2 * 60_000 }), 'stay_quiet');
  assert.equal(m.decideIdle({ ...base, mode: 'active', checkinsThisCall: 1, sinceLastCheckinMs: 7 * 60_000 }), 'follow_up');
  m.used('follow_up');
  assert.equal(m.decideIdle({ ...base, mode: 'active', checkinsThisCall: 2, sinceLastCheckinMs: 7 * 60_000 }), 'new_topic');
  assert.equal(m.decideIdle({ ...base, mode: 'active', checkinsThisCall: 3, sinceLastCheckinMs: 7 * 60_000 }), 'stay_quiet');
});
test('frequency changes how soon and how often Active mode speaks first', () => {
  const m = new ConversationInitiativeManager(new ConversationContextManager(), new ConversationCooldownManager(memoryStore()));
  assert.equal(m.decideIdle({ ...base, mode: 'active', frequency: 'low', quietMs: 40_000 }), 'stay_quiet');
  assert.equal(m.decideIdle({ ...base, mode: 'active', frequency: 'high', quietMs: 25_000 }), 'follow_up');
  assert.equal(m.decideIdle({ ...base, mode: 'active', frequency: 'low', checkinsThisCall: 1, sinceLastCheckinMs: 60 * 60_000 }), 'stay_quiet');
  assert.equal(m.decideIdle({ ...base, mode: 'active', frequency: 'high', checkinsThisCall: 4, sinceLastCheckinMs: 4 * 60_000 }), 'follow_up');
});
test('short answers or a low mood lead to one caring check, not repeated', () => {
  const c = new ConversationContextManager(); const m = new ConversationInitiativeManager(c, new ConversationCooldownManager(memoryStore()));
  c.heard('boring tha'); c.heard('pata nahi');
  assert.equal(m.decideIdle({ ...base, mode: 'active' }), 'mood_check');
  m.used('mood_check');
  assert.notEqual(m.decideIdle({ ...base, mode: 'active', checkinsThisCall: 1, sinceLastCheckinMs: 7 * 60_000 }), 'mood_check');
  assert.match(FollowUpQuestionGenerator.idle('mood_check', c, 'calm'), /without assuming or diagnosing/);
});
test('silence requests, ending the chat, quiet hours, being busy or the switch off keep FRIDAY quiet', () => {
  const c = new ConversationContextManager(); const m = new ConversationInitiativeManager(c);
  assert.equal(m.decideIdle({ ...base, mode: 'active', busy: true }), 'stay_quiet');
  assert.equal(m.decideIdle({ ...base, mode: 'active', enabled: false }), 'stay_quiet');
  assert.equal(m.decideIdle({ ...base, mode: 'active', quietHours: true }), 'stay_quiet');
  c.heard('theek hai'); assert.equal(m.decideIdle({ ...base, mode: 'active' }), 'stay_quiet');
  c.heard('abhi chup raho, kaam kar raha hoon'); assert.equal(m.decideIdle({ ...base, mode: 'active' }), 'stay_quiet');
});
test('quiet hours wrap past midnight', () => {
  const at = (h: number, m = 0) => { const d = new Date(2026, 0, 1, h, m); return d; };
  assert.ok(inQuietHours(at(23), '22:00', '08:00'));
  assert.ok(inQuietHours(at(3), '22:00', '08:00'));
  assert.ok(!inQuietHours(at(12), '22:00', '08:00'));
  assert.ok(inQuietHours(at(14), '13:00', '15:00'));
  assert.ok(!inQuietHours(at(15), '13:00', '15:00'));
});
test('topic seeds and question kinds are not repeated across calls', () => {
  const store = memoryStore(); const cool = new ConversationCooldownManager(store); const now = Date.now();
  const first = TopicSuggestionEngine.pick(cool, now); cool.mark('seed:' + first, now);
  const second = TopicSuggestionEngine.pick(new ConversationCooldownManager(store), now);
  assert.ok(first && second && first !== second);
  assert.ok(new ConversationCooldownManager(store).recent('seed:' + first, 86_400_000, now));
});
test('openings follow the mode', () => {
  assert.match(FollowUpQuestionGenerator.opening('quiet', 'mic', true, '"Ji"', 'calm'), /Quiet mode/);
  assert.match(FollowUpQuestionGenerator.opening('active', 'wake', true, '"Ji"', 'calm'), /first time today/);
  assert.doesNotMatch(FollowUpQuestionGenerator.opening('active', 'wake', false, '"Ji"', 'calm'), /first time today/);
  assert.match(FollowUpQuestionGenerator.opening('normal', 'checkin', false, '"Ji"', 'calm'), /check-in/);
});
test('typed mode commands', () => {
  assert.deepEqual(parseMobileAction('active mode on'), { action: 'conversation_mode', mode: 'active' });
  assert.deepEqual(parseMobileAction('companion mode'), { action: 'conversation_mode', mode: 'active' });
  assert.deepEqual(parseMobileAction('Quiet mode'), { action: 'conversation_mode', mode: 'quiet' });
  assert.deepEqual(parseMobileAction('normal mode'), { action: 'set_mode', mode: 'normal' });
});
