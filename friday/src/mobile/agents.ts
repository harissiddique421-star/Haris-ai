/**
 * FRIDAY's internal agents. Gemini (the core FRIDAY agent) decides what to do and calls
 * mobile_action; this orchestrator hands each action to the specialised agent that owns it,
 * runs the Security agent's check first, stops duplicate requests, turns failures into a
 * structured result instead of a crash, and times every agent. The user only ever talks to
 * FRIDAY: agent names appear in Settings diagnostics, never in replies.
 */
export type AgentName = 'communication' | 'media' | 'device' | 'accessibility' | 'memory' | 'document' | 'research' | 'health' | 'core';

const OWNERS: Record<AgentName, string[]> = {
  communication: ['whatsapp_message', 'whatsapp_call', 'call', 'send_sms', 'email', 'open_contact', 'open_dialer', 'read_notifications', 'answer_call', 'reject_call', 'silence_call', 'who_is_calling', 'missed_calls', 'set_call_announce', 'set_driving_mode', 'reply_message', 'emergency', 'emergency_contact'],
  media: ['search_youtube', 'play_music', 'resume_video', 'media'],
  device: ['torch', 'volume', 'brightness', 'ringer_mode', 'battery', 'open_settings', 'set_alarm', 'set_timer', 'stop_timer', 'show_timers', 'show_alarms', 'dismiss_alarm', 'clock', 'open_app', 'navigate', 'meeting_mode', 'sleep', 'set_wake_word'],
  accessibility: ['phone_control', 'read_screen', 'tap', 'type_text', 'scroll', 'copy_text'],
  memory: ['remember', 'forget', 'list_memories', 'set_persona', 'set_style', 'set_mode', 'save_routine', 'run_routine', 'list_routines', 'delete_routine', 'rename_routine', 'action_history'],
  document: ['make_document'],
  research: ['search_web'],
  health: ['health_check', 'network_status', 'set_proactive'],
  core: ['briefing', 'set_reminder', 'care_reminder', 'list_reminders', 'delete_reminder', 'reminder_done', 'reminder_skip', 'add_event', 'upcoming_events', 'prayer_times', 'qibla', 'prayer_reminders'],
};
const OWNER = new Map<string, AgentName>();
for (const [agent, actions] of Object.entries(OWNERS) as [AgentName, string[]][]) for (const a of actions) OWNER.set(a, agent);
export function agentFor(action: string): AgentName { return OWNER.get(action) ?? 'core'; }

/** Actions that reach other people, change calls or remove data: only on the user's own words. */
const HIGH_RISK = new Set(['whatsapp_message', 'whatsapp_call', 'call', 'send_sms', 'email', 'answer_call', 'reject_call', 'forget', 'delete_reminder', 'delete_routine', 'emergency', 'emergency_contact', 'reply_message']);
/** Actions that change no screen: a routine can run the next step right away. */
export const QUICK = new Set(['torch', 'volume', 'brightness', 'ringer_mode', 'battery', 'media', 'clock', 'remember', 'list_memories', 'set_alarm', 'set_timer', 'set_reminder', 'network_status', 'meeting_mode', 'missed_calls', 'who_is_calling', 'silence_call']);

export interface AgentStats { runs: number; failures: number; totalMs: number; lastMs: number; }
export type Runner = (name: string, args: unknown) => Promise<any>;

const FAILED = new Set(['error', 'failed', 'timeout', 'unavailable', 'unsupported']);

export class Orchestrator {
  readonly stats = new Map<AgentName, AgentStats>();
  private inFlight = new Map<string, Promise<any>>();
  /** Time the user last spoke, and the time the phone itself last prompted Gemini (check-in, call or message notice). */
  private userSpokeAt = 0;
  private phonePromptAt = 0;

  constructor(private run: Runner) {}

  userSpoke() { this.userSpokeAt = Date.now(); }
  phonePrompted() { this.phonePromptAt = Date.now(); }

  /**
   * Security agent. A risky action is allowed when it was typed, when it carries the phone's own
   * confirmation token, or when the user has spoken since the phone last prompted Gemini by itself.
   * So a quiet check-in, an incoming-call notice or a message read aloud can never make FRIDAY
   * message, call or delete anything on its own.
   */
  private securityCheck(action: string, args: any, typed: boolean): any | null {
    if (!HIGH_RISK.has(action) || typed) return null;
    if (args && typeof args === 'object' && args.confirm_token) return null;
    if (this.phonePromptAt > 0 && this.userSpokeAt < this.phonePromptAt)
      return { status: 'needs_user', message: 'This request did not come from the user. Ask the user what they want first and act only on their clear answer.' };
    return null;
  }

  async dispatch(name: string, args: any, typed = false): Promise<any> {
    const action = String(args?.action || '');
    const agent = agentFor(action);
    const blocked = this.securityCheck(action, args, typed);
    if (blocked) { this.record(agent, 0, true); return blocked; }
    // The same request already running (Gemini sometimes repeats a call with a new id): share its result.
    const key = name + ':' + JSON.stringify(args ?? {});
    const running = this.inFlight.get(key);
    if (running) return running;
    const started = performance.now();
    const work = (async () => {
      try {
        const result = await this.run(name, args);
        this.record(agent, performance.now() - started, FAILED.has(String(result?.status)));
        return result ?? { status: 'error', message: 'The phone returned no result.' };
      } catch (error: any) {
        // An agent failure never takes FRIDAY down: it becomes a normal result Gemini can explain.
        this.record(agent, performance.now() - started, true);
        return { status: 'error', message: 'That action failed on the phone. Say so briefly and offer to try again.' };
      } finally {
        this.inFlight.delete(key);
      }
    })();
    this.inFlight.set(key, work);
    return work;
  }

  private record(agent: AgentName, ms: number, failed: boolean) {
    const s = this.stats.get(agent) ?? { runs: 0, failures: 0, totalMs: 0, lastMs: 0 };
    s.runs++; if (failed) s.failures++; s.totalMs += ms; s.lastMs = Math.round(ms);
    this.stats.set(agent, s);
  }

  /** One line per agent for Settings diagnostics. */
  summary(): string {
    const rows = [...this.stats.entries()].map(([a, s]) => `${a} ${s.runs} run${s.runs === 1 ? '' : 's'}, avg ${Math.round(s.totalMs / Math.max(1, s.runs))} ms, last ${s.lastMs} ms${s.failures ? `, ${s.failures} failed` : ''}`);
    return rows.length ? rows.join(' · ') : 'no actions yet';
  }
}
