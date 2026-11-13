/**
 * Emotion Engine: picks FRIDAY's conversational tone from what just happened.
 *
 * It is a behaviour layer, not feelings. It reads the user's words (live
 * captions), how they were said (voice tone: louder and higher, or quieter and
 * flatter than this user's usual voice, see voiceTone.ts), the result of each
 * phone action and the conversation so far, and returns one of thirteen tones. The tone is:
 *  - sent to Gemini with each action result, so the spoken reply's wording,
 *    delivery and length fit the moment;
 *  - shown on FRIDAY's face (the avatar's expression) during Live calls.
 * Runs on the phone, keeps no text, and never calls a model.
 */
export type Emotion =
  | 'happy' | 'excited' | 'calm' | 'curious' | 'concerned' | 'serious'
  | 'surprised' | 'playful' | 'confident' | 'thoughtful' | 'focused' | 'apologetic' | 'gentle';
import type { ToneCue } from './voiceTone';

/** The avatar's closest expression for each tone (the 3D character has fewer faces than tones). */
export const AVATAR_EMOTION: Record<Emotion, string> = {
  happy: 'happy', excited: 'excited', calm: 'idle', curious: 'curious', concerned: 'thinking',
  serious: 'idle', surprised: 'surprised', playful: 'playful', confident: 'proud',
  thoughtful: 'thinking', focused: 'thinking', apologetic: 'embarrassed', gentle: 'idle',
};

/** How the spoken reply should sound for each tone; sent to Gemini as a short hint. */
export const TONE_HINT: Record<Emotion, string> = {
  happy: 'warm and light, one short line',
  excited: 'energetic but brief',
  calm: 'calm and steady',
  curious: 'interested; ask one short question if needed',
  concerned: 'careful and reassuring; say what went wrong and the next step',
  serious: 'clear and direct, no jokes',
  surprised: 'mildly surprised, then check the result',
  playful: 'light and friendly',
  confident: 'confident and brief',
  thoughtful: 'considered; take a moment, then answer',
  focused: 'focused; give the exact detail to confirm',
  apologetic: 'briefly apologetic, then say what you will do',
  gentle: 'soft, gentle and a little slower; keep it short and kind (the user sounds tired or low)',
};

// Roman Urdu, Hindi, Hinglish and English cues. Word boundaries keep "great" out of "greater" and so on.
const FRUSTRATED = /\b(?:phir se|dobara|kitni (?:baar|bar|dafa)|nahi ho raha|nahin ho raha|kaam nahi|kaam nahin|kya bakwas|bakwas|faltu|pagal|ugh|again|still not|doesn'?t work|not working|why (?:isn'?t|won'?t|can'?t)|useless|samajh nahi|samjh nahi|yaar\s*!|uff+)\b/i;
const EXCITED = /\b(?:wow|zabardast|kamaal|kamal|awesome|amazing|great|superb|mast|maza|shandaar|yay|yess+|let'?s go)\b|!{2,}/i;
const URGENT = /\b(?:jaldi|abhi|turant|foran|fauran|urgent|emergency|asap|quick(?:ly)?|right now)\b/i;
const THANKS = /\b(?:thanks|thank you|shukriya|shukria|thnx|thx|jazakallah|dhanyavad)\b/i;
const PLAYFUL = /\b(?:haha+|hehe+|lol|joke|mazak|mazaak|lmao)\b/i;
const SAD = /\b(?:sad|udaas|udas|pareshan|tension|worried|dukhi|thak gaya|thak gayi|tired|bimar|beemar|sick)\b/i;
const QUESTION = /\?|\b(?:kya|kaise|kyun|kyon|kab|kahan|kaun|what|why|how|when|where|who)\b/i;

const OK = new Set(['done', 'set', 'opened', 'playing', 'sent', 'calling', 'saved', 'steps']);

export class EmotionEngine {
  private current: Emotion = 'calm';
  private frustration = 0;
  private lastChange = 0;

  get emotion(): Emotion { return this.current; }

  reset() { this.current = 'calm'; this.frustration = 0; this.lastChange = 0; }

  /**
   * The user's own words (a finished caption) and, when known, how they sounded. The words decide
   * first; the voice only settles sentences whose words carry no feeling, and makes frustrated words
   * said in a raised voice count double.
   */
  fromUser(text: string, voice: ToneCue = null): Emotion {
    const t = text.trim(); if (!t) return this.current;
    if (FRUSTRATED.test(t)) { this.frustration = Math.min(3, this.frustration + (voice === 'raised' ? 2 : 1)); return this.set(this.frustration >= 2 ? 'apologetic' : 'concerned'); }
    const wasFrustrated = this.frustration > 0;
    this.frustration = Math.max(0, this.frustration - 1);
    if (SAD.test(t)) return this.set(voice === 'low' ? 'gentle' : 'concerned');
    if (URGENT.test(t)) return this.set('focused');
    if (THANKS.test(t)) return this.set('happy');
    if (EXCITED.test(t)) return this.set('excited');
    if (PLAYFUL.test(t)) return this.set('playful');
    // Neutral words in a raised voice right after a failure: still upset. Otherwise: be direct and quick.
    if (voice === 'raised') { if (wasFrustrated) { this.frustration = Math.min(3, this.frustration + 2); return this.set('apologetic'); } return this.set('focused'); }
    if (voice === 'low') return this.set('gentle');
    if (QUESTION.test(t)) return this.set(t.split(/\s+/).length > 12 ? 'thoughtful' : 'curious');
    return this.set('calm');
  }

  /** The result of a phone action. */
  fromAction(action: string, status: string): Emotion {
    if (status === 'needs_confirmation' || status === 'choose' || status === 'needs_user') return this.set('focused');
    if (status === 'permission_required') return this.set('serious');
    if (status === 'unverified' || status === 'draft_opened') return this.set('thoughtful');
    if (status === 'cancelled') return this.set('calm');
    if (OK.has(status)) {
      // After the user was frustrated, success is reported plainly and kindly, not with excitement.
      if (this.frustration > 0) { this.frustration = 0; return this.set('calm'); }
      return this.set(action === 'play_music' || action === 'search_youtube' ? 'playful' : 'confident');
    }
    if (status === 'timeout' || status === 'error' || status === 'failed' || status === 'unavailable' || status === 'unsupported' || status === 'not_found')
      return this.set(this.frustration > 0 ? 'apologetic' : 'concerned');
    return this.set('surprised');
  }

  private set(next: Emotion): Emotion { this.current = next; this.lastChange = Date.now(); return next; }

  /** Seconds since the tone last changed; the face relaxes back to calm after a while. */
  get ageMs(): number { return this.lastChange ? Date.now() - this.lastChange : Infinity; }
}
