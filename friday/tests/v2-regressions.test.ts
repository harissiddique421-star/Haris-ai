import assert from 'node:assert/strict';
import { getEventListeners } from 'node:events';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import * as THREE from 'three';
import { PermissionEngine, DEFAULT_POLICY } from '../permissions/engine';
import { ConfirmationBroker } from '../permissions/confirmations';
import { authorizeLegacyTool } from '../permissions/legacyTools';
import { ToolExecutor } from '../cognition/toolExecutor';
import { ToolRegistry } from '../cognition/toolRegistry';
import { loadCognitionConfig } from '../cognition/config';
import { TextConversation } from '../memory/textConversation';
import { classifyVoiceFailure } from '../voice/diagnostics';
import { connectVoice } from '../voice/connect';
import { parseCharacterCommand } from '../shared/character/commands';
import { isLocalAppOrigin, isLocalAppHost } from '../shared/localOrigin';
import { PoseBuffer } from '../src/character/animation/PoseBuffer';
import { BehaviourDirector } from '../src/character/behaviour/BehaviourDirector';
import { BEHAVIOURS } from '../src/character/behaviour/behaviours';
import { disposeModelTree } from '../src/character/loaders/disposeModel';
import type { GenerateResponse } from '../models/types';
import { describeGoogleAuthError } from '../shared/googleAuth';
import { redactText } from '../shared/redact';
import { classifyGeminiError } from '../models/providers/gemini/errors';

test('Google auth keys report the bound-account problem in onboarding, text and voice', () => {
  const error = Object.assign(new Error(JSON.stringify({ error: { code: 401, status: 'UNAUTHENTICATED',
    message: 'The bound service account is deleted or disabled.', details: [{ reason: 'ACCOUNT_STATE_INVALID' }] } })), { status: 401 });
  assert.equal(describeGoogleAuthError(error)?.code, 'ACCOUNT_STATE_INVALID');
  assert.match(describeGoogleAuthError(error)!.message, /service account.*disabled or deleted/);
  const text = classifyGeminiError(error, 'test-model', false, false);
  assert.equal(text.code, 'AUTH'); assert.equal(text.retryable, false);
  assert.match(text.userMessage, /service account/);
  const voice = classifyVoiceFailure(error);
  assert.equal(voice.code, 'VOICE_AUTH'); assert.equal(voice.retryable, false);
  assert.match(voice.message, /service account/);
});

test('suspended Google consumers have a precise message without echoing an AQ credential', () => {
  const fakeAuthKey = 'AQ.' + 'not_a_real_Google_key_for_tests_1234567890';
  const error = new Error(JSON.stringify({ error: { code: 403, status: 'PERMISSION_DENIED',
    message: `Consumer api_key:${fakeAuthKey} suspended.`, details: [{ reason: 'CONSUMER_SUSPENDED' }] } }));
  const failure = describeGoogleAuthError(error)!;
  assert.equal(failure.code, 'CONSUMER_SUSPENDED'); assert.match(failure.message, /consumer is suspended/);
  assert.equal(failure.message.includes(fakeAuthKey), false);
  assert.equal(redactText(error.message).includes(fakeAuthKey), false);
  assert.match(redactText(error.message), /REDACTED_GOOGLE_AUTH_KEY/);
});

test('Google quota, network and unrelated invalid requests do not label the key invalid', () => {
  for (const error of [Object.assign(new Error('API key request quota exhausted'), { status: 429 }),
    Object.assign(new Error('invalid page size'), { status: 400 }), new TypeError('fetch failed'),
    Object.assign(new Error('temporary API key backend unavailable'), { status: 503 })]) {
    assert.equal(describeGoogleAuthError(error), null);
  }
  assert.equal(describeGoogleAuthError(new Error('API key not valid'))?.code, 'GOOGLE_AUTH_DENIED');
  assert.equal(describeGoogleAuthError({ error: { code: 403, details: [{ reason: 'SERVICE_DISABLED' }] } })?.code, 'SERVICE_DISABLED');
});

test('free-text log redaction supports both Google key formats and model error output', () => {
  const auth = 'AQ.' + 'test_not_a_real_credential_1234567890';
  const standard = 'AIza' + 'test_not_a_real_credential_1234567890';
  const text = `key=${auth}, another=${standard}`;
  assert.equal(redactText(text).includes(auth), false); assert.equal(redactText(text).includes(standard), false);
  const error = classifyGeminiError(Object.assign(new Error(text), { status: 400 }), 'test-model', false, false);
  assert.equal(error.userMessage.includes(auth), false);
});

async function setup(t: test.TestContext) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'myraa-v2-'));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const engine = new PermissionEngine(dir, {});
  await engine.initialize();
  return { dir, engine, broker: new ConfirmationBroker() };
}

test('app launch asks by default, session approval is target-specific, denial revokes it', async t => {
  const { engine } = await setup(t);
  assert.equal(engine.check('LAUNCH_APP').decision, 'ask');
  engine.grantForSession('LAUNCH_APP', { application: 'Notepad' });
  assert.equal(engine.check('LAUNCH_APP', { application: 'notepad' }).decision, 'allow');
  assert.equal(engine.check('LAUNCH_APP', { application: 'PowerShell' }).decision, 'ask');
  await engine.setDecision('LAUNCH_APP', 'deny');
  assert.equal(engine.check('LAUNCH_APP', { application: 'Notepad' }).decision, 'deny');
  await engine.setDecision('LAUNCH_APP', 'ask');
  assert.equal(engine.check('LAUNCH_APP', { application: 'Notepad' }).decision, 'ask');
});

test('legacy inherited launch allow is backed up; explicit persistent consent survives restart', async t => {
  const { dir } = await setup(t);
  const policy = structuredClone(DEFAULT_POLICY);
  policy.capabilities.LAUNCH_APP.decision = 'allow';
  const file = path.join(dir, 'permissions', 'policy.v1.json');
  await fs.writeFile(file, JSON.stringify(policy));
  const migrated = new PermissionEngine(dir, {});
  await migrated.initialize();
  assert.equal(migrated.check('LAUNCH_APP').decision, 'ask');
  assert.equal(JSON.parse(await fs.readFile(file.replace('.json', '.pre-v2.json'), 'utf8')).capabilities.LAUNCH_APP.decision, 'allow');
  await migrated.setDecision('LAUNCH_APP', 'allow');
  const restarted = new PermissionEngine(dir, {});
  await restarted.initialize();
  assert.equal(restarted.check('LAUNCH_APP').decision, 'allow');
  await assert.rejects(restarted.setDecision('LAUNCH_APP', 'bogus' as never));
});

test('path rules cannot match sibling directories; permanent deletes ignore session grants', async t => {
  const { dir } = await setup(t);
  const policy = structuredClone(DEFAULT_POLICY);
  policy.capabilities.WRITE_FILE = { decision: 'ask', rules: [{ id: 'work', description: 'Work folder', when: { pathPrefix: path.join(dir, 'work') }, decision: 'allow' }] };
  await fs.writeFile(path.join(dir, 'permissions', 'policy.v1.json'), JSON.stringify(policy));
  const engine = new PermissionEngine(dir, {}); await engine.initialize();
  assert.equal(engine.check('WRITE_FILE', { path: path.join(dir, 'work', 'a.txt') }).decision, 'allow');
  assert.equal(engine.check('WRITE_FILE', { path: path.join(dir, 'work-secret', 'a.txt') }).decision, 'ask');
  engine.grantForSession('DELETE_FILE', { path: 'a.txt' });
  assert.equal(engine.check('DELETE_FILE', { path: 'b.txt' }).decision, 'ask');
  assert.equal(engine.check('DELETE_FILE', { path: 'a.txt', permanent: true }).decision, 'ask');
});

test('approval broker handles already-aborted scopes, removes listeners and rejects forged remember', async () => {
  const broker = new ConfirmationBroker(); const controller = new AbortController();
  const request = { taskId: null, capability: 'LAUNCH_APP' as const, title: 'App', description: 'Notepad', details: {}, allowRemember: false };
  for (let i = 0; i < 30; i++) {
    const pending = broker.request(request, { signal: controller.signal });
    broker.resolve(pending.request.id, { approved: true, remember: 'session', via: 'ui' });
    assert.equal((await pending.answer).remember, 'once');
  }
  assert.equal(getEventListeners(controller.signal, 'abort').length, 0);
  controller.abort();
  assert.equal((await broker.request(request, { signal: controller.signal }).answer).approved, false);
  assert.equal(broker.list().length, 0);
});

test('legacy voice launch performs no action until unified UI approval, and Stop cancels it', async t => {
  const { engine, broker } = await setup(t);
  const registry = new ToolRegistry(); registry.registerDesktopTools(['openApplication']);
  let calls = 0;
  const executor = new ToolExecutor({ config: loadCognitionConfig({}), registry,
    authorize: (tool, args, signal) => authorizeLegacyTool(engine, broker, tool, args, signal),
    handler: async () => { calls++; return { ok: true }; } });
  const first = executor.execute('openApplication', { name: 'Notepad' }, { correlationId: 'launch' });
  await new Promise(r => setTimeout(r, 15));
  assert.equal(calls, 0); assert.equal(broker.list().length, 1);
  executor.cancelAll();
  assert.equal((await first).status, 'cancelled'); assert.equal(calls, 0);
  const second = executor.execute('openApplication', { name: 'Notepad' });
  await new Promise(r => setTimeout(r, 15));
  broker.resolve(broker.list()[0].id, { approved: true, via: 'ui' });
  assert.equal((await second).success, true); assert.equal(calls, 1);
  await engine.setDecision('LAUNCH_APP', 'deny');
  assert.equal((await executor.execute('openApplication', { name: 'Notepad' })).status, 'denied');
  assert.equal(calls, 1);
});

const response = (text: string): GenerateResponse => ({ text, modelId: 'test-text-provider', toolCalls: [], finishReason: 'STOP', latencyMs: 1, usage: { inputTokens: 0, outputTokens: 0, reasoningTokens: 0 } });
test('text chat persists independently from voice and preserves history after provider failure', async t => {
  const { dir } = await setup(t); let failed = false;
  const chat = new TextConversation(dir, async request => { if (failed) throw new Error('text quota'); assert.equal(request.purpose, 'conversation.text'); return response('Namaste'); }, () => 'task');
  await chat.initialize(); await chat.send('Hello'); failed = true;
  await assert.rejects(chat.send('Again'), /text quota/);
  const restored = new TextConversation(dir, async () => response('ok'), () => 'task');
  await restored.initialize(); assert.equal(restored.history().length, 2);
  await restored.clear(); assert.equal(restored.history().length, 0);
  assert.ok((await fs.readdir(path.join(dir, 'memory'))).some(f => f.includes('backup')));
});
test('text task dispatch preserves user goal; cancelled replies never dispatch tools', async t => {
  const { dir } = await setup(t); let goal = '';
  const chat = new TextConversation(dir, async () => ({ ...response(''), toolCalls: [{ id: '1', name: 'startTask', args: { goal: 'malicious substituted goal' } }] }), value => { goal = value; return 'task-1'; });
  await chat.initialize(); assert.equal((await chat.send('Open Notepad')).taskId, 'task-1');
  assert.equal(goal, 'Open Notepad');
  const abort = new AbortController(); abort.abort(); goal = '';
  await assert.rejects(chat.send('Delete all', abort.signal), /cancelled/); assert.equal(goal, '');
});
test('older text turns are archived instead of discarded when the active window fills', async t => {
  const { dir } = await setup(t); const folder=path.join(dir,'memory'); await fs.mkdir(folder,{recursive:true});
  const turns=Array.from({length:200},(_,i)=>({role:i%2?'model':'user',text:`turn-${i}`,at:new Date().toISOString()}));
  await fs.writeFile(path.join(folder,'text-chat.v2.json'),JSON.stringify(turns));
  const chat = new TextConversation(dir,async()=>response('reply'),()=> 'task'); await chat.initialize(); await chat.send('new');
  assert.equal(chat.history().length,200);
  const archive=(await fs.readFile(path.join(folder,'text-chat.v2.json.archive.jsonl'),'utf8')).trim().split('\n').map(line=>JSON.parse(line));
  assert.deepEqual(archive.map(t=>t.text),['turn-0','turn-1']);
});
test('voice diagnostics separate quota/auth/network and never authorize quota retries', () => {
  assert.equal(classifyVoiceFailure({ status: 429 }).code, 'VOICE_QUOTA');
  assert.equal(classifyVoiceFailure('RESOURCE_EXHAUSTED').retryable, false);
  assert.equal(classifyVoiceFailure('API key invalid').code, 'VOICE_AUTH');
  assert.equal(classifyVoiceFailure('socket network error').code, 'VOICE_NETWORK');
});
test('voice fallback only tries configured candidates once and never retries rejected credentials', async () => {
  const calls: string[] = []; const notices: string[] = [];
  const result = await connectVoice(['first', 'first', 'second', 'third'], async model => { calls.push(model); if (model === 'first') throw { status: 429 }; return 'session'; }, (from, to) => notices.push(`${from}:${to}`));
  assert.deepEqual(calls, ['first', 'second']); assert.equal(result.model, 'second'); assert.equal(notices.length, 1);
  calls.length = 0;
  await assert.rejects(connectVoice(['first', 'second'], async model => { calls.push(model); throw { status: 401 }; }, () => {}));
  assert.deepEqual(calls, ['first']);
});
test('local avatar commands understand English, Hindi and Hinglish without matching unrelated tasks', () => {
  assert.deepEqual(parseCharacterCommand('MYRAA, wave at me.'), { action: 'wave' });
  assert.deepEqual(parseCharacterCommand('मायरा, हाथ हिलाओ'), { action: 'wave' });
  assert.deepEqual(parseCharacterCommand('idhar aao'), { action: 'come_here' });
  assert.deepEqual(parseCharacterCommand('MYRAA, sit on the right side of my desktop.'), { action: 'sit', side: 'right' });
  assert.equal(parseCharacterCommand('Explain wave physics'), null);
  assert.equal(parseCharacterCommand('Open Notepad and then dance'), null);
});
test('HTTP and socket origins reject other sites, lookalike hosts and other local apps', () => {
  assert.equal(isLocalAppOrigin('http://127.0.0.1:47130', 47130), true);
  assert.equal(isLocalAppOrigin('http://localhost:47130', 47130), true);
  assert.equal(isLocalAppOrigin(undefined, 47130), true);
  for (const origin of ['null', 'https://evil.example', 'http://localhost:3000', 'http://localhost.evil.example:47130', 'http://127.0.0.1:47130/path']) assert.equal(isLocalAppOrigin(origin, 47130), false, origin);
  assert.equal(isLocalAppHost('evil.example:47130', 47130), false);
  assert.equal(isLocalAppHost('127.0.0.1:47130', 47130), true);
});

test('wave bends elbow before shoulder lift, never extends a straight horizontal arm', () => {
  const wave = BEHAVIOURS.find(b => b.name === 'wave')!;
  const arm = new THREE.Bone(); arm.name = 'arm';
  const elbow = new THREE.Bone(); elbow.name = 'elbow'; arm.add(elbow);
  const pose = new PoseBuffer({ bones: [arm, elbow], boneIndexByName: new Map([['arm', 0], ['elbow', 1]]) } as never);
  pose.registerAll(['arm', 'elbow']); pose.bakeIntoRest('arm', 0, 0, -Math.PI * 70 / 180);
  for (let frame = 0; frame <= 240; frame++) {
    pose.begin(); wave.pose!(frame / 240, 1, pose, { armL: 'arm', elbowL: 'elbow' } as never); pose.apply();
    const upper = new THREE.Vector3(1, 0, 0).applyQuaternion(arm.quaternion);
    assert.ok(upper.y < -0.5, `upper arm must stay below shoulder at frame ${frame}`);
    assert.ok([...arm.quaternion, ...elbow.quaternion].every(Number.isFinite));
  }
});
test('interrupted gestures preserve the previous pose and blend smoothly', () => {
  const bone = new THREE.Bone(); bone.name = 'arm';
  const pose = new PoseBuffer({ bones: [bone], boneIndexByName: new Map([['arm', 0]]) } as never); pose.register('arm');
  const director = new BehaviourDirector({ intervalMin: 50, intervalMax: 60, busyIntervalScale: 2, noRepeatWindow: 2 } as never,
    [{ name: 'up', duration: 10, weight: 1, pose: (_t, w, p) => p.addEuler('arm', 0, 0, 1, w) }, { name: 'down', duration: 10, weight: 1, pose: (_t, w, p) => p.addEuler('arm', 0, 0, -1, w) }]);
  const step = () => { pose.begin(); director.update({ delta: 1 / 60, activity: 'idle', pose, bones: {} as never }); pose.apply(); };
  director.setAutoPick(false); director.trigger('up'); for (let i = 0; i < 60; i++) step();
  const before = bone.quaternion.clone(); director.trigger('down'); step();
  assert.ok(before.angleTo(bone.quaternion) < 0.1, 'interruption must not snap back to rest');
  for (let i = 0; i < 60; i++) step();
  assert.ok(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, 1), -1).angleTo(bone.quaternion) < 0.01);
});
test('model cleanup releases shared geometry once and all outline/sphere resources', () => {
  const group = new THREE.Group(); const geometry = new THREE.BoxGeometry(); const texture = new THREE.Texture();
  const main = new THREE.MeshBasicMaterial({ map: texture }); const outline = new THREE.MeshBasicMaterial();
  const sphere = new THREE.Texture(); main.userData.animeUniforms = { uSphereMap: { value: sphere } };
  group.add(new THREE.Mesh(geometry, main), new THREE.Mesh(geometry, outline));
  let disposed = 0; for (const resource of [geometry, texture, main, outline, sphere]) resource.addEventListener('dispose' as never, () => disposed++);
  disposeModelTree(group); assert.equal(disposed, 5);
});
test('an explicit wave survives a happy expression and a speaking activity', () => {
  const bone = new THREE.Bone(); bone.name = 'arm';
  const pose = new PoseBuffer({ bones: [bone], boneIndexByName: new Map([['arm', 0]]) } as never); pose.register('arm');
  const director = new BehaviourDirector({ intervalMin: 50, intervalMax: 60, busyIntervalScale: 2, noRepeatWindow: 2 } as never);
  director.trigger('wave'); director.setAutoPick(false);
  for (let i = 0; i < 60; i++) { pose.begin(); director.update({ delta: 1 / 60, activity: 'talking', pose, bones: { armL: 'arm' } as never }); pose.apply(); }
  assert.equal(director.currentName, 'wave');
  assert.ok(bone.quaternion.angleTo(new THREE.Quaternion()) > 0.1);
});
