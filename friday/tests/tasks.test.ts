import test from 'node:test';
import assert from 'node:assert/strict';
import { TaskTracker, MAX_ATTEMPTS } from '../src/mobile/tasks';
import { agentFor, Orchestrator } from '../src/mobile/agents';

const steps = ['WhatsApp par Ali ko message karo: kal milte hain', '5 baje ka reminder lagao', 'Spotify chalao'];

test('a task walks through its steps in order and finishes', () => {
  const t = new TaskTracker(() => 1000);
  let r = t.handle({ command: 'start', goal: 'Evening setup', steps });
  assert.equal(r.status, 'next'); assert.equal(r.step, 1); assert.equal(r.step_text, steps[0]);
  r = t.handle({ command: 'step_done', step: 1 }); assert.equal(r.step, 2);
  r = t.handle({ command: 'step_done', step: 2 }); assert.equal(r.step, 3);
  r = t.handle({ command: 'step_done', step: 3 }); assert.equal(r.status, 'done');
  assert.equal(t.task, null); assert.equal(t.history[0].state, 'done');
});

test('a repeated step_done does not skip the next step', () => {
  const t = new TaskTracker(); t.start('Goal', steps);
  t.handle({ command: 'step_done', step: 1 });
  const r = t.handle({ command: 'step_done', step: 1 });
  assert.equal(r.step, 2); assert.equal(t.task!.steps[1].state, 'running');
});

test('a failed step waits for retry, skip or cancel, and stops after repeated failures', () => {
  const t = new TaskTracker(); t.start('Goal', steps);
  let r = t.handle({ command: 'step_failed', step: 1, text: 'contact not found' });
  assert.equal(r.status, 'failed'); assert.match(r.message, /retry, skip it or cancel/);
  assert.equal(t.handle({ command: 'step_done', step: 1 }).status, 'failed');
  for (let i = 1; i < MAX_ATTEMPTS; i++) { r = t.retry(); assert.equal(r.status, 'next'); t.stepFailed('again'); }
  r = t.retry(); assert.equal(r.status, 'stopped'); assert.equal(t.task, null);
  const s = new TaskTracker(); s.start('Goal', steps); s.stepFailed('x');
  assert.equal(s.skip().step, 2); assert.equal(s.task!.steps[0].state, 'skipped');
});

test('only one task at a time; cancel stops the rest', () => {
  const t = new TaskTracker(); t.start('First', steps);
  assert.equal(t.start('Second', steps).status, 'busy');
  const r = t.cancel(); assert.equal(r.status, 'cancelled');
  assert.ok(t.history[0].steps.every(s => s.state === 'skipped'));
  assert.equal(t.start('Second', steps).status, 'next');
});

test('bad plans are refused and the plan survives a reload', () => {
  const t = new TaskTracker();
  assert.equal(t.start('', steps).status, 'error');
  assert.equal(t.start('One step', ['battery batao']).status, 'error');
  assert.equal(t.start('Too long', Array.from({ length: 13 }, (_, i) => 'step ' + i)).status, 'error');
  t.start('Goal', steps); t.stepDone(undefined, 1);
  const u = new TaskTracker(); u.load(t.save());
  assert.equal(u.status().status, 'running'); assert.equal(u.handle({ command: 'step_done', step: 2 }).step, 3);
  const bad = new TaskTracker(); bad.load('not json'); assert.equal(bad.task, null);
});

test('task calls belong to the planning agent and pass even in assisted mode or with the phone prompting', async () => {
  assert.equal(agentFor('task'), 'planning');
  const o = new Orchestrator(async () => ({ status: 'next' }));
  o.policy = { autonomy: 'assisted', paused: false };
  o.phonePrompted();
  assert.equal((await o.dispatch('mobile_action', { action: 'task', command: 'status' })).status, 'next');
});
