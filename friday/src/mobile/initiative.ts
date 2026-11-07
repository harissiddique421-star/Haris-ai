/**
 * Conversation initiative for Live calls: decides when FRIDAY may speak first and what kind of
 * opening or follow-up to give. Runs on the phone and makes no AI calls of its own: it only
 * chooses whether to send one short [Phone] hint to the Live session that is already open, and
 * the model writes the actual words from the conversation and its saved memory.
 *
 *  - ContextualTopicManager: what the user is talking about in this call (topics worth a
 *    follow-up, whether they are busy, whether they asked for silence).
 *  - ConversationInitiativeManager: answer / ask / continue / new topic / check in / stay quiet,
 *    under the user's conversation mode (normal, companion, quiet, focus).
 *  - FollowUpQuestionGenerator: the hint text for the chosen move.
 */
export type ConversationMode = 'normal' | 'companion' | 'quiet' | 'focus';
export type Move = 'stay_quiet' | 'check_in' | 'continue_topic' | 'follow_up' | 'new_topic';

export function normalizeMode(raw: unknown): ConversationMode {
  return raw === 'companion' || raw === 'quiet' || raw === 'focus' ? raw : 'normal';
}

const SILENCE = /\b(?:chup|chup raho|khamosh|quiet|silence|shut up|mat bolo|baad mein baat|abhi nahi|abhi nahin|akela chhod|disturb mat|don'?t talk|stop talking|busy hoon|busy hun|kaam kar raha|kaam kar rahi)\b/i;
const ENDING = /^(?:bas|theek hai|thik hai|ok(?:ay)?|achha|acha|bye|khuda hafiz|allah hafiz|shukriya|thanks|thank you)[.!\s]*$/i;
const TOPICS: [string, RegExp][] = [
  ['exam', /\b(?:exam|paper|test|imtihan|result|viva|interview)\b/i],
  ['project', /\b(?:project|app|website|client|deadline|launch|business|kaam|assignment)\b/i],
  ['mood', /\b(?:mood off|udaas|udas|sad|pareshan|tension|upset|tired|thak gaya|thak gayi|bimar|beemar)\b/i],
  ['plan', /\b(?:plan|kal|tomorrow|weekend|trip|safar|shaadi|party)\b/i],
];

export class ContextualTopicManager {
  topics = new Set<string>();
  wantsSilence = false;
  lastUserText = '';
  userTurns = 0;
  reset() { this.topics.clear(); this.wantsSilence = false; this.lastUserText = ''; this.userTurns = 0; }
  /** A finished user sentence. Asking for silence lasts for the rest of the call. */
  heard(text: string) {
    const t = text.trim(); if (!t) return;
    this.lastUserText = t; this.userTurns++;
    if (SILENCE.test(t)) this.wantsSilence = true;
    for (const [name, re] of TOPICS) if (re.test(t)) this.topics.add(name);
  }
  get endedConversation() { return ENDING.test(this.lastUserText); }
}

export interface IdleContext {
  mode: ConversationMode;
  enabled: boolean;          // the "Check in when I go quiet" switch
  quietMs: number;           // since anyone last spoke
  sinceLastCheckinMs: number;
  checkinsThisCall: number;
  busy: boolean;             // media playing, an action running, a phone call ringing
}

export class ConversationInitiativeManager {
  constructor(readonly topics: ContextualTopicManager) {}
  /** How long a quiet spell must last before FRIDAY may speak first, per mode (Infinity: never). */
  static idleAfter(mode: ConversationMode) { return mode === 'companion' ? 30_000 : mode === 'normal' ? 45_000 : Infinity; }
  static maxCheckins(mode: ConversationMode) { return mode === 'companion' ? 3 : mode === 'normal' ? 1 : 0; }

  decideIdle(c: IdleContext): Move {
    if (!c.enabled || c.busy || this.topics.wantsSilence) return 'stay_quiet';
    const after = ConversationInitiativeManager.idleAfter(c.mode);
    if (c.quietMs < after || c.sinceLastCheckinMs < 10 * 60_000) return 'stay_quiet';
    if (c.checkinsThisCall >= ConversationInitiativeManager.maxCheckins(c.mode)) return 'stay_quiet';
    if (c.mode === 'normal') return 'check_in';
    // Companion: something they said this call first, then memory, then a light new topic.
    if (this.topics.topics.size && c.checkinsThisCall === 0) return 'continue_topic';
    if (c.checkinsThisCall <= 1) return 'follow_up';
    return 'new_topic';
  }
}

export const FollowUpQuestionGenerator = {
  idle(move: Move, topics: ContextualTopicManager, tone: string): string {
    const about = [...topics.topics].join(', ');
    const end = ' Only one short line with at most one question, in the user\'s language, in your own words. If they do not answer, stay silent.';
    if (move === 'check_in') return '[Phone] The user has been quiet for a while with FRIDAY open. Say one short, natural line to check whether they need anything else (for example "Kuch aur karna hai?"). Do not repeat it. If they do not answer, stay silent.';
    if (move === 'continue_topic') return `[Phone] Companion mode: the user has gone quiet. Continue what they talked about in this call (${about}) with one curious, caring follow-up question, for example about how it is going or their point of view. Tone: ${tone}.` + end;
    if (move === 'follow_up') return `[Phone] Companion mode: the user has gone quiet. If RELEVANT MEMORY has something unfinished that fits (a project, an exam, how they were feeling), ask about it once, naturally ("Waise ... uska kya bana?"); otherwise ask how their day is going. Tone: ${tone}.` + end;
    if (move === 'new_topic') return `[Phone] Companion mode: the user has gone quiet. Offer one light, interesting topic or a playful question that fits what you know about them ("Ek interesting sawal poochun?"), or say you are here if they seem busy. Tone: ${tone}.` + end;
    return '';
  },
  /** The opening line when a call starts; companion mode may open with a question about the user. */
  opening(mode: ConversationMode, by: 'wake' | 'mic' | 'checkin', firstToday: boolean, examples: string, tone: string): string {
    if (by === 'checkin') return `[Phone] The user tapped your daily companion check-in, so they are inviting a chat. Open with one short, warm line in their language: follow up on one unfinished thing from RELEVANT MEMORY if there is one (a project, an exam, their mood), otherwise ask how their day went. One question only, then listen. Tone: ${tone}.`;
    const started = by === 'wake' ? 'the wake phrase' : 'the user tapping the microphone, so keep it extra short';
    if (mode === 'quiet' || mode === 'focus') return `[Phone] FRIDAY was just started by ${started}. ${mode === 'focus' ? 'Focus mode is on: ' : 'Quiet mode is on: '}say only a 1 to 3 word acknowledgement such as "Ji, bolo." and listen.`;
    if (mode === 'companion' && firstToday) return `[Phone] FRIDAY was just started by ${started}, the first time today, in companion mode. Say one short, warm opening line in the user's language, and you may add one friendly question about their day or something unfinished from RELEVANT MEMORY (for example "Achha, ek baat batao... aaj din kaisa guzra?"). If the user starts talking, stop and listen. Tone: ${tone}.`;
    return `[Phone] FRIDAY was just started by ${started}. Say one short opening line in the user's language, like ${examples}, but in your own words and different from last time. Tone: ${tone}. Then stop and listen. If the user starts talking, stop and listen.`;
  },
};
