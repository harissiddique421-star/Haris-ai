/**
 * Live call recovery: decides what to do when the Gemini Live connection ends with an error.
 *  - switch_model: Google refused this Live model (out of quota, or not available to this key),
 *    so try the next Live model in the list.
 *  - reconnect: the call was running and the connection dropped or Google asked to end the
 *    session (goAway, time limit, server restart, brief network loss), so connect again with
 *    the same model and carry the conversation over when Google allows it.
 *  - none: show the message (wrong key, no internet, repeated failures).
 * The limits stop a broken key or model from looping.
 */
export type Recovery = 'switch_model' | 'reconnect' | 'none';

export interface RecoveryState {
  /** Model switches already tried for this call. */
  fallbacks: number;
  /** Automatic reconnects in a row without a working call in between. */
  reconnects: number;
  online: boolean;
  /** FRIDAY is on screen (a call can only be started in the foreground). */
  visible: boolean;
  /** This connection tried to resume the previous session. */
  resuming: boolean;
}

export const MAX_FALLBACKS = 2;
export const MAX_RECONNECTS = 2;

/** Message LiveTransport sends when Google announces the session is about to end. */
export const SESSION_ENDING = 'This Live session is ending.';

const QUOTA = /quota|resource.?exhausted|rate.?limit/i;
const MODEL_REFUSED = /not found|not supported|unsupported|is not available|unknown model|does not exist/i;
/** Close codes for a dropped or restarted connection (not a refused key or setup). */
const DROPPED = new Set([1001, 1006, 1011, 1012, 1013, 1014]);

/** Close code and Google's own reason from LiveTransport's message (its fixed advice text mentions "quota" too, so only the reason is checked). */
function closeInfo(message: string): { code: number; reason: string } | null {
  const m = /\(code (\d{4})(?:: (.*))?\)\. Check your key/.exec(message);
  return m ? { code: Number(m[1]), reason: m[2] || '' } : null;
}

export function liveRecovery(message: string, s: RecoveryState): Recovery {
  const ending = message.startsWith(SESSION_ENDING);
  const close = closeInfo(message);
  const starting = !!close && /while starting/i.test(message);
  const during = !!close && /during the call/i.test(message);
  if (close && (QUOTA.test(close.reason) || (starting && (close.code === 1007 || close.code === 1008) && MODEL_REFUSED.test(close.reason))))
    return s.fallbacks < MAX_FALLBACKS ? 'switch_model' : 'none';
  if (!s.online || !s.visible || s.reconnects >= MAX_RECONNECTS) return 'none';
  if (ending) return 'reconnect';
  // A resume Google would not accept (old handle, or the setting refused): connect again as a fresh call.
  if (starting && (s.resuming || /resum|compress|sliding/i.test(close!.reason))) return 'reconnect';
  if (during && DROPPED.has(close!.code)) return 'reconnect';
  return 'none';
}
