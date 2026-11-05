import { classifyVoiceFailure } from './diagnostics';

/** Bounded fallback across already configured, authorized realtime models. */
export async function connectVoice<T>(models: string[], connect: (model: string) => Promise<T>,
  onFallback: (from: string, to: string) => void, cancelled: () => boolean = () => false): Promise<{ session: T; model: string }> {
  const candidates = [...new Set(models)].slice(0, 2);
  for (let i = 0; i < candidates.length; i++) {
    if (cancelled()) throw new Error('Voice connection cancelled.');
    try {
      const session = await connect(candidates[i]);
      if (cancelled()) { (session as { close?: () => void })?.close?.(); throw new Error('Voice connection cancelled.'); }
      return { session, model: candidates[i] };
    } catch (error) {
      const failure = classifyVoiceFailure(error);
      if (failure.code === 'VOICE_AUTH' || i + 1 >= candidates.length || cancelled()) throw error;
      onFallback(candidates[i], candidates[i + 1]);
    }
  }
  throw new Error('No configured realtime voice model is available.');
}
