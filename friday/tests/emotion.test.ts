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
import { VoiceTone } from '../src/mobile/voiceTone';
/** A 64 ms voiced frame: a harmonic tone at f0 Hz and amplitude amp, plus a little noise. */
function voiced(f0: number, amp: number, phase = 0) {
  const x = new Float32Array(1024);
  for (let i = 0; i < x.length; i++) { const t = (i + phase) / 16000; x[i] = amp * (Math.sin(2 * Math.PI * f0 * t) + 0.5 * Math.sin(4 * Math.PI * f0 * t) + 0.25 * Math.sin(6 * Math.PI * f0 * t)) + (Math.random() - 0.5) * amp * 0.05; }
  return x;
}
function sentence(v: VoiceTone, f0: number, amp: number, wobble = 0.08) {
  for (let k = 0; k < 15; k++) v.feed(voiced(f0 * (1 + wobble * Math.sin(k)), amp, k * 1024));
  return v.end();
}
test('voice tone measures pitch', () => {
  for (const f0 of [110, 180, 260]) {
    const p = VoiceTone.pitch(voiced(f0, 0.2));
    assert.ok(Math.abs(p - f0) / f0 < 0.05, `${f0} Hz heard as ${p}`);
  }
  assert.equal(VoiceTone.pitch(new Float32Array(1024).map(() => Math.random() - 0.5)), 0);
});
test('voice tone: raised and low against the usual voice, nothing before it is known', () => {
  const v = new VoiceTone();
  assert.equal(sentence(v, 150, 0.05), null);
  assert.equal(sentence(v, 210, 0.2), null); // no usual voice yet
  v.reset();
  for (let i = 0; i < 3; i++) assert.equal(sentence(v, 150, 0.05), null);
  assert.equal(sentence(v, 190, 0.15), 'raised');
  assert.equal(sentence(v, 152, 0.05), null);
  assert.equal(sentence(v, 135, 0.015, 0.02), 'low');
  assert.equal(sentence(v, 150, 0.05), null);
});
test('the voice settles neutral words; the words still decide first', () => {
  const e = new EmotionEngine();
  assert.equal(e.fromUser('YouTube kholo', 'raised'), 'focused');
  assert.equal(e.fromUser('theek hai', 'low'), 'gentle');
  assert.equal(e.fromUser('shukriya FRIDAY', 'low'), 'happy');
  assert.equal(e.fromUser('main bohat thak gaya hoon', 'low'), 'gentle');
  e.reset();
  assert.equal(e.fromUser('phir se nahi ho raha', 'raised'), 'apologetic');
  e.reset();
  e.fromUser('phir se nahi ho raha');
  assert.equal(e.fromUser('call karo', 'raised'), 'apologetic');
  assert.equal(TONE_HINT.gentle.length > 0 && AVATAR_EMOTION.gentle, 'idle');
});
