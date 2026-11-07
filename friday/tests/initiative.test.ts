import test from 'node:test';
import assert from 'node:assert/strict';
import { ContextualTopicManager, ConversationInitiativeManager, FollowUpQuestionGenerator, normalizeMode } from '../src/mobile/initiative';
import { parseMobileAction } from '../src/mobile/actions';

const base = { enabled: true, quietMs: 60_000, sinceLastCheckinMs: Infinity, checkinsThisCall: 0, busy: false };

test('quiet and focus modes never speak first', () => {
  const m = new ConversationInitiativeManager(new ContextualTopicManager());
  assert.equal(m.decideIdle({ ...base, mode: 'quiet', quietMs: 10 * 60_000 }), 'stay_quiet');
  assert.equal(m.decideIdle({ ...base, mode: 'focus', quietMs: 10 * 60_000 }), 'stay_quiet');
});
test('normal mode checks in once per call after a quiet spell', () => {
  const m = new ConversationInitiativeManager(new ContextualTopicManager());
  assert.equal(m.decideIdle({ ...base, mode: 'normal', quietMs: 20_000 }), 'stay_quiet');
  assert.equal(m.decideIdle({ ...base, mode: 'normal' }), 'check_in');
  assert.equal(m.decideIdle({ ...base, mode: 'normal', checkinsThisCall: 1, sinceLastCheckinMs: 20 * 60_000 }), 'stay_quiet');
});
test('companion mode continues the call topic, then follows up, then offers a new topic, with a cooldown', () => {
  const t = new ContextualTopicManager(); const m = new ConversationInitiativeManager(t);
  t.heard('kal mera exam hai yaar');
  assert.deepEqual([...t.topics], ['exam', 'plan']);
  assert.equal(m.decideIdle({ ...base, mode: 'companion', quietMs: 35_000 }), 'continue_topic');
  assert.equal(m.decideIdle({ ...base, mode: 'companion', checkinsThisCall: 1, sinceLastCheckinMs: 5 * 60_000 }), 'stay_quiet');
  assert.equal(m.decideIdle({ ...base, mode: 'companion', checkinsThisCall: 1, sinceLastCheckinMs: 11 * 60_000 }), 'follow_up');
  assert.equal(m.decideIdle({ ...base, mode: 'companion', checkinsThisCall: 2, sinceLastCheckinMs: 11 * 60_000 }), 'new_topic');
  assert.equal(m.decideIdle({ ...base, mode: 'companion', checkinsThisCall: 3, sinceLastCheckinMs: 11 * 60_000 }), 'stay_quiet');
});
test('asking for silence, ending the chat, being busy or the switch off keeps FRIDAY quiet', () => {
  const t = new ContextualTopicManager(); const m = new ConversationInitiativeManager(t);
  assert.equal(m.decideIdle({ ...base, mode: 'companion', busy: true }), 'stay_quiet');
  assert.equal(m.decideIdle({ ...base, mode: 'companion', enabled: false }), 'stay_quiet');
  t.heard('abhi chup raho, kaam kar raha hoon');
  assert.equal(m.decideIdle({ ...base, mode: 'companion' }), 'stay_quiet');
  const t2 = new ContextualTopicManager(); t2.heard('theek hai');
  assert.ok(t2.endedConversation);
});
test('prompts are hints, not scripts, and openings follow the mode', () => {
  const t = new ContextualTopicManager(); t.heard('project mein bug aa raha hai');
  assert.match(FollowUpQuestionGenerator.idle('continue_topic', t, 'calm'), /project/);
  assert.match(FollowUpQuestionGenerator.opening('quiet', 'mic', true, '"Ji"', 'calm'), /Quiet mode/);
  assert.match(FollowUpQuestionGenerator.opening('companion', 'wake', true, '"Ji"', 'calm'), /first time today/);
  assert.doesNotMatch(FollowUpQuestionGenerator.opening('companion', 'wake', false, '"Ji"', 'calm'), /first time today/);
  assert.match(FollowUpQuestionGenerator.opening('normal', 'checkin', false, '"Ji"', 'calm'), /check-in/);
  assert.equal(normalizeMode('weird'), 'normal');
});
test('typed mode commands', () => {
  assert.deepEqual(parseMobileAction('companion mode on'), { action: 'conversation_mode', mode: 'companion' });
  assert.deepEqual(parseMobileAction('Quiet mode'), { action: 'conversation_mode', mode: 'quiet' });
  assert.deepEqual(parseMobileAction('normal mode'), { action: 'set_mode', mode: 'normal' });
});
