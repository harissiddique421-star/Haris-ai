import test from 'node:test';
import assert from 'node:assert/strict';
import { TurnLevels } from '../src/lib/turnLevels';

const FRAME = 64;
/** Same pseudo-random sequence every run. */
function rng(seed = 7) { return () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; }; }

/**
 * Runs a room (noise level per frame) with the user talking in the given windows (syllables with short gaps),
 * and returns when each turn started and stopped.
 */
function run(room: (t: number, r: () => number) => number, talk: [number, number, number][], totalMs = 30000) {
  const levels = new TurnLevels(), r = rng();
  let speaking = false; const starts: number[] = [], stops: number[] = [];
  for (let t = 0; t < totalMs; t += FRAME) {
    let rms = room(t, r);
    for (const [a, b, voice] of talk) if (t >= a && t < b) {
      const v = ((t - a) % 320) < 224 ? voice * (0.6 + 0.4 * r()) : voice * 0.08;
      rms = Math.sqrt(rms * rms + v * v);
    }
    const change = levels.step(rms, t, speaking, false);
    if (change === 'start') { speaking = true; starts.push(t); } else if (change === 'stop') { speaking = false; stops.push(t); }
  }
  /** How long after each sentence ended the turn ended (Gemini answers only after that). */
  const endDelays = talk.map(([, b]) => { const s = stops.find(x => x >= b - 200); return s === undefined ? Infinity : s - b; });
  return { starts, stops, endDelays };
}
const flicker = (base: number, amount: number) => (_t: number, r: () => number) => base * Math.exp((r() * 2 - 1) * amount);
const sentences = (voice: number): [number, number, number][] => [[8000, 10500, voice], [16000, 18000, voice], [24000, 26500, voice]];

test('quiet room: a normal and a soft voice both start a turn, and it ends about half a second after they stop', () => {
  for (const voice of [0.05, 0.014]) {
    const { starts, endDelays } = run(flicker(0.002, 0.3), sentences(voice));
    assert.equal(starts.length, 3, `voice ${voice}`);
    for (const d of endDelays) assert.ok(d <= 600, `voice ${voice}: turn ended ${d} ms after the user stopped`);
  }
});

test('fan noise flickering around its level does not hold the turn open after the user stops (rc.65: up to ~4 s late)', () => {
  for (const noise of [0.006, 0.009]) {
    const { starts, endDelays } = run(flicker(noise, 0.4), sentences(0.05));
    assert.equal(starts.length, 3, `fan ${noise}`);
    for (const d of endDelays) assert.ok(d <= 600, `fan ${noise}: turn ended ${d} ms after the user stopped`);
  }
});

test('a steady loud fan above the quiet-room start level is learnt within seconds, not about 30 s', () => {
  const levels = new TurnLevels(), r = rng();
  let speaking = false, openSince = -1;
  for (let t = 0; t < 8000; t += FRAME) {
    const change = levels.step(0.015 * Math.exp((r() * 2 - 1) * 0.3), t, speaking, false);
    if (change === 'start') { speaking = true; openSince = t; } else if (change === 'stop') speaking = false;
  }
  assert.equal(speaking, false, `the fan still held a turn open (since ${openSince} ms)`);
  assert.ok(levels.noiseFloor > 0.01, `room level ${levels.noiseFloor.toFixed(4)} did not follow the fan`);
  const { endDelays } = run(flicker(0.015, 0.3), [[12000, 14500, 0.06], [20000, 22500, 0.06]]);
  for (const d of endDelays) assert.ok(d <= 600, `loud fan: turn ended ${d} ms after the user stopped`);
});

test('quieter words alone keep a turn at most 1.5 s', () => {
  const levels = new TurnLevels();
  assert.equal(levels.step(0.05, 0, false, false), null);
  assert.equal(levels.step(0.05, 64, false, false), 'start');
  let stoppedAt = -1;
  for (let t = 128; t < 4000 && stoppedAt < 0; t += FRAME) if (levels.step(levels.keepLevel() * 1.05, t, true, false) === 'stop') stoppedAt = t;
  assert.ok(stoppedAt > 0 && stoppedAt - 64 <= 1500 + FRAME, `stopped at ${stoppedAt} ms`);
});

test('interrupting FRIDAY still needs a loud voice for about half a second', () => {
  const levels = new TurnLevels();
  for (let i = 0; i < 7; i++) assert.equal(levels.step(0.09, i * FRAME, false, true, 0.05), null);
  assert.equal(levels.step(0.09, 7 * FRAME, false, true, 0.05), 'start');
  const quiet = new TurnLevels();
  for (let i = 0; i < 20; i++) assert.equal(quiet.step(0.05, i * FRAME, false, true, 0.05), null, 'a normal-level sound cut FRIDAY off');
});
