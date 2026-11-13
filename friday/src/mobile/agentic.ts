/**
 * Agentic voice mode: instead of Gemini Live, FRIDAY listens with Android's speech recognizer, thinks with the
 * brain the user chose (DeepSeek, OpenRouter, OpenAI, Groq or Gemini) and speaks with the phone's voice.
 * One command can hold many tasks: the brain calls mobile_action as often as it needs (open the app, read the
 * screen, tap, read again to check, ...), each result goes back to it, and it speaks only when everything is
 * done or it needs the user. The user can say "ruko" / "stop" any time.
 *
 * Two small checks run on the phone before a sentence reaches the brain:
 *  - turn detection: a sentence that stops on "aur", "phir", "ke", "and"... is unfinished, so FRIDAY waits for the rest;
 *  - addressee detection: a sentence clearly meant for someone else in the room is not answered.
 */
import { MOBILE_TOOL } from './actions';

export type AgentState = 'listening' | 'thinking' | 'acting' | 'speaking' | 'stopped';
export interface StepCall { id: string; name: string; args: any; }
export interface StepResult { text: string; calls: StepCall[]; assistant: any; model?: string; provider?: string; note?: string; ms?: number; }
export interface AgentDeps {
  step(messages: any[], tools: any[]): Promise<StepResult>;
  cancelStep(): void;
  dispatch(name: string, args: any): Promise<any>;
  speak(text: string): Promise<void>;
  ear(command: 'start' | 'stop' | 'pause' | 'resume'): string;
  instruction(): string;
  addresseeMode(): 'smart' | 'all';
  onState(state: AgentState, detail?: string): void;
  onUser(text: string): void;
  onReply(text: string): void;
  onNotice(text: string): void;
  onIgnored(text: string): void;
  onAction?(name: string, args: any, result: any): void;
}

/** The mobile_action tool in the JSON Schema form OpenAI-compatible providers use (lower-case types). */
export function openAiSchema(schema: any): any {
  if (Array.isArray(schema)) return schema.map(openAiSchema);
  if (!schema || typeof schema !== 'object') return schema;
  const out: any = {};
  for (const [key, value] of Object.entries(schema)) {
    if (key === 'type' && typeof value === 'string') out.type = value.toLowerCase();
    else if (key === 'properties' && value && typeof value === 'object') out.properties = Object.fromEntries(Object.entries(value as any).map(([k, v]) => [k, openAiSchema(v)]));
    else if (key === 'items') out.items = openAiSchema(value);
    else out[key] = value;
  }
  return out;
}
export function agentTools() {
  return [{ type: 'function', function: { name: MOBILE_TOOL.name, description: MOBILE_TOOL.description, parameters: openAiSchema(MOBILE_TOOL.parameters) } }];
}

/** What agentic mode adds to FRIDAY's usual instructions. */
export const AGENTIC_RULES = `
AGENTIC MODE: you hear the user through speech-to-text (words can be misheard; ask when a name or number looks wrong) and your reply is read aloud by the phone, so answer in plain spoken sentences: no Markdown, lists, emojis or links, normally one to three short sentences in the user's language (Roman Urdu when they speak it).
You can do many things from one command. Plan the steps yourself and call mobile_action once per step, as many times as needed, without asking between steps. After every action that changes the screen (open_app, tap, type_text, scroll, phone_control) call read_screen and check that it really worked before the next step; if a step failed, try another way (another label, scroll, back, open the app again) up to three times, then tell the user exactly what did not work. Never say you did something until a tool result shows it. When reading data from an app (numbers, posts, insights), read it from read_screen results only, never from memory or guesses.
Speak only when all the work is done, when you need the user's answer or confirmation, or to report a problem. Keep confirmations (needs_confirmation) exactly as the rules above say.
A message starting with [Phone] comes from the phone itself, not the user.`;

const UNFINISHED = new Set(['aur', 'or', 'and', 'phir', 'fir', 'then', 'ke', 'ki', 'ka', 'ko', 'se', 'mein', 'me', 'main', 'par', 'pe', 'to', 'toh', 'ya', 'lekin', 'magar', 'but', 'because', 'kyunki', 'kyonki', 'jo', 'jab', 'agar', 'if', 'with', 'for', 'the', 'a', 'an', 'my', 'mera', 'meri', 'mere', 'apna', 'apni', 'is', 'us', 'ye', 'yeh', 'wo', 'woh', 'that', 'this', 'zara', 'ek', 'aik', 'of', 'in', 'on', 'at', 'about', 'wala', 'wali', 'wale', 'jaise', 'like', 'also', 'bhi', 'saath', 'sath', 'liye', 'lie']);

/**
 * Has the user finished the sentence? "wait" when it stops on a joining word ("aur", "phir", "ke", "and"),
 * on a comma, or when only FRIDAY's name was said; FRIDAY then waits a little for the rest.
 */
export function turnState(text: string): 'done' | 'wait' {
  const t = text.trim().toLowerCase();
  if (!t) return 'wait';
  if (/[,;:]$|\.\.\.$|…$/.test(t)) return 'wait';
  const words = t.replace(/[.!?]+$/, '').split(/\s+/);
  if (words.length <= 2 && /^(hey |hi |ok |okay |sun |suno )?(friday|fraide|फ्राइडे)$/.test(words.join(' '))) return 'wait';
  if (/[.!?]$/.test(t)) return 'done';
  return UNFINISHED.has(words[words.length - 1]) ? 'wait' : 'done';
}

const NAME = /\b(friday|fraide|fridey|फ्राइडे)\b/i;
const ASK = /\b(kholo|khol|open|bhejo|bhej|send|call|karo|kardo|kar do|krdo|kro|chalao|chala|play|lagao|laga|set|search|dhoondo|dhundo|talash|batao|bata|btao|tell|check|dikhao|dikha|show|likho|likh|write|banao|bana|make|band|stop|pause|resume|ruko|remind|yaad|message|msg|whatsapp|youtube|instagram|insta|facebook|volume|torch|alarm|timer|weather|mausam|news|khabar|research|report|read|parho|padho|suno|sunao|translate|tarjuma|scroll|tap|back|home|screenshot|note|kharcha|expense|reminder|namaz|qibla|time|date|battery|wifi|bluetooth|kya|kaise|kese|kab|kitna|kitni|kitne|kaun|kon|kahan|kahaan|kyun|kyu|what|how|when|who|where|why|which|can you|could you|please|plz)\b/i;
const OTHER_PERSON = /^(ammi|ammy|abbu|abu|mama|mummy|papa|bhai|bhaiya|baji|appi|api|beta|beti|yaar|bro|dost|uncle|aunty|bhabhi|dadi|nani|chacha|khala)\b/i;

/**
 * Was this said to FRIDAY? Her name always counts; so does an answer within a few seconds after she spoke,
 * and a command or a question. A sentence that starts by calling someone else ("ammi ...", "bhai ...") or
 * that has no command or question at all (chatter, a TV) is treated as meant for someone else.
 */
export function addressee(text: string, ctx: { sinceFridayMs: number; mode: 'smart' | 'all'; working?: boolean }): 'friday' | 'other' {
  if (ctx.mode === 'all') return 'friday';
  const t = text.trim();
  if (!t) return 'other';
  if (NAME.test(t)) return 'friday';
  if (/^(ruko|ruk jao|bas|stop|cancel|band karo|chup)\b/i.test(t)) return 'friday';
  if (ctx.sinceFridayMs < 15_000) return 'friday';
  if (OTHER_PERSON.test(t)) return 'other';
  if (/^(haan|han|ha|nahi|nahin|na|no|yes|ok|okay|theek|thik|acha|accha|jee|ji)\b/i.test(t)) return ctx.sinceFridayMs < 30_000 || ctx.working ? 'friday' : 'other';
  return ASK.test(t) ? 'friday' : 'other';
}

export const STOP_WORDS = /^(ruko|ruk jao|bas|bas karo|stop|cancel|band karo|chup|rehne do|rahne do)[.!]?$/i;

/** Keeps the conversation short enough: the system message, then the last turns, never starting on a tool result. */
export function trimHistory(messages: any[], max = 60): any[] {
  if (messages.length <= max) return messages;
  const system = messages[0]?.role === 'system' ? [messages[0]] : [];
  let start = messages.length - (max - system.length);
  while (start < messages.length && messages[start].role !== 'user') start++;
  return [...system, ...messages.slice(start)];
}

/** Every tool call needs its result right after it (providers refuse otherwise); a cancelled one gets "cancelled". */
export function repairHistory(messages: any[]): any[] {
  const out: any[] = [];
  for (let i = 0; i < messages.length; i++) {
    const m = messages[i];
    if (m.role === 'tool') continue;
    out.push(m);
    const calls: any[] = m.role === 'assistant' && Array.isArray(m.tool_calls) ? m.tool_calls : [];
    if (!calls.length) continue;
    const results = new Map<string, any>();
    for (let j = i + 1; j < messages.length && messages[j].role === 'tool'; j++) results.set(messages[j].tool_call_id, messages[j]);
    for (const c of calls) out.push(results.get(c.id) || { role: 'tool', tool_call_id: c.id, name: c.function?.name || 'mobile_action', content: '{"status":"cancelled","message":"The user stopped this."}' });
  }
  return out;
}

/** A tool result small enough to send back (screens can be long). */
export function toolContent(result: any): string {
  let s: string;
  try { s = JSON.stringify(result ?? {}); } catch { s = '{"status":"error"}'; }
  return s.length > 6000 ? s.slice(0, 6000) + '…(cut)' : s;
}

export class AgenticSession {
  private messages: any[] = [];
  private pending = '';
  private waitTimer: ReturnType<typeof setTimeout> | null = null;
  private queue: string[] = [];
  private busy = false;
  private run = 0;
  private stopped = true;
  private lastSpokeAt = 0;
  readonly maxSteps = 30;
  private readonly tools = agentTools();

  constructor(private deps: AgentDeps) {}

  active() { return !this.stopped; }
  isBusy() { return this.busy; }

  start(greeting?: string): string {
    const why = this.deps.ear('start');
    if (why !== 'ok') { this.stopped = true; return why; }
    this.stopped = false; this.run++; this.queue = []; this.pending = '';
    this.messages = [{ role: 'system', content: (this.deps.instruction() || 'You are FRIDAY, a helpful Android assistant.') + AGENTIC_RULES }];
    this.deps.onState('listening');
    if (greeting) void this.say(greeting);
    return 'ok';
  }

  stop() {
    if (this.stopped) return;
    this.stopped = true; this.run++; this.queue = []; this.pending = '';
    if (this.waitTimer) { clearTimeout(this.waitTimer); this.waitTimer = null; }
    this.deps.cancelStep(); this.deps.ear('stop'); this.busy = false;
    this.deps.onState('stopped');
  }

  /** Words from the recognizer: partial (still speaking) or final (one sentence). */
  heard(kind: string, text: string) {
    if (this.stopped) return;
    if (kind === 'partial') { if (this.waitTimer) { clearTimeout(this.waitTimer); this.waitTimer = null; } return; }
    if (kind !== 'final' || !text.trim()) return;
    this.pending = this.pending ? `${this.pending} ${text.trim()}` : text.trim();
    if (this.waitTimer) { clearTimeout(this.waitTimer); this.waitTimer = null; }
    if (turnState(this.pending) === 'wait') { this.waitTimer = setTimeout(() => { this.waitTimer = null; this.commit(); }, 3000); return; }
    this.commit();
  }

  /** A message from the phone itself ([Phone] ...) or typed text: always for FRIDAY. */
  sendText(text: string) { if (!this.stopped && text.trim()) this.accept(text.trim(), true); }

  private commit() {
    const text = this.pending.trim(); this.pending = '';
    if (!text) return;
    const who = addressee(text, { sinceFridayMs: Date.now() - this.lastSpokeAt, mode: this.deps.addresseeMode(), working: this.busy });
    if (who === 'other') { this.deps.onIgnored(text); return; }
    this.accept(text, false);
  }

  private accept(text: string, fromPhone: boolean) {
    if (!fromPhone) this.deps.onUser(text);
    if (!fromPhone && STOP_WORDS.test(text) && this.busy) {
      this.run++; this.deps.cancelStep(); this.queue = []; this.busy = false;
      this.messages.push({ role: 'user', content: text }, { role: 'assistant', content: 'Theek hai, ruk gayi.' });
      void this.say('Theek hai, ruk gayi.');
      return;
    }
    if (this.busy) { this.queue.push(text); return; }
    void this.work(text);
  }

  private async work(text: string) {
    const mine = ++this.run;
    this.busy = true;
    this.messages.push({ role: 'user', content: text });
    let finalText = '';
    try {
      for (let step = 0; step < this.maxSteps; step++) {
        if (mine !== this.run || this.stopped) return;
        // A new instruction while working is added before the next step, so the brain can follow it.
        while (this.queue.length) this.messages.push({ role: 'user', content: this.queue.shift()! });
        this.deps.onState('thinking');
        this.messages = trimHistory(repairHistory(this.messages));
        const r = await this.deps.step(this.messages, this.tools);
        if (mine !== this.run || this.stopped) return;
        if (r.note) this.deps.onNotice(r.note);
        this.messages.push(r.assistant || { role: 'assistant', content: r.text || '' });
        if (!r.calls?.length) { finalText = r.text || ''; break; }
        if (r.text) this.deps.onNotice(r.text);
        for (const call of r.calls) {
          if (mine !== this.run || this.stopped) return;
          this.deps.onState('acting', String(call.args?.action || call.name));
          let result: any;
          try { result = call.name === 'mobile_action' ? await this.deps.dispatch(call.name, call.args || {}) : { status: 'unsupported', message: 'Only mobile_action is available.' }; }
          catch (e) { result = { status: 'error', message: String((e as Error)?.message || e) }; }
          if (mine !== this.run || this.stopped) return;
          this.deps.onAction?.(String(call.args?.action || ''), call.args, result);
          this.messages.push({ role: 'tool', tool_call_id: call.id, name: call.name, content: toolContent(result) });
          if (result?.sleep) { finalText = String(result.message || ''); this.messages.push({ role: 'assistant', content: finalText }); await this.say(finalText); this.stop(); return; }
        }
        if (step === this.maxSteps - 1) finalText = 'Bohat saare steps ho gaye, main yahan ruk rahi hoon. Batayein aage kya karun.';
      }
    } catch (e) {
      if (mine !== this.run || this.stopped) return;
      finalText = String((e as Error)?.message || e || 'Kuch masla ho gaya.');
      this.deps.onNotice(finalText);
    } finally {
      if (mine === this.run) this.busy = false;
    }
    if (mine !== this.run || this.stopped) return;
    if (finalText) { this.deps.onReply(finalText); await this.say(finalText); }
    if (mine === this.run && !this.stopped) {
      this.deps.onState('listening');
      if (this.queue.length) void this.work(this.queue.shift()!);
    }
  }

  private async say(text: string) {
    const spoken = text.replace(/[*_#`>|]/g, '').replace(/\s+/g, ' ').trim();
    if (!spoken || this.stopped) return;
    this.deps.onState('speaking');
    this.deps.ear('pause');
    try { await this.deps.speak(spoken); }
    finally { this.lastSpokeAt = Date.now(); if (!this.stopped) { this.deps.ear('resume'); if (!this.busy) this.deps.onState('listening'); } }
  }
}
