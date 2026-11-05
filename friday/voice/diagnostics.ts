import { describeGoogleAuthError } from '../shared/googleAuth';

export interface VoiceDiagnostic {
  service: 'voice';
  code: 'VOICE_QUOTA' | 'VOICE_AUTH' | 'VOICE_NETWORK' | 'VOICE_UNAVAILABLE';
  retryable: boolean;
  textAvailable: true;
  message: string;
}

/** Voice quota must not invalidate keys or imply text/STT/TTS quotas also failed. */
export function classifyVoiceFailure(value: unknown): VoiceDiagnostic {
  const error = value as { message?: string; status?: number; code?: number; reason?: string };
  const text = `${error?.message || ''} ${error?.reason || ''} ${typeof value === 'string' ? value : ''}`;
  const status = Number(error?.status || error?.code);
  if (status === 429 || /quota|resource[ _-]?exhausted|rate.?limit|too many requests/i.test(text)) return {
    service: 'voice', code: 'VOICE_QUOTA', retryable: false, textAvailable: true,
    message: 'The realtime voice model reached its quota. Text chat uses your separately configured text model. No automatic quota retries will run.',
  };
  const authFailure = describeGoogleAuthError(value);
  if (authFailure || [401, 403].includes(status) || /api.?key.*(invalid|not valid|rejected)|unauthenticated|authentication|credential/i.test(text)) return {
    service: 'voice', code: 'VOICE_AUTH', retryable: false, textAvailable: true,
    message: authFailure ? `${authFailure.message} Text chat needs a working configured provider.` : 'The voice provider rejected authentication. Check the configured key. Text chat may still be available through another configured provider.',
  };
  const network = /network|socket|fetch failed|timed? ?out|econn|enotfound/i.test(text);
  return { service: 'voice', code: network ? 'VOICE_NETWORK' : 'VOICE_UNAVAILABLE', retryable: true, textAvailable: true,
    message: 'The realtime voice connection is unavailable. You can continue with text chat.' };
}
