import test from 'node:test';
import assert from 'node:assert/strict';
import { EmotionEngine, AVATAR_EMOTION, TONE_HINT } from '../src/mobile/emotion';
test('user wording picks a tone in Roman Urdu, Hinglish and English', () => {
  const e = new EmotionEngine();
  assert.equal(e.fromUser('yaar phir se nahi ho raha'), 'concerned');
  assert.equal(e.fromUser('kitni baar bolun, kaam nahi kar raha'), 'apologetic');
  e.reset();
  assert.equal(e.fromUser('Wow zabardast!'), 'excited');
  assert.equal(e.fromUser('shukriya FRIDAY'), 'happy');
  assert.equal(e.fromUser('jaldi Ammi ko call karo'), 'focused');
  assert.equal(e.fromUser('time kya hua hai?'), 'curious');
  assert.equal(e.fromUser('YouTube kholo'), 'calm');
});
test('action results set the tone; success after frustration stays calm', () => {
  const e = new EmotionEngine();
  assert.equal(e.fromAction('open_app', 'opened'), 'confident');
  assert.equal(e.fromAction('play_music', 'playing'), 'playful');
  assert.equal(e.fromAction('whatsapp_message', 'needs_confirmation'), 'focused');
  assert.equal(e.fromAction('tap', 'permission_required'), 'serious');
  assert.equal(e.fromAction('call', 'timeout'), 'concerned');
  e.fromUser('ugh not working again');
  assert.equal(e.fromAction('call', 'error'), 'apologetic');
  assert.equal(e.fromAction('call', 'calling'), 'calm');
});
test('every tone has an avatar face and a hint', () => {
  for (const tone of Object.keys(TONE_HINT)) assert.ok(AVATAR_EMOTION[tone as keyof typeof AVATAR_EMOTION], tone);
});
