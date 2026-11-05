/** Uses only browser-exposed installed voices. No API key or network provider is added. */
export function speakLocally(text: string, onStart: () => void, onEnd: () => void, onError: (message: string) => void): () => void {
  if (!('speechSynthesis' in window) || typeof SpeechSynthesisUtterance === 'undefined') {
    onError('Local speech is unavailable on this system. You can keep using text.'); return () => {};
  }
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text.slice(0, 3000));
  const voices = window.speechSynthesis.getVoices().filter(voice => voice.localService);
  const hindi = /[\u0900-\u097f]/.test(text);
  const voice = voices.find(v => hindi ? v.lang.startsWith('hi') : v.lang === 'en-IN') ?? voices.find(v => !hindi && v.lang.startsWith('en'));
  if (!voice) { onError(`No compatible local ${hindi ? 'Hindi' : 'English'} voice is installed. Text remains available.`); return () => {}; }
  utterance.voice = voice; utterance.lang = voice.lang;
  let finished = false;
  const finish = () => { if (!finished) { finished = true; clearTimeout(timer); onEnd(); } };
  const timer = setTimeout(() => { window.speechSynthesis.cancel(); finish(); }, 120_000);
  utterance.onstart = onStart; utterance.onend = finish;
  utterance.onerror = event => { finish(); if (!['canceled', 'interrupted'].includes(event.error)) onError(`Local speech failed (${event.error}). Text remains available.`); };
  window.speechSynthesis.speak(utterance);
  return () => { window.speechSynthesis.cancel(); finish(); };
}
