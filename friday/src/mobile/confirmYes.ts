/**
 * The phone remembers the last "send this?" question (WhatsApp message, reply, SMS, file, call) and carries out a
 * clear yes itself. So a short "haan" or "hn kro" always sends what was asked, even when the model lost track of
 * the conversation (a reconnect, noise in between) and would otherwise ask again or change the message.
 */
const YES = new Set(['haan', 'han', 'hn', 'ha', 'haa', 'hanji', 'hnji', 'ji', 'jee', 'g', 'yes', 'yeah', 'yep', 'ok', 'okay', 'okk', 'theek', 'thik', 'thek', 'bilkul', 'zaroor', 'zarur', 'sure', 'kro', 'karo', 'krdo', 'kardo', 'krdein', 'kardein', 'bhejo', 'bhej', 'bhejdo', 'send', 'go', 'done', 'chalo', 'sahi']);
const FILL = new Set(['hai', 'he', 'h', 'do', 'de', 'dein', 'dena', 'kr', 'kar', 'msg', 'message', 'reply', 'it', 'please', 'plz', 'pls', 'yar', 'yr', 'yaar', 'friday', 'ahead', 'abhi', 'jaldi', 'na', 'bhai', 'call', 'karna']);
const NO = new Set(['nahi', 'nahin', 'nai', 'ni', 'nhi', 'nh', 'no', 'nope', 'mat', 'ruko', 'ruk', 'cancel', 'rehne', 'stop', 'band']);
/** Sending actions whose question the phone itself may answer with the user's yes. */
export const CONFIRMABLE = new Set(['whatsapp_message', 'reply_message', 'send_sms', 'send_document', 'whatsapp_call', 'call', 'email']);
export const CONFIRM_MS = 110_000;

const words = (t: string) => t.toLowerCase().replace(/[^\p{L}\p{N}\s]/gu, ' ').split(/\s+/).filter(Boolean);
/** Only yes words ("haan", "hn kro", "ji bhej do", "ok send kar do"), nothing new. */
export function isYes(text: string): boolean {
  const w = words(text);
  if (!w.length || w.length > 6) return false;
  return w.some(x => YES.has(x)) && w.every(x => YES.has(x) || FILL.has(x));
}
/** A no, or anything that changes the request ("nahi", "ni yr pehle wali baat bhejo"). */
export function isNo(text: string): boolean { return words(text).some(x => NO.has(x)); }

export interface Pending { action: string; args: Record<string, unknown>; token: string; at: number; }
export class ConfirmMemory {
  private pending: Pending | null = null;
  private done = new Map<string, { result: any; at: number }>();
  /** Called with every action result: a needs_confirmation for a send is remembered. */
  saw(args: any, result: any, now = Date.now()) {
    const action = String(args?.action || '');
    if (result?.status === 'needs_confirmation' && typeof result.confirm_token === 'string' && CONFIRMABLE.has(action)) {
      const { confirm_token: _old, confirm_on_screen: _s, ...rest } = args || {};
      this.pending = { action, args: rest, token: result.confirm_token, at: now };
    } else if (action && action === this.pending?.action && args?.confirm_token) this.pending = null;
  }
  /** What to run for this sentence: the remembered request with its token, or null. A no forgets it. */
  answer(text: string, now = Date.now()): Pending | null {
    const p = this.pending;
    if (!p || now - p.at > CONFIRM_MS) { this.pending = null; return null; }
    if (isNo(text)) { this.pending = null; return null; }
    if (!isYes(text)) return null;
    this.pending = null;
    return p;
  }
  /** The phone already ran this token: the model's own call with it gets the same result instead of running twice. */
  ran(token: string, result: any, now = Date.now()) { this.done.set(token, { result, at: now }); for (const [t, d] of this.done) if (now - d.at > 600_000) this.done.delete(t); }
  already(args: any): any | null { const t = args?.confirm_token; return typeof t === 'string' ? this.done.get(t)?.result ?? null : null; }
  clear() { this.pending = null; }
}
