/**
 * Voice tone: how the user said it, not what they said. While the user talks, each microphone frame's
 * loudness and pitch are measured on the phone (nothing is recorded or kept, only a few numbers per
 * sentence). When the sentence ends it is compared with this user's own usual voice in this call:
 *  - 'raised': clearly louder and higher than usual (excited, upset or in a hurry);
 *  - 'low': clearly quieter, lower and flatter than usual (tired, sad or unwell).
 * Anything else, and the first sentences of a call (no usual voice yet), give no cue, so the
 * Emotion Engine falls back to the words alone. The cue only nudges the tone; the words decide first.
 */
export type ToneCue = 'raised' | 'low' | null;

const RATE = 16000;
/** Pitch search on a 2x decimated signal (8 kHz): 70-400 Hz covers men, women and children. */
const MIN_LAG = Math.floor(RATE / 2 / 400), MAX_LAG = Math.ceil(RATE / 2 / 70);

interface Sentence { db: number; pitch: number; spread: number }

export class VoiceTone {
  private dbs: number[] = [];
  private pitches: number[] = [];
  private usual: Sentence | null = null;
  private sentences = 0;
  private half = new Float32Array(0);

  reset() { this.dbs = []; this.pitches = []; this.usual = null; this.sentences = 0; }

  /** One frame (16 kHz mono floats) of the user speaking. */
  feed(frame: Float32Array) {
    let sum = 0; for (let i = 0; i < frame.length; i++) sum += frame[i] * frame[i];
    const rms = Math.sqrt(sum / Math.max(1, frame.length));
    if (rms < 0.005) return; // a pause between words
    this.dbs.push(20 * Math.log10(rms));
    const p = VoiceTone.pitch(frame, this.scratch(frame.length >> 1));
    if (p > 0) this.pitches.push(p);
  }

  /** The sentence ended: its cue against this user's usual voice, then the usual voice learns from it. */
  end(): ToneCue {
    const dbs = this.dbs, pitches = this.pitches; this.dbs = []; this.pitches = [];
    if (dbs.length < 5 || pitches.length < 4) return null; // too short to judge
    const s: Sentence = { db: median(dbs), pitch: median(pitches), spread: semitoneSpread(pitches) };
    const u = this.usual;
    let cue: ToneCue = null;
    if (u && this.sentences >= 2) {
      const up = 12 * Math.log2(s.pitch / u.pitch); // semitones above the usual pitch
      if (s.db - u.db >= 4 && up >= 2) cue = 'raised';
      else if (u.db - s.db >= 4 && up <= -1 && s.spread <= u.spread * 0.8) cue = 'low';
    }
    // A raised or low sentence moves the usual voice only a little, so one shout does not become "usual".
    const a = !u ? 1 : cue ? 0.1 : 0.3;
    this.usual = u ? { db: u.db + (s.db - u.db) * a, pitch: u.pitch * Math.pow(s.pitch / u.pitch, a), spread: u.spread + (s.spread - u.spread) * a } : s;
    this.sentences++;
    return cue;
  }

  private static corr: Float32Array | null = null;
  private scratch(n: number) { if (this.half.length !== n) this.half = new Float32Array(n); return this.half; }

  /** Pitch in Hz by autocorrelation on the frame averaged down to 8 kHz, or 0 when it is not voiced. */
  static pitch(frame: Float32Array, half = new Float32Array(frame.length >> 1)): number {
    const n = half.length;
    let mean = 0;
    for (let i = 0; i < n; i++) { half[i] = (frame[2 * i] + frame[2 * i + 1]) * 0.5; mean += half[i]; }
    mean /= n || 1;
    let energy = 0;
    for (let i = 0; i < n; i++) { half[i] -= mean; energy += half[i] * half[i]; }
    if (energy <= 0 || n <= MAX_LAG * 2) return 0;
    const r = this.corr || (this.corr = new Float32Array(MAX_LAG + 1));
    let best = 0;
    for (let lag = MIN_LAG; lag <= MAX_LAG; lag++) {
      let c = 0, e1 = 0, e2 = 0;
      for (let i = 0; i + lag < n; i++) { const x = half[i], y = half[i + lag]; c += x * y; e1 += x * x; e2 += y * y; }
      r[lag] = c / Math.sqrt(e1 * e2 || 1);
      if (r[lag] > best) best = r[lag];
    }
    if (best < 0.6) return 0;
    // The first peak close to the best one is the real period; later ones are its multiples (an octave too low).
    for (let lag = MIN_LAG + 1; lag < MAX_LAG; lag++) {
      if (r[lag] >= best * 0.9 && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) return RATE / 2 / lag;
    }
    return 0;
  }
}

function median(v: number[]) { const s = [...v].sort((a, b) => a - b); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; }
/** How much the pitch moves within the sentence: the middle half's range, in semitones. */
function semitoneSpread(p: number[]) {
  const s = p.map(x => 12 * Math.log2(x)).sort((a, b) => a - b);
  return s[Math.floor(s.length * 0.75)] - s[Math.floor(s.length * 0.25)];
}
