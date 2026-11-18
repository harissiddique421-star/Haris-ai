/**
 * Microphone levels for the Live call without Voice ID: when the user's turn starts and ends, and when a
 * louder voice interrupts FRIDAY. It never calls a model; it only looks at the RMS level of each 64 ms frame.
 *
 * While the user is not talking only silence reaches Gemini, so the moment this ends the turn is the moment
 * Gemini starts its own short end-of-speech wait and then answers. A turn that is held open by room noise
 * (fan, traffic, TV) after the user stopped means raw noise keeps flowing to Gemini and the reply comes late.
 */
export class TurnLevels {
  /**
   * Quietest level (RMS, about -40 dBFS) that can start the user's turn in a quiet room. It was 0.02 (-34 dBFS)
   * until rc.65, and a soft voice or a phone held at arm's length never reached it.
   */
  static readonly START = 0.01;
  /** Highest the noise-based start level may go (was 0.07, which needed a raised voice in any noisy room). */
  static readonly MAX_START = 0.04;
  /** The start level sits this far above the room's own noise. */
  static readonly START_OVER_NOISE = 2.5;
  /** Once the user is talking, quieter words (sentence ends, a dropped voice) still count at this share of the start level... */
  static readonly KEEP_SHARE = 0.6;
  /**
   * ...but never closer to the room's noise than this. rc.65 kept the turn going at 0.6 x the start level alone,
   * that is only 1.5 x the noise: an ordinary fan or traffic flickering a little above its average kept the turn
   * open after the user stopped, so the room went to Gemini instead of silence and the reply came seconds late.
   */
  static readonly KEEP_OVER_NOISE = 2;
  /** The turn ends this long after the last frame at the keep-talking level. */
  static readonly STOP_DELAY_MS = 500;
  /**
   * Quieter frames alone keep a turn going at most this long: with nothing at the start level for this long the
   * user has stopped and it is the room, so the turn ends even if the room stays a little above its average.
   */
  static readonly SOFT_ONLY_MS = 1500;
  static readonly START_FRAMES = 2;
  // FRIDAY's own voice from the loudspeaker leaks back into the microphone: a louder, longer voice is needed to interrupt her.
  static readonly BARGE_IN = 0.08;
  static readonly BARGE_IN_FRAMES = 8;
  /** Recent frames (about 2 s) whose quietest level shows the room's noise, even while someone is talking. */
  static readonly MIN_WINDOW = 32;

  /** The room's background level. */
  noiseFloor = 0.005;
  voiceFrames = 0;
  /** Last frame at the keep-talking level, and the last at the full start level. */
  lastVoiceAt = 0;
  lastStrongAt = 0;
  private recent: number[] = [];

  reset() { this.voiceFrames = 0; this.lastVoiceAt = 0; this.lastStrongAt = 0; this.recent = []; }

  /** Level that starts the user's turn (and, scaled by KEEP_SHARE/KEEP_OVER_NOISE, keeps it). */
  startLevel() { return Math.max(TurnLevels.START, Math.min(TurnLevels.MAX_START, this.noiseFloor * TurnLevels.START_OVER_NOISE)); }
  keepLevel() {
    const start = this.startLevel();
    return Math.min(start, Math.max(start * TurnLevels.KEEP_SHARE, this.noiseFloor * TurnLevels.KEEP_OVER_NOISE));
  }

  /** While FRIDAY's guard is on (the opening of her reply) nothing counts toward a barge-in. */
  guard() { this.voiceFrames = 0; }

  /**
   * One microphone frame. speaking: whether the user's turn is open now. output: FRIDAY is talking (or her echo
   * is still in the room), with outputRms her level at the speaker. Returns "start" when the user's turn starts
   * (an interruption when output is true), "stop" when it ends, otherwise null.
   */
  step(rms: number, now: number, speaking: boolean, output: boolean, outputRms = 0): "start" | "stop" | null {
    const start = this.startLevel();
    const threshold = output
      ? Math.max(
          TurnLevels.BARGE_IN,
          // A short knock, cough or steady room noise must not cut FRIDAY off: stay well above the room's level.
          Math.min(0.12, this.noiseFloor * 3.5),
          Math.min(0.16, outputRms * 0.5 + 0.02),
        )
      : speaking ? this.keepLevel() : start;

    if (!output) this.learnRoom(rms, speaking, threshold);

    if (rms >= threshold) {
      this.lastVoiceAt = now;
      if (output || rms >= start) this.lastStrongAt = now;
      this.voiceFrames += 1;
      if (!speaking && this.voiceFrames >= (output ? TurnLevels.BARGE_IN_FRAMES : TurnLevels.START_FRAMES)) return "start";
      if (speaking && !output && now - this.lastStrongAt >= TurnLevels.SOFT_ONLY_MS) { this.voiceFrames = 0; return "stop"; }
      return null;
    }

    // While FRIDAY talks, a brief dip between syllables should not restart the barge-in count.
    this.voiceFrames = output ? Math.max(0, this.voiceFrames - 2) : 0;
    if (speaking && (now - this.lastVoiceAt >= TurnLevels.STOP_DELAY_MS || (!output && now - this.lastStrongAt >= TurnLevels.SOFT_ONLY_MS))) return "stop";
    return null;
  }

  /**
   * Follows the room's background level (TV, fan, music) so steady noise is not taken as the user. It drops at
   * once to a quieter room. It rises from the quietest frame of the last ~2 s: words always have short gaps, so
   * a voice hardly moves it, while a steady noise is learnt within a few seconds (rc.65 took about 30 s for a
   * noise above the start level, and meanwhile that noise opened turns and kept them open).
   */
  private learnRoom(rms: number, speaking: boolean, threshold: number) {
    this.recent.push(rms);
    if (this.recent.length > TurnLevels.MIN_WINDOW) this.recent.shift();
    if (rms < this.noiseFloor) { this.noiseFloor = this.noiseFloor * 0.8 + rms * 0.2; return; }
    if (!speaking && rms < threshold * 0.7) this.noiseFloor = this.noiseFloor * 0.99 + rms * 0.01;
    if (this.recent.length >= TurnLevels.MIN_WINDOW) {
      let quietest = Infinity;
      for (const level of this.recent) if (level < quietest) quietest = level;
      if (quietest > this.noiseFloor) this.noiseFloor += (quietest - this.noiseFloor) * 0.1;
    }
  }
}
