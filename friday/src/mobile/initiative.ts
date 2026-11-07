/**
 * Active conversation engine for Live calls: decides when FRIDAY may speak first and what kind of
 * opening or follow-up to give. Runs on the phone and makes no AI calls of its own: it only
 * chooses whether to send one short [Phone] hint to the Live session that is already open, and
 * the model writes the actual words from the conversation and its saved memory.
 *
 *  - ConversationContextManager (ContextualTopicManager): what the user talked about in this call,
 *    their mood, short answers, and whether they asked for silence or ended the chat. It only reads
 *    Gemini's transcript of the user, which with Voice ID on contains only the owner's speech, so
 *    background voices never feed it.
 *  - ConversationCooldownManager: remembers across calls which kinds of questions and topics were
 *    used recently (on the phone only), so the same question is not asked again and again.
 *  - TopicSuggestionEngine: a light topic seed that was not used recently.
 *  - ConversationInitiativeManager: stay quiet / check in / continue the topic / mood check /
 *    follow up from memory / new topic, under the mode (active, normal, quiet, focus), frequency
 *    and quiet hours.
 *  - FollowUpQuestionGenerator: the one-line hint for the chosen move and the call's opening.
 */
export type ConversationMode = 'active' | 'normal' | 'quiet' | 'focus';
export type Frequency = 'low' | 'medium' | 'high';
export type Move = 'stay_quiet' | 'check_in' | 'continue_topic' | 'mood_check' | 'follow_up' | 'new_topic';

/** "companion" was the earlier name of Active mode. A user who never chose gets Active. */
export function normalizeMode(raw: unknown): ConversationMode {
  if (raw === 'companion' || raw === 'active' || raw === undefined || raw === null || raw === '') return 'active';
  return raw === 'quiet' || raw === 'focus' || raw === 'normal' ? raw : 'normal';
}
export function normalizeFrequency(raw: unknown): Frequency { return raw === 'low' || raw === 'high' ? raw : 'medium'; }

/** Minutes since midnight, from "HH:MM". */
function minutes(hhmm: string) { const [h, m] = hhmm.split(':').map(Number); return (h || 0) * 60 + (m || 0); }
/** True when `now` falls inside quiet hours (which may wrap past midnight, like 22:00 to 08:00). */
export function inQuietHours(now: Date, start: string, end: string) {
  const t = now.getHours() * 60 + now.getMinutes(), a = minutes(start), b = minutes(end);
  if (a === b) return false;
  return a < b ? t >= a && t < b : t >= a || t < b;
}

const SILENCE = /\b(?:chup|chup raho|khamosh|quiet|silence|shut up|mat bolo|baad mein baat|abhi nahi|abhi nahin|akela chhod|disturb mat|don'?t talk|stop talking|busy hoon|busy hun|kaam kar raha|kaam kar rahi)\b/i;
const ENDING = /^(?:bas|theek hai|thik hai|ok(?:ay)?|achha|acha|bye|khuda hafiz|allah hafiz|shukriya|thanks|thank you|good night|so raha|so rahi)[.!\s]*$/i;
const LOW_MOOD = /\b(?:mood off|udaas|udas|sad|pareshan|tension|upset|tired|thak gaya|thak gayi|thaka|thaki|bimar|beemar|bore|boring|akela|akeli|lonely)\b/i;
const TOPICS: [string, RegExp][] = [
  ['exam', /\b(?:exam|paper|test|imtihan|result|viva|interview)\b/i],
  ['university', /\b(?:university|uni|college|class|lecture|school|teacher|sir|madam)\b/i],
  ['project', /\b(?:project|app|website|client|deadline|launch|business|kaam|assignment|office)\b/i],
  ['mood', LOW_MOOD],
  ['plan', /\b(?:plan|kal|tomorrow|weekend|trip|safar|shaadi|party)\b/i],
];

export class ConversationContextManager {
  topics = new Set<string>();
  wantsSilence = false;
  lowMood = false;
  shortAnswers = 0;
  lastUserText = '';
  userTurns = 0;
  reset() { this.topics.clear(); this.wantsSilence = false; this.lowMood = false; this.shortAnswers = 0; this.lastUserText = ''; this.userTurns = 0; }
  /** A finished user sentence. Asking for silence lasts for the rest of the call. */
  heard(text: string) {
    const t = text.trim(); if (!t) return;
    this.lastUserText = t; this.userTurns++;
    if (SILENCE.test(t)) this.wantsSilence = true;
    if (LOW_MOOD.test(t)) this.lowMood = true;
    this.shortAnswers = t.split(/\s+/).length <= 2 && !ENDING.test(t) ? this.shortAnswers + 1 : 0;
    for (const [name, re] of TOPICS) if (re.test(t)) this.topics.add(name);
  }
  get endedConversation() { return ENDING.test(this.lastUserText); }
}
/** Earlier name, kept for callers and tests. */
export { ConversationContextManager as ContextualTopicManager };

/** Remembers recent question kinds and topics on the phone (no text of the conversation is kept). */
export class ConversationCooldownManager {
  private used: Record<string, number> = {};
  constructor(private readonly store?: { get(): string | null; set(v: string): void }) {
    try { this.used = JSON.parse(store?.get() || '{}') || {}; } catch { this.used = {}; }
  }
  /** Was this kind of question or topic used within the window? */
  recent(key: string, windowMs: number, now = Date.now()) { return now - (this.used[key] || 0) < windowMs; }
  mark(key: string, now = Date.now()) {
    this.used[key] = now;
    for (const [k, at] of Object.entries(this.used)) if (now - at > 14 * 86_400_000) delete this.used[k];
    try { this.store?.set(JSON.stringify(this.used)); } catch { /* kept for this session */ }
  }
}

const SEEDS = [
  'their studies or work these days', 'a favourite food or a place to eat', 'a drama, movie or series they are watching', 'cricket or a sport they follow',
  'music they have been listening to', 'a place they would love to travel to', 'something about technology or AI they find interesting', 'their plans for the weekend',
  'a fun "what would you do if" question', 'a skill they want to learn', 'a happy memory from childhood', 'a book, podcast or video they liked recently',
];
export const TopicSuggestionEngine = {
  /** A seed not used in the last few days, or '' when all were used recently. */
  pick(cool: ConversationCooldownManager, now = Date.now()): string {
    const start = Math.floor(now / 3_600_000) % SEEDS.length;
    for (let i = 0; i < SEEDS.length; i++) { const s = SEEDS[(start + i) % SEEDS.length]; if (!cool.recent('seed:' + s, 4 * 86_400_000, now)) return s; }
    return '';
  },
};

export interface IdleContext {
  mode: ConversationMode;
  frequency: Frequency;
  enabled: boolean;          // the "Check in when I go quiet" switch
  quietHours: boolean;       // now is inside the user's quiet hours
  quietMs: number;           // since anyone last spoke
  sinceLastCheckinMs: number;
  checkinsThisCall: number;
  busy: boolean;             // media playing, an action running, a phone call ringing
}

const TIMING: Record<Frequency, { after: number; cooldown: number; max: number }> = {
  low: { after: 60_000, cooldown: 15 * 60_000, max: 1 },
  medium: { after: 30_000, cooldown: 6 * 60_000, max: 3 },
  high: { after: 20_000, cooldown: 3 * 60_000, max: 5 },
};

export class ConversationInitiativeManager {
  constructor(readonly context: ConversationContextManager, readonly cool = new ConversationCooldownManager()) {}
  get topics() { return this.context; }
  /** Quiet spell, cooldown and per-call cap for a mode and frequency. Normal mode is always gentle. */
  static timing(mode: ConversationMode, frequency: Frequency) {
    if (mode === 'quiet' || mode === 'focus') return { after: Infinity, cooldown: Infinity, max: 0 };
    if (mode === 'normal') return { after: 45_000, cooldown: 10 * 60_000, max: 1 };
    return TIMING[frequency];
  }

  decideIdle(c: IdleContext): Move {
    if (!c.enabled || c.busy || c.quietHours || this.context.wantsSilence || this.context.endedConversation) return 'stay_quiet';
    const t = ConversationInitiativeManager.timing(c.mode, c.frequency);
    if (c.quietMs < t.after || c.sinceLastCheckinMs < t.cooldown || c.checkinsThisCall >= t.max) return 'stay_quiet';
    if (c.mode === 'normal') return 'check_in';
    // Active: care first, then what they said in this call, then memory, then a light new topic.
    if ((this.context.lowMood || this.context.shortAnswers >= 2) && !this.cool.recent('mood_check', 6 * 3_600_000)) return 'mood_check';
    if (this.context.topics.size && c.checkinsThisCall === 0) return 'continue_topic';
    if (!this.cool.recent('follow_up', 3 * 3_600_000)) return 'follow_up';
    return 'new_topic';
  }
  /** Records the move so the same kind of question is not repeated across calls. */
  used(move: Move, seed = '') { if (move !== 'stay_quiet') this.cool.mark(move); if (seed) this.cool.mark('seed:' + seed); }
}

export const FollowUpQuestionGenerator = {
  idle(move: Move, context: ConversationContextManager, tone: string, seed = ''): string {
    const about = [...context.topics].join(', ');
    const end = ' One or two short sentences with at most one question, in the user\'s language, in your own words, not a question you already asked today. If they do not answer, stay silent.';
    if (move === 'check_in') return '[Phone] The user has been quiet for a while with FRIDAY open. Say one short, natural line to check whether they need anything else (for example "Kuch aur karna hai?"). Do not repeat it. If they do not answer, stay silent.';
    if (move === 'mood_check') return `[Phone] Active mode: the user went quiet${context.lowMood ? ' after sounding low' : ' after a few very short answers'}. Gently check on them in one caring line, without assuming or diagnosing anything (for example "Aaj tum kuch quiet lag rahe ho. Sab theek hai, ya bas baat karne ka mood nahi?"). No jokes. If they say they are fine or want quiet, accept it.` + end;
    if (move === 'continue_topic') return `[Phone] Active mode: the user has gone quiet. Continue what they talked about in this call (${about}) with one curious follow-up about it or their point of view, building on their last answer. Tone: ${tone}.` + end;
    if (move === 'follow_up') return `[Phone] Active mode: the user has gone quiet. If RELEVANT MEMORY has something unfinished that fits (a project, an exam, a plan, how they were feeling), ask about it once, naturally ("Waise ... uska kya bana?"). If nothing fits, ask about their day in a fresh way. Never invent past conversations. Tone: ${tone}.` + end;
    if (move === 'new_topic') return `[Phone] Active mode: the user has gone quiet. Bring up one light, interesting topic${seed ? `, for example ${seed},` : ''} or a playful question that fits what you know about them ("Ek interesting sawal poochun?"). If they seem busy, just say you are here. Tone: ${tone}.` + end;
    return '';
  },
  /** The opening line when a call starts; Active mode may open with a question about the user. */
  opening(mode: ConversationMode, by: 'wake' | 'mic' | 'checkin', firstToday: boolean, examples: string, tone: string): string {
    if (by === 'checkin') return `[Phone] The user tapped your companion check-in, so they are inviting a chat. Open with one short, warm line in their language: follow up on one unfinished thing from RELEVANT MEMORY if there is one (a project, an exam, their mood), otherwise ask how their day went. One question only, then listen. Tone: ${tone}.`;
    const started = by === 'wake' ? 'the wake phrase' : 'the user tapping the microphone, so keep it extra short';
    if (mode === 'quiet' || mode === 'focus') return `[Phone] FRIDAY was just started by ${started}. ${mode === 'focus' ? 'Focus mode is on: ' : 'Quiet mode is on: '}say only a 1 to 3 word acknowledgement such as "Ji, bolo." and listen.`;
    if (mode === 'active' && firstToday) return `[Phone] FRIDAY was just started by ${started}, the first time today, in Active mode. Say one short, warm opening line in the user's language, and you may add one friendly question about their day or something unfinished from RELEVANT MEMORY (for example "Achha, ek baat batao... aaj din kaisa guzra?"). If the user starts talking, stop and listen. Tone: ${tone}.`;
    return `[Phone] FRIDAY was just started by ${started}. Say one short opening line in the user's language, like ${examples}, but in your own words and different from last time. Tone: ${tone}. Then stop and listen. If the user starts talking, stop and listen.`;
  },
};
