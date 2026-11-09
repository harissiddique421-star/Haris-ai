import { test } from 'node:test';
import assert from 'node:assert/strict';
import { liveRecovery, SESSION_ENDING, type RecoveryState } from '../src/mobile/recovery';

const ok: RecoveryState = { fallbacks: 0, reconnects: 0, online: true, visible: true, resuming: false };
const drop = (code: number, when = 'during the call', reason = '') => `Gemini Live disconnected ${when} (code ${code}${reason ? ': ' + reason : ''}). Check your key, quota and Live model in Settings.`;

test('quota refusal switches to the next Live model, at most twice', () => {
  assert.equal(liveRecovery(drop(1011, 'while starting', 'Quota exceeded'), ok), 'switch_model');
  assert.equal(liveRecovery(drop(1011, 'during the call', 'RESOURCE_EXHAUSTED'), ok), 'switch_model');
  assert.equal(liveRecovery(drop(1011, 'while starting', 'Quota exceeded'), { ...ok, fallbacks: 2 }), 'none');
});

test('a model the key cannot use switches model while starting', () => {
  assert.equal(liveRecovery(drop(1008, 'while starting', 'models/x is not found for API version v1beta'), ok), 'switch_model');
  assert.equal(liveRecovery(drop(1008, 'while starting', 'API key not valid'), ok), 'none');
});

test('session ending and dropped calls reconnect, with a limit', () => {
  assert.equal(liveRecovery(SESSION_ENDING + ' Tap Talk to start a fresh call.', ok), 'reconnect');
  assert.equal(liveRecovery(drop(1006), ok), 'reconnect');
  assert.equal(liveRecovery(drop(1011, 'during the call', 'Internal error'), ok), 'reconnect');
  assert.equal(liveRecovery(drop(1006), { ...ok, reconnects: 2 }), 'none');
});

test('no reconnect when offline, hidden, or the key is refused', () => {
  assert.equal(liveRecovery(drop(1006), { ...ok, online: false }), 'none');
  assert.equal(liveRecovery(drop(1006), { ...ok, visible: false }), 'none');
  assert.equal(liveRecovery(drop(1007, 'while starting', 'API key not valid'), ok), 'none');
  assert.equal(liveRecovery('No internet connection. Connect to the internet and try again.', ok), 'none');
});

test('a refused resume starts a fresh call', () => {
  assert.equal(liveRecovery(drop(1008, 'while starting', 'invalid session handle'), { ...ok, resuming: true }), 'reconnect');
  assert.equal(liveRecovery(drop(1007, 'while starting', 'Unknown name "sessionResumption"'), ok), 'reconnect');
});

test('a refused context compression setting starts the call again without it', () => {
  assert.equal(liveRecovery(drop(1007, 'while starting', 'Unknown name "contextWindowCompression"'), ok), 'reconnect');
  assert.equal(liveRecovery(drop(1007, 'while starting', 'Invalid slidingWindow'), ok), 'reconnect');
});
