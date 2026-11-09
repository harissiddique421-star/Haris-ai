/**
 * Multi-step tasks (Stage 5). Gemini plans a goal into short steps and does each one with
 * mobile_action as usual; this tracker is the phone's record of that plan. It keeps the steps and
 * their results, tells Gemini which step is next, stops at a failure until the user says retry or
 * skip, and refuses a second task while one is running, so two plans never fight over the phone.
 *
 * It does not run anything by itself and adds no permission: every step still passes the normal
 * security, autonomy and confirmation checks. A step that needs confirmation waits for the user.
 */
export type StepState = 'pending' | 'running' | 'done' | 'failed' | 'skipped';
export interface TaskStep { text: string; state: StepState; note: string; attempts: number; }
export interface Task { id: string; goal: string; steps: TaskStep[]; state: 'running' | 'done' | 'failed' | 'cancelled'; started: number; updated: number; }

export const MAX_STEPS = 12, MAX_ATTEMPTS = 3;
const clip = (s: unknown, n: number) => String(s ?? '').replace(/\s+/g, ' ').trim().slice(0, n);

export class TaskTracker {
  task: Task | null = null;
  history: Task[] = [];
  constructor(private now: () => number = Date.now) {}

  private current(): TaskStep | undefined { return this.task?.steps.find(s => s.state === 'pending' || s.state === 'running' || s.state === 'failed'); }
  private index(step?: TaskStep) { return step && this.task ? this.task.steps.indexOf(step) + 1 : 0; }

  private next(lead: string): any {
    const t = this.task!; const step = this.current();
    if (!step) {
      t.state = 'done'; t.updated = this.now(); this.finish();
      return { status: 'done', message: `${lead} All ${t.steps.length} steps of "${t.goal}" are finished. Tell the user in one short line what was done.`, task: this.view(t) };
    }
    if (step.state === 'failed') return { status: 'failed', message: `${lead} Step ${this.index(step)} ("${step.text}") failed: ${step.note || 'no reason given'}. Tell the user and ask whether to retry, skip it or cancel the task.`, task: this.view(t) };
    step.state = 'running'; step.attempts++;
    return { status: 'next', step: this.index(step), step_text: step.text, message: `${lead} Now do step ${this.index(step)} of ${t.steps.length}: "${step.text}". Do it with mobile_action, then call task with command step_done and step ${this.index(step)} (or step_failed with the reason). Keep spoken updates short.`, task: this.view(t) };
  }

  private finish() { if (this.task) { this.history.unshift(this.task); this.history = this.history.slice(0, 10); } this.task = null; }

  start(goal: string, steps: unknown): any {
    const g = clip(goal, 160);
    const list = (Array.isArray(steps) ? steps : []).map(s => clip(s, 200)).filter(Boolean);
    if (!g) return { status: 'error', message: 'Say what the goal of the task is.' };
    if (list.length < 2) return { status: 'error', message: 'A task needs at least two steps. For one action just do it directly.' };
    if (list.length > MAX_STEPS) return { status: 'error', message: `Keep a task to ${MAX_STEPS} steps or fewer. Split it into smaller tasks.` };
    if (this.task) return { status: 'busy', message: `Another task is still running ("${this.task.goal}", step ${this.index(this.current())} of ${this.task.steps.length}). Ask the user whether to cancel it first.`, task: this.view(this.task) };
    const at = this.now();
    this.task = { id: 't' + at.toString(36), goal: g, steps: list.map(text => ({ text, state: 'pending', note: '', attempts: 0 })), state: 'running', started: at, updated: at };
    return this.next(`Task "${g}" planned with ${list.length} steps.`);
  }

  stepDone(note?: string, number?: unknown): any {
    const step = this.current();
    if (!this.task || !step) return { status: 'no_task', message: 'No task is running.' };
    // A repeated step_done for a step already finished must not skip the next one.
    if (number != null && Number(number) !== this.index(step)) return { status: 'next', step: this.index(step), step_text: step.text, message: `Step ${number} was already recorded. The current step is ${this.index(step)}: "${step.text}".`, task: this.view(this.task) };
    if (step.state !== 'running') return { status: 'failed', message: `Step ${this.index(step)} failed earlier. Ask the user whether to retry, skip it or cancel.`, task: this.view(this.task) };
    step.state = 'done'; step.note = clip(note, 200); this.task.updated = this.now();
    return this.next(`Step ${this.index(step)} done.`);
  }

  stepFailed(reason?: string, number?: unknown): any {
    const step = this.current();
    if (!this.task || !step) return { status: 'no_task', message: 'No task is running.' };
    if (number != null && Number(number) !== this.index(step)) return { status: 'next', step: this.index(step), step_text: step.text, message: `Step ${number} was already recorded. The current step is ${this.index(step)}: "${step.text}".`, task: this.view(this.task) };
    step.state = 'failed'; step.note = clip(reason, 200) || 'no reason given'; this.task.updated = this.now();
    return this.next('');
  }

  retry(): any {
    const step = this.current();
    if (!this.task || !step) return { status: 'no_task', message: 'No task is running.' };
    if (step.state !== 'failed') return { status: 'next', step: this.index(step), step_text: step.text, message: `Step ${this.index(step)} is not failed; carry on with "${step.text}".`, task: this.view(this.task) };
    // A step that keeps failing ends the task instead of looping.
    if (step.attempts >= MAX_ATTEMPTS) {
      this.task.state = 'failed'; this.task.updated = this.now(); const t = this.task; this.finish();
      return { status: 'stopped', message: `Step "${step.text}" failed ${step.attempts} times, so the task stopped. Tell the user why (${step.note}) and suggest what they can do.`, task: this.view(t) };
    }
    step.state = 'pending';
    return this.next('Trying again.');
  }

  skip(): any {
    const step = this.current();
    if (!this.task || !step) return { status: 'no_task', message: 'No task is running.' };
    step.state = 'skipped'; this.task.updated = this.now();
    return this.next(`Skipped step ${this.index(step)}.`);
  }

  cancel(): any {
    if (!this.task) return { status: 'no_task', message: 'No task is running.' };
    const t = this.task; t.state = 'cancelled'; t.updated = this.now();
    for (const s of t.steps) if (s.state === 'pending' || s.state === 'running') s.state = 'skipped';
    this.finish();
    return { status: 'cancelled', message: `Cancelled "${t.goal}". Steps already done stay done; nothing else will run.`, task: this.view(t) };
  }

  status(): any {
    if (!this.task) {
      const last = this.history[0];
      return { status: 'no_task', message: last ? `No task is running. The last one, "${last.goal}", ended ${last.state}.` : 'No task is running.', last: last ? this.view(last) : null };
    }
    const step = this.current();
    return { status: 'running', message: `Task "${this.task.goal}": step ${this.index(step)} of ${this.task.steps.length} (${step?.state}).`, task: this.view(this.task) };
  }

  /** Handles one task call from Gemini. */
  handle(args: any): any {
    const c = String(args?.command || 'status').toLowerCase();
    if (c === 'start' || c === 'plan') return this.start(args?.goal ?? args?.text, args?.steps);
    if (c === 'step_done' || c === 'done') return this.stepDone(args?.text, args?.step);
    if (c === 'step_failed' || c === 'failed') return this.stepFailed(args?.text, args?.step);
    if (c === 'retry') return this.retry();
    if (c === 'skip') return this.skip();
    if (c === 'cancel' || c === 'stop') return this.cancel();
    return this.status();
  }

  view(t: Task) { return { goal: t.goal, state: t.state, steps: t.steps.map((s, i) => `${i + 1}. ${s.text} [${s.state}${s.note ? ': ' + s.note : ''}]`) }; }

  save(): string { return JSON.stringify({ task: this.task, history: this.history }); }
  load(raw: string | null) {
    try { const v = JSON.parse(raw || '{}'); this.task = v.task && Array.isArray(v.task.steps) ? v.task : null; this.history = Array.isArray(v.history) ? v.history.slice(0, 10) : []; } catch { this.task = null; this.history = []; }
  }
}
