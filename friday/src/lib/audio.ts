/**
 * Audio handling utility for Friday Live API Voice stream.
 * Handles:
 * - 16kHz layout sampling for microphone stream.
 * - Raw Little Endian Int16 PCM translation.
 * - 24kHz layout output sampling for model voice playback.
 * - Gapless double-buffer queue scheduler.
 * - Interrupt signal immediate stop.
 * - Input & Output AnalyserNodes for real-time waveform visuals.
 */

export type LiveState = "disconnected" | "connecting" | "listening" | "speaking";

// PCM Conversion Helper: converts Float32Array [-1.0, 1.0] to signed Int16 Raw PCM Little Endian
function floatTo16BitPCM(input: Float32Array): ArrayBuffer {
  const buffer = new ArrayBuffer(input.length * 2);
  const view = new DataView(buffer);
  let offset = 0;
  for (let i = 0; i < input.length; i++, offset += 2) {
    let s = Math.max(-1, Math.min(1, input[i]));
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
  }
  return buffer;
}

/**
 * Streaming pitch shifter that raises pitch by `ratio` without changing speed
 * (lip-sync and timing are kept). A single read head plays the delay line a
 * little faster than real time; when it catches up with the newest audio it
 * jumps back by the lag whose waveform matches best (pitch-period aligned)
 * and crossfades for 5 ms. Only one copy of the voice is audible at a time,
 * so there is no doubling or echo (the comb effect of two free-running heads).
 * State carries across chunks, so chunk boundaries stay seamless.
 */
export class PitchShifter {
  private readonly N = 8192;
  private readonly buf = new Float32Array(8192);
  private written = 0;
  private rp: number;
  private fadeFrom = 0;
  private fadeLeft = 0;
  private readonly W: number; // match window
  private readonly X: number; // crossfade length
  private readonly minLag: number;
  private readonly maxLag: number;
  private readonly minDelay: number;

  constructor(private readonly ratio: number, sampleRate = 24000) {
    const s = sampleRate / 24000;
    this.W = Math.round(240 * s); // 10 ms
    this.X = Math.round(120 * s); // 5 ms
    this.minLag = Math.round(240 * s);
    this.maxLag = Math.round(720 * s); // 30 ms
    this.minDelay = Math.round(400 * s);
    this.rp = -(this.minDelay + this.maxLag);
  }

  private at(pos: number): number {
    const i = Math.floor(pos);
    const f = pos - i;
    const a = i >= 0 && i < this.written ? this.buf[i % this.N] : 0;
    const b = i + 1 >= 0 && i + 1 < this.written ? this.buf[(i + 1) % this.N] : 0;
    return a + (b - a) * f;
  }

  /** Jump the head back by the best-matching lag and start a crossfade. */
  private splice(): void {
    const base = Math.floor(this.rp);
    let best = this.minLag;
    let bestScore = -Infinity;
    for (let lag = this.minLag; lag <= this.maxLag; lag += 2) {
      let dot = 0;
      let energy = 1e-9;
      for (let k = 0; k < this.W; k += 2) {
        const a = this.at(base + k);
        const b = this.at(base - lag + k);
        dot += a * b;
        energy += b * b;
      }
      const score = dot / Math.sqrt(energy);
      if (score > bestScore) {
        bestScore = score;
        best = lag;
      }
    }
    this.fadeFrom = this.rp;
    this.rp -= best;
    this.fadeLeft = this.X;
  }

  process(input: Float32Array): Float32Array {
    const out = new Float32Array(input.length);
    for (let n = 0; n < input.length; n++) {
      this.buf[this.written % this.N] = input[n];
      this.written++;
      let y: number;
      if (this.fadeLeft > 0) {
        const t = 1 - this.fadeLeft / this.X;
        y = this.at(this.fadeFrom) * (1 - t) + this.at(this.rp) * t;
        this.fadeFrom += this.ratio;
        this.fadeLeft--;
      } else {
        y = this.at(this.rp);
      }
      out[n] = y;
      this.rp += this.ratio;
      if (this.fadeLeft === 0 && this.written - this.rp < this.minDelay) this.splice();
    }
    return out;
  }
}

// Float conversion helper: converts signed Int16 array buffer to Float32Array [-1.0, 1.0]
function pcm16ToFloats(uint8Array: Uint8Array): Float32Array {
  const int16 = new Int16Array(
    uint8Array.buffer,
    uint8Array.byteOffset,
    uint8Array.byteLength / 2
  );
  const floats = new Float32Array(int16.length);
  for (let i = 0; i < int16.length; i++) {
    floats[i] = int16[i] / 32768.0;
  }
  return floats;
}

// Convert ArrayBuffer to Base64 String
function base64ArrayBuffer(arrayBuffer: ArrayBuffer): string {
  let binary = '';
  const bytes = new Uint8Array(arrayBuffer);
  const len = bytes.byteLength;
  for (let i = 0; i < len; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return window.btoa(binary);
}

// Convert Base64 string to Uint8Array
function base64ToUint8Array(base64: string): Uint8Array {
  const binaryString = window.atob(base64);
  const len = binaryString.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  return bytes;
}

export interface AudioSocket {
  readyState: number;
  binaryType: string;
  onopen: ((event: any) => void) | null;
  onmessage: ((event: any) => void) | null;
  onerror: ((event: any) => void) | null;
  onclose: ((event: any) => void) | null;
  send(data: string): void;
  close(): void;
}
/**
 * On-device speaker check for the microphone (HARIS Voice ID on the Android app). feed() takes one
 * 16-bit PCM frame (base64) and FRIDAY's activity (0 listening, 1 thinking, 2 speaking) and returns
 * JSON: "a" = audio allowed to reach Gemini, in order (["o", owner pcm base64] or ["z", silent
 * samples]); "e" = events (["owner", score] the owner started talking, ["end", score] the owner's turn ended
 * (even if other people keep talking), ["unclear", score] overlapping voices hid the owner, ["unknown"|"noise", score]);
 * "s" = state; "fail" = the check stopped and the call continues without it.
 */
/** endMs: the quiet time after which the gate ends the owner's turn (for the turn timing log). */
export interface VoiceGateLink { feed(pcm: string, ai: number): string; stop(): void; endMs?: number; }
export interface AudioConnection { createSocket(): AudioSocket; pitch?: number; voiceGate?: () => VoiceGateLink | null; }

/**
 * Times one voice turn, stage by stage (milliseconds only, never audio or words): when the speaker
 * was heard, when Voice ID accepted them, when they stopped, when the phone told Gemini the turn
 * ended, when Gemini's transcript and reply arrived and when FRIDAY's voice started playing.
 * Each finished turn is written to the log and kept (last 5) for Settings diagnostics.
 */
export class TurnTimer {
  speechAt = 0; verifiedAt = 0; verifyMs = -1; stoppedAt = 0; endAt = 0; endSentAt = 0;
  heardAt = 0; heardDoneAt = 0; toolAt = 0; toolDoneAt = 0; replyAt = 0; playAt = 0; doneAt = 0;
  endMs = 0;
  readonly log: string[] = [];
  /** Biggest stage of the last finished turn, and its whole delay from "stopped" to "voice playing". */
  lastTotalMs = -1; lastBiggest = "";

  start(at: number) { if (this.speechAt && !this.playAt && !this.stoppedAt) return; this.reset(); this.speechAt = at; }
  verified(at: number, checkMs: number) { if (!this.speechAt) this.speechAt = at; this.verifiedAt = at; this.verifyMs = checkMs; }
  stopped(stoppedAt: number, endAt: number, sentAt: number) { this.stoppedAt = stoppedAt; this.endAt = endAt; this.endSentAt = sentAt; }
  heard(at: number) { if (!this.stoppedAt) return; if (!this.heardAt) this.heardAt = at; this.heardDoneAt = at; }
  tool(at: number) { if (this.stoppedAt && !this.toolAt) this.toolAt = at; }
  toolDone(at: number) { if (this.toolAt) this.toolDoneAt = at; }
  reply(at: number) { if (this.stoppedAt && !this.replyAt) this.replyAt = at; }
  playing(at: number) { if (this.stoppedAt && this.replyAt && !this.playAt) { this.playAt = at; this.finish(); } }
  complete(at: number) { if (this.stoppedAt) this.doneAt = at; }

  private reset() { this.speechAt = this.verifiedAt = this.stoppedAt = this.endAt = this.endSentAt = this.heardAt = this.heardDoneAt = this.toolAt = this.toolDoneAt = this.replyAt = this.playAt = this.doneAt = 0; this.verifyMs = -1; }

  private finish() {
    const from = this.stoppedAt;
    const stages: [string, number][] = [];
    const add = (name: string, a: number, b: number) => { if (a && b && b >= a) stages.push([name, b - a]); };
    // End of turn on the phone: the quiet time after the speaker's last word (endpoint), plus the gate's own delay.
    add("end of speech detected", from, this.endAt || this.endSentAt);
    add("Gemini heard it (transcript)", this.endSentAt || this.endAt || from, this.heardAt);
    if (this.toolAt) add("phone action", this.toolAt, this.toolDoneAt || this.replyAt);
    add("Gemini reply started", this.heardAt || this.endSentAt || this.endAt || from, this.replyAt);
    add("voice playback started", this.replyAt, this.playAt);
    this.lastTotalMs = this.playAt - from;
    const biggest = stages.reduce((m, s) => (s[1] > m[1] ? s : m), ["", -1] as [string, number]);
    this.lastBiggest = biggest[0];
    const line = `turn: ${this.lastTotalMs} ms from your last word to FRIDAY's voice`
      + (this.verifyMs >= 0 ? ` · voice check ${this.verifyMs} ms` : "")
      + ` · ${stages.map(([n, ms]) => `${n} +${ms} ms`).join(" · ")}`
      + (this.endMs ? ` (turn ends after ${this.endMs} ms quiet)` : "")
      + (biggest[0] ? ` · slowest: ${biggest[0]}` : "");
    console.log(`[Voice latency] ${line}`);
    this.log.push(line);
    if (this.log.length > 5) this.log.shift();
  }
}

export class MyraaAudioSession {
  private ws: AudioSocket | null = null;
  
  // Audios contexts (separate to match exact required sample rates)
  private inputAudioCtx: AudioContext | null = null;
  private outputAudioCtx: AudioContext | null = null;
  
  // Audio sources & processors
  private micStream: MediaStream | null = null;
  private micSourceNode: MediaStreamAudioSourceNode | null = null;
  private micProcessorNode: ScriptProcessorNode | null = null;
  private micFilterNode: BiquadFilterNode | null = null;
  
  // Visualisers
  public inputAnalyser: AnalyserNode | null = null;
  public outputAnalyser: AnalyserNode | null = null;
  private outputGainNode: GainNode | null = null;
  private pitchShifter: PitchShifter | null = null;
  
  // Buffering / Playback details
  private nextStartTime = 0;
  private activeSources: AudioBufferSourceNode[] = [];
  private userSpeaking = false;
  private voiceFrames = 0;
  private lastVoiceAt = 0;
  private outputSpeechStartedAt = 0;
  private readonly voiceThreshold = 0.02;
  /** Slowly follows the room's background level (TV, fan, music) so steady noise is not taken as the user starting to speak. */
  private noiseFloor = 0.005;
  private readonly voiceStartFrames = 2;
  private readonly voiceStopDelayMs = 500;
  private readonly bargeInGuardMs = 700;
  private readonly bargeInStartFrames = 6;
  private readonly bargeInVoiceThreshold = 0.06;
  
  // State Callbacks
  private onStateChange: (state: LiveState) => void;
  private onTranscription: (role: "user" | "model", text: string) => void;
  private onToolCall: (name: string, args: any, callback: (result: any) => void) => void;
  private onError: (error: string) => void;
  private onMemorySync?: (memories: any[]) => void;
  /** Local voice activity: the user started (true) or stopped (false) speaking. */
  private onUserSpeech?: (speaking: boolean) => void;
  /** Voice ID: the owner's words were hidden by other voices talking at the same time (ask them to repeat). */
  private onVoiceUnclear?: () => void;
  private onScreenVisionState?: (state: "idle" | "capturing" | "ready" | "error", info: { activeWindow?: string | null; error?: string | null }) => void;
  
  private connection?: AudioConnection;
  private currentState: LiveState = "disconnected";
  private isActivated = false;
  private useMicrophone = true;
  private liveReady = false;
  private outputReady = false;
  private pendingTextMessages: string[] = [];

  // Microphone lifecycle: one capture at a time, restarted if Android takes it away mid-call.
  private micReady = false;
  private micStarting = false;
  private micRestarts = 0;
  private lastMicFrameAt = 0;
  private micWatchdog: ReturnType<typeof setInterval> | null = null;
  private turnWatchdog: ReturnType<typeof setTimeout> | null = null;
  /** Speech captured before Gemini finished its setup, sent as soon as it is ready (about 2 s at most). */
  private preReadyAudio: string[] = [];
  private readonly preReadyMaxChunks = 32;
  /** The last half second while FRIDAY talks, so a barge-in keeps the first words of the user's sentence. */
  private bargeInPreRoll: string[] = [];
  private readonly bargeInPreRollChunks = 8;
  /** Voice ID: when set, only audio the phone verified as the owner's voice reaches Gemini, and only the owner can interrupt FRIDAY. */
  private gate: VoiceGateLink | null = null;
  /** Last Voice ID state ("AUTHORIZED_USER", "UNKNOWN_SPEAKER", ...), for diagnostics. */
  public voiceIdState = "";
  public voiceIdIgnored = 0;
  private silenceCache = new Map<number, string>();

  /** Timings only (no audio, no text), shown in Settings diagnostics to see where a delay comes from. */
  public readonly timings: {
    connectStartedAt: number; micReadyAt: number; liveReadyAt: number;
    lastSpeechEndAt: number; lastReplyStartAt: number; lastReplyLatencyMs: number;
    micRestarts: number; lastMicIssue: string;
  } = { connectStartedAt: 0, micReadyAt: 0, liveReadyAt: 0, lastSpeechEndAt: 0, lastReplyStartAt: 0, lastReplyLatencyMs: -1, micRestarts: 0, lastMicIssue: "" };
  /** Stage-by-stage timing of each voice turn (see TurnTimer). */
  public readonly turn = new TurnTimer();

  constructor(handlers: {
    onStateChange: (state: LiveState) => void;
    onTranscription: (role: "user" | "model", text: string) => void;
    onToolCall: (name: string, args: any, callback: (result: any) => void) => void;
    onError: (error: string) => void;
    onMemorySync?: (memories: any[]) => void;
    onScreenVisionState?: (state: "idle" | "capturing" | "ready" | "error", info: { activeWindow?: string | null; error?: string | null }) => void;
    onUserSpeech?: (speaking: boolean) => void;
    onVoiceUnclear?: () => void;
    connection?: AudioConnection;
  }) {
    this.onUserSpeech = handlers.onUserSpeech;
    this.onVoiceUnclear = handlers.onVoiceUnclear;
    this.connection = handlers.connection;
    this.onStateChange = handlers.onStateChange;
    this.onTranscription = handlers.onTranscription;
    this.onToolCall = handlers.onToolCall;
    this.onError = handlers.onError;
    this.onMemorySync = handlers.onMemorySync;
    this.onScreenVisionState = handlers.onScreenVisionState;
  }

  private setState(state: LiveState) {
    this.currentState = state;
    this.onStateChange(state);
  }

  public getState(): LiveState {
    return this.currentState;
  }

  /**
   * Pushes a compressed JPEG base64 screenshot frame directly to the live WebSocket server.
   */
  public sendVideoFrame(
    base64Data: string,
    metadata: { changeScore?: number; heartbeat?: boolean } = {},
  ) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN && this.currentState !== "disconnected") {
      this.ws.send(JSON.stringify({ type: "video", video: base64Data, ...metadata }));
    }
  }

  /** Queue typed input; it is flushed once Gemini Live is ready. */
  public sendText(text: string) {
    const trimmed = text.trim();
    if (!trimmed) return;

    if (this.currentState === "speaking") {
      this.emitConversationEvent("user_interrupted_myraa");
      this.handleInterruption();
    }

    if (
      this.ws &&
      this.ws.readyState === WebSocket.OPEN &&
      this.liveReady &&
      this.outputReady &&
      this.currentState !== "disconnected"
    ) {
      this.ws.send(JSON.stringify({ type: "text", text: trimmed }));
      return;
    }
    this.pendingTextMessages.push(trimmed);
  }

  private flushPendingTextMessages() {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN || !this.liveReady || !this.outputReady) return;
    for (const text of this.pendingTextMessages.splice(0)) {
      this.ws.send(JSON.stringify({ type: "text", text }));
    }
  }

  // Voice mode requests a microphone; typed mode keeps audio responses without one.
  public async connect(useMicrophone = true) {
    if (this.isActivated) return;
    this.isActivated = true;
    this.useMicrophone = useMicrophone;
    this.timings.connectStartedAt = Date.now();
    this.setState("connecting");
    if (useMicrophone) { try { this.gate = this.connection?.voiceGate?.() ?? null; } catch { this.gate = null; } }
    this.turn.endMs = this.gate?.endMs ?? 0;

    try {
      if (useMicrophone && !this.connection) {
        const response = await fetch('/api/permissions');
        if (!response.ok) throw new Error('Could not check microphone permissions. Text chat is still available.');
        const permissions = await response.json();
        if (permissions?.policy?.capabilities?.ACCESS_MICROPHONE?.decision === 'deny') throw new Error('Microphone access is denied in FRIDAY permissions. You can keep using text.');
        if (!this.isActivated) return;
      }
      // 1. Establish custom WebSocket server bridge
      const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
      this.ws = this.connection?.createSocket() ?? new WebSocket(`${protocol}//${window.location.host}/live`);
      this.ws.binaryType = "blob";

      this.ws.onopen = async () => {
        console.log("[Myraa] Connected to server side WS bridge");
        try {
          // Guard against early user disconnect during connection setup
          if (!this.isActivated) return;

          // Safe, cross-browser AudioContext initialization
          const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
          if (!AudioContextClass) {
            throw new Error("Holographic audio link unsupported: Web Audio API missing in browser.");
          }

          this.inputAudioCtx = new AudioContextClass({ sampleRate: 16000 });
          this.outputAudioCtx = new AudioContextClass({ sampleRate: 24000 });

          // Ensure Audio Contexts are active and resumed to bypass browser security blocks
          if (this.inputAudioCtx.state === "suspended") {
            await this.inputAudioCtx.resume().catch(() => {});
          }
          if (this.outputAudioCtx.state === "suspended") {
            await this.outputAudioCtx.resume().catch(() => {});
          }
          
          // Setup custom output Analyser & Volume Gains
          this.outputGainNode = this.outputAudioCtx.createGain();
          this.outputAnalyser = this.outputAudioCtx.createAnalyser();
          this.outputAnalyser.fftSize = 256;
          this.outputAnalyser.smoothingTimeConstant = 0.8;
          
          this.outputGainNode.connect(this.outputAnalyser);
          this.outputAnalyser.connect(this.outputAudioCtx.destination);
          this.outputReady = true;
          this.flushPendingTextMessages();
          // Optional pitch lift (Settings → Voice → Voice pitch).
          this.pitchShifter = null;
          if (this.connection) {
            const semitones = this.connection.pitch || 0;
            this.pitchShifter = semitones > 0 ? new PitchShifter(Math.pow(2, Math.min(5, semitones) / 12)) : null;
          } else void fetch("/api/app-settings")
            .then((r) => (r.ok ? r.json() : null))
            .then((s) => {
              const semitones = Number(s?.voice?.pitch) || 0;
              this.pitchShifter = semitones > 0 ? new PitchShifter(Math.pow(2, Math.min(5, semitones) / 12)) : null;
            })
            .catch(() => undefined);

          if (!this.useMicrophone) {
            this.setState("listening");
            this.flushPendingTextMessages();
            return;
          }

          await this.startMicrophone();
          if (!this.isActivated) return;
          this.startMicWatchdog();
          // Say "listening" only once both the microphone and Gemini are ready.
          if (this.liveReady) this.setState("listening");

        } catch (audioError: any) {
          console.error("Audio Context or Microphone Initialization Failed:", audioError);
          this.onError(`Permission error: ${audioError.message || "Microphone required for holographic Live link."}`);
          this.disconnect();
        }
      };

      this.ws.onmessage = async (event) => {
        try {
          const data = JSON.parse(event.data);
          if (data.type === 'voice_diagnostic') { if (data.message) this.onError(data.message); return; }
          
          // Root Error Handler message
          if (data.type === "error") {
            this.onError(data.error);
            this.disconnect();
            if (data.code === "INVALID_API_KEY") {
              // Keep the current screen stable so the user can read the error.
              // The key gate will be shown naturally on the next app launch.
              console.warn("[Myraa] Gemini rejected the configured API key.");
            }
            return;
          }

          // Handle server-side states
          if (data.type === "status") {
            console.log("[Myraa WS Status]:", data.status);
            if (data.status === "connecting_gemini") {
              // Wait for Gemini Live connection
            } else if (data.status === "connected") {
              this.liveReady = true;
              this.timings.liveReadyAt = Date.now();
              // Until the microphone is running, stay in "connecting" so the user is not told to speak into nothing.
              if (this.micReady || !this.useMicrophone) this.setState("listening");
              this.flushPreReadyAudio();
              this.flushPendingTextMessages();
            } else if (data.status === "session_closed") {
              this.disconnect();
            }
            return;
          }

          // Handle audio payload (24kHzPCM model response)
          if (data.type === "audio" && data.audio) {
            this.turn.reply(Date.now());
            if (this.currentState !== "speaking" && this.timings.lastSpeechEndAt > 0 && this.timings.lastReplyStartAt < this.timings.lastSpeechEndAt) {
              this.timings.lastReplyStartAt = Date.now();
              this.timings.lastReplyLatencyMs = this.timings.lastReplyStartAt - this.timings.lastSpeechEndAt;
            }
            this.playAudioPCMChunk(data.audio);
          }

          // Handle interruption signal (e.g. user talked over Myraa)
          if (data.type === "interrupted") {
            this.handleInterruption();
          }

          // Turn complete
          if (data.type === "turnComplete") {
            this.turn.complete(Date.now());
            // Once Friday completes speaking, change visual state back to listening
            setTimeout(() => {
              if (this.activeSources.length === 0 && this.currentState === "speaking") {
                this.setState("listening");
              }
            }, 100);
            this.armTurnWatchdog();
          }

          // Handle live captions transcription
          if (data.type === "transcription") {
            if (data.role === "user") this.turn.heard(Date.now()); else this.turn.reply(Date.now());
            this.onTranscription(data.role, data.text);
          }

          // Handle memory synchronization
          if (data.type === "memory_sync" && data.memories) {
            if (this.onMemorySync) {
              this.onMemorySync(data.memories);
            }
          }

          // Handle Tool Calling
          if (data.type === "toolCall") {
            const { callId, name, args } = data;
            this.turn.tool(Date.now());
            this.onToolCall(name, args, (result) => {
              this.turn.toolDone(Date.now());
              // Send back execution result to server bridge
              if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                this.ws.send(JSON.stringify({
                  type: "toolResponse",
                  id: callId,
                  name: name,
                  output: result
                }));
              }
            });
          }

          // Screen-vision lifecycle events from the server.
          if (data.type === "screenVisionState") {
            if (this.onScreenVisionState) {
              this.onScreenVisionState(data.state, {
                activeWindow: data.activeWindow ?? null,
                error: data.error ?? null,
              });
            }
          }

        } catch (parseError) {
          console.error("Error reading server packet:", parseError);
        }
      };

      this.ws.onerror = (wsError) => {
        console.error("WebSocket transport error:", wsError);
        this.onError("Holographic network link lost. Please check connection.");
        this.disconnect();
      };

      this.ws.onclose = () => {
        console.log("WebSocket connection closed");
        this.disconnect();
      };

    } catch (e: any) {
      console.error("Connection establish sequence failed:", e);
      this.onError(e.message || "Failed to initialize active channel.");
      this.disconnect();
    }
  }

  /**
   * Opens the microphone once and routes it into the existing input context.
   * Any previous capture is released first, so there is never more than one
   * microphone stream or processor for a call.
   */
  private async startMicrophone(): Promise<void> {
    if (this.micStarting || !this.isActivated || !this.inputAudioCtx) return;
    this.micStarting = true;
    try {
      this.releaseMicrophone();
      // Obtain the user microphone. Some embedded preview browsers do not
      // expose getUserMedia even though the main UI itself renders normally.
      if (!navigator.mediaDevices?.getUserMedia) {
        throw new Error(
          "Microphone access is unavailable in this browser. Open FRIDAY in Chrome at http://localhost:3000 and allow microphone access.",
        );
      }
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: 1,
          // Chrome/WebView voice isolation where the phone supports it (ignored elsewhere): it keeps TV, music and
          // other people out of what Gemini hears, which keeps names and commands recognised correctly.
          ...({ voiceIsolation: true } as Record<string, boolean>),
        }
      });

      // Safeguard: Check if we disconnected while waiting for user to grant mic permissions
      const ctx = this.inputAudioCtx;
      if (!this.isActivated || !ctx || !this.outputAudioCtx) {
        stream.getTracks().forEach((track) => {
          try {
            track.stop();
          } catch (e) {}
        });
        return;
      }
      if (ctx.state === "suspended") await ctx.resume().catch(() => {});

      this.micStream = stream;

      // The input analyser is kept across microphone restarts so visuals keep their reference.
      if (!this.inputAnalyser) {
        this.inputAnalyser = ctx.createAnalyser();
        this.inputAnalyser.fftSize = 256;
      }

      this.micSourceNode = ctx.createMediaStreamSource(stream);
      // Cut low rumble (fans, traffic, AC hum) below the human voice before it reaches Gemini.
      const voiceBand = ctx.createBiquadFilter();
      voiceBand.type = "highpass";
      voiceBand.frequency.value = 85;
      this.micFilterNode = voiceBand;
      this.micSourceNode.connect(voiceBand);
      voiceBand.connect(this.inputAnalyser);

      // Stream input PCM 16-bit to WS
      // A smaller buffer cuts microphone-to-live-stream latency while
      // remaining stable on ordinary Windows audio devices.
      this.micProcessorNode = ctx.createScriptProcessor(1024, 1, 1);
      voiceBand.connect(this.micProcessorNode);
      this.micProcessorNode.connect(ctx.destination);
      this.micProcessorNode.onaudioprocess = (e) => this.onMicrophoneFrame(e.inputBuffer.getChannelData(0));

      // Android can take the microphone away (a phone call, another app): get it back.
      for (const track of stream.getAudioTracks()) {
        track.onended = () => this.recoverMicrophone("microphone track ended");
      }

      this.micReady = true;
      this.lastMicFrameAt = performance.now();
      if (!this.timings.micReadyAt) this.timings.micReadyAt = Date.now();
    } finally {
      this.micStarting = false;
    }
  }

  private releaseMicrophone() {
    this.micReady = false;
    if (this.micProcessorNode) {
      this.micProcessorNode.onaudioprocess = null;
      try {
        this.micProcessorNode.disconnect();
      } catch (e) {}
      this.micProcessorNode = null;
    }
    if (this.micFilterNode) {
      try {
        this.micFilterNode.disconnect();
      } catch (e) {}
      this.micFilterNode = null;
    }
    if (this.micSourceNode) {
      try {
        this.micSourceNode.disconnect();
      } catch (e) {}
      this.micSourceNode = null;
    }
    if (this.micStream) {
      this.micStream.getTracks().forEach((track) => {
        track.onended = null;
        try {
          track.stop();
        } catch (e) {}
      });
      this.micStream = null;
    }
  }

  /** Restarts the microphone in place (same call, same contexts), a few times at most. */
  private recoverMicrophone(reason: string) {
    if (!this.isActivated || this.micStarting || !this.useMicrophone || !this.inputAudioCtx) return;
    this.timings.lastMicIssue = reason;
    if (this.micRestarts >= 5) {
      this.onError("The microphone stopped working. End the call and start it again, and check that no other app is using the microphone.");
      this.disconnect();
      return;
    }
    this.micRestarts += 1;
    this.timings.micRestarts += 1;
    console.warn("[Audio] Restarting microphone:", reason);
    this.startMicrophone().catch((error: any) => {
      if (!this.isActivated) return;
      this.onError(`Permission error: ${error?.message || "The microphone could not be restarted."}`);
      this.disconnect();
    });
  }

  /**
   * Keeps the call able to hear: resumes audio contexts Android suspended and
   * restarts the microphone when its frames stop arriving.
   */
  private startMicWatchdog() {
    if (this.micWatchdog) return;
    this.micWatchdog = setInterval(() => {
      if (!this.isActivated) return;
      for (const ctx of [this.inputAudioCtx, this.outputAudioCtx]) {
        if (ctx && ctx.state !== "running" && ctx.state !== "closed") void ctx.resume().catch(() => {});
      }
      if (!this.micReady || this.micStarting) return;
      const track = this.micStream?.getAudioTracks()[0];
      if (!track || track.readyState === "ended") this.recoverMicrophone("microphone track ended");
      else if (this.inputAudioCtx?.state === "running" && performance.now() - this.lastMicFrameAt > 4000) this.recoverMicrophone("no microphone audio for 4 s");
    }, 1500);
  }

  private onMicrophoneFrame(channelData: Float32Array) {
    this.lastMicFrameAt = performance.now();
    if (this.currentState === "disconnected") return;
    if (this.gate) { this.onGatedFrame(this.gate, channelData); return; }

    const shouldForwardMicrophone = this.updateVoiceActivity(channelData);
    if (!shouldForwardMicrophone) {
      // While FRIDAY talks, keep the last moment of audio so a barge-in does not lose its first words.
      this.bargeInPreRoll.push(base64ArrayBuffer(floatTo16BitPCM(channelData)));
      if (this.bargeInPreRoll.length > this.bargeInPreRollChunks) this.bargeInPreRoll.shift();
      return;
    }

    // Convert to base64 Int16 Little Endian PCM
    const base64 = base64ArrayBuffer(floatTo16BitPCM(channelData));
    if (!this.liveReady) {
      // Gemini is still setting up: keep what the user already says instead of dropping it.
      this.preReadyAudio.push(base64);
      if (this.preReadyAudio.length > this.preReadyMaxChunks) this.preReadyAudio.shift();
      return;
    }
    if (this.bargeInPreRoll.length) {
      const preRoll = this.bargeInPreRoll.splice(0);
      // Only a real barge-in sends it; otherwise it is FRIDAY's own voice and is dropped.
      if (this.userSpeaking) for (const chunk of preRoll) this.sendAudio(chunk);
    }
    this.sendAudio(base64);
  }

  /**
   * Voice ID path: the phone decides what Gemini hears. Owner speech is forwarded (interrupting
   * FRIDAY if she is talking); anyone else, the TV and FRIDAY's own echo become silence, and never
   * stop her reply.
   */
  private onGatedFrame(gate: VoiceGateLink, channelData: Float32Array) {
    const myraaOutputActive = this.currentState === "speaking" || this.activeSources.length > 0;
    let result: { a?: [string, string | number][]; e?: [string, number, number?, number?][]; s?: string; fail?: string };
    try { result = JSON.parse(gate.feed(base64ArrayBuffer(floatTo16BitPCM(channelData)), myraaOutputActive ? 2 : 0)); }
    catch { result = { fail: "Voice ID did not answer" }; }
    if (result.fail) {
      // Keep the call working without Voice ID; diagnostics show why.
      this.timings.lastMicIssue = `Voice ID off for this call: ${result.fail}`;
      this.voiceIdState = "ERROR";
      try { gate.stop(); } catch { /* stopped */ }
      this.gate = null;
      return;
    }
    if (result.s) this.voiceIdState = result.s;
    let ownerTurnEnded = false, unclear = false, endAgoMs = 0;
    const now = Date.now();
    if ((result.s === "SPEECH_DETECTED" || result.s === "VERIFYING_SPEAKER") && !this.userSpeaking) this.turn.start(now);
    for (const event of result.e || []) {
      const name = event[0], agoMs = Number(event[2]) || 0, checkMs = Number(event[3]);
      if (name === "owner") {
        this.turn.verified(now - agoMs, Number.isFinite(checkMs) ? checkMs : -1);
        if (!this.userSpeaking) {
          this.userSpeaking = true;
          if (myraaOutputActive) {
            this.emitConversationEvent("user_interrupted_myraa");
            this.handleInterruption();
          }
          this.micRestarts = 0;
          this.emitConversationEvent("user_started_speaking");
          this.onUserSpeech?.(true);
        }
      } else if (name === "end" || name === "lost") {
        ownerTurnEnded = true; endAgoMs = agoMs;
      } else if (name === "unknown") this.voiceIdIgnored++;
      else if (name === "unclear") { this.voiceIdIgnored++; unclear = true; }
    }
    const outputNow = this.currentState === "speaking" || this.activeSources.length > 0;
    for (const [kind, value] of result.a || []) {
      if (kind === "o" && typeof value === "string") this.forwardAudio(value);
      // Silence keeps Gemini's timing (it hears when the owner stopped); nothing is sent while FRIDAY talks, as before.
      else if (kind === "z" && typeof value === "number" && value > 0 && !outputNow) this.forwardAudio(this.silence(value));
    }
    if (ownerTurnEnded && this.userSpeaking) {
      // The owner stopped talking (other people may still be talking): tell Gemini the turn is over
      // now, after the owner's audio, instead of waiting for the room to go quiet.
      this.userSpeaking = false;
      this.timings.lastSpeechEndAt = Date.now();
      this.emitConversationEvent("user_stopped_speaking");
      this.onUserSpeech?.(false);
      const sent = this.liveReady && this.ws && this.ws.readyState === WebSocket.OPEN;
      if (sent) this.ws!.send(JSON.stringify({ type: "audioEnd" }));
      // The owner's last word was the endpoint's quiet time before the gate's "end".
      const endAt = Date.now() - endAgoMs;
      this.turn.stopped(endAt - this.turn.endMs, endAt, sent ? Date.now() : 0);
    }
    if (unclear && !outputNow) this.onVoiceUnclear?.();
  }

  private silence(samples: number): string {
    let cached = this.silenceCache.get(samples);
    if (!cached) {
      cached = base64ArrayBuffer(new ArrayBuffer(samples * 2));
      if (this.silenceCache.size < 32) this.silenceCache.set(samples, cached);
    }
    return cached;
  }

  private forwardAudio(base64: string) {
    if (!this.liveReady) {
      this.preReadyAudio.push(base64);
      if (this.preReadyAudio.length > this.preReadyMaxChunks) this.preReadyAudio.shift();
      return;
    }
    this.sendAudio(base64);
  }

  private sendAudio(base64: string) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ audio: base64 }));
    }
  }

  private flushPreReadyAudio() {
    for (const chunk of this.preReadyAudio.splice(0)) this.sendAudio(chunk);
  }

  /** If the end of FRIDAY's playback is never reported (audio output suspended), do not stay stuck in "speaking". */
  private armTurnWatchdog() {
    if (this.turnWatchdog) clearTimeout(this.turnWatchdog);
    const ctx = this.outputAudioCtx;
    const remainingMs = ctx ? Math.max(0, (this.nextStartTime - ctx.currentTime) * 1000) : 0;
    this.turnWatchdog = setTimeout(() => {
      this.turnWatchdog = null;
      const out = this.outputAudioCtx;
      if (!this.isActivated || this.currentState !== "speaking" || !out) return;
      if (out.currentTime > this.nextStartTime + 1) this.handleInterruption();
    }, remainingMs + 1500);
  }

  // Interruption triggers: stops all active audio players immediately
  private handleInterruption() {
    console.log("[Audio] Interruption signal received; flushing play logs.");
    
    // Stop all playing nodes
    this.activeSources.forEach((source) => {
      try {
        source.stop();
      } catch (err) {
        // Already finished or stopped
      }
    });
    this.activeSources = [];
    this.nextStartTime = 0;
    this.outputSpeechStartedAt = 0;
    if (this.turnWatchdog) { clearTimeout(this.turnWatchdog); this.turnWatchdog = null; }
    
    // Set state back to user listening
    this.setState("listening");
  }

  /**
   * Lightweight local VAD for turn-taking. It never calls a model: it only
   * emits start/stop events and flushes buffered TTS immediately on barge-in.
   */
  private updateVoiceActivity(samples: Float32Array): boolean {
    let energy = 0;
    for (let i = 0; i < samples.length; i++) energy += samples[i] * samples[i];
    const rms = Math.sqrt(energy / Math.max(1, samples.length));
    const timestamp = performance.now();
    const myraaOutputActive = this.currentState === "speaking" || this.activeSources.length > 0;

    // Full-duplex speaker playback can leak back into the microphone even with
    // browser echo cancellation enabled. Do not feed that echo to Gemini. A
    // short guard protects the opening words; after it, a louder sustained
    // signal is still accepted as a genuine human barge-in.
    if (
      myraaOutputActive
      && this.outputSpeechStartedAt > 0
      && timestamp - this.outputSpeechStartedAt < this.bargeInGuardMs
    ) {
      this.voiceFrames = 0;
      return false;
    }

    const outputRms = myraaOutputActive ? this.readOutputRms() : 0;
    const threshold = myraaOutputActive
      ? Math.max(
          this.bargeInVoiceThreshold,
          // A short knock, cough or steady room noise must not cut FRIDAY off: stay well above the room's level.
          Math.min(0.12, this.noiseFloor * 3.5),
          Math.min(0.1, outputRms * 0.32 + 0.012),
        )
      : Math.max(this.voiceThreshold, Math.min(0.06, this.noiseFloor * 2.2));
    const requiredFrames = myraaOutputActive ? this.bargeInStartFrames : this.voiceStartFrames;

    if (rms >= threshold) {
      this.lastVoiceAt = timestamp;
      this.voiceFrames += 1;
      if (!this.userSpeaking && this.voiceFrames >= requiredFrames) {
        const interruptedMyraa = myraaOutputActive;
        this.userSpeaking = true;
        if (interruptedMyraa) {
          this.emitConversationEvent("user_interrupted_myraa", rms);
          this.handleInterruption();
        }
        this.micRestarts = 0;
        this.emitConversationEvent("user_started_speaking", rms);
        this.onUserSpeech?.(true);
        this.turn.start(Date.now());
      }
      return !myraaOutputActive || this.userSpeaking;
    }

    // While FRIDAY talks, a brief dip between syllables should not restart the barge-in count.
    this.voiceFrames = myraaOutputActive ? Math.max(0, this.voiceFrames - 2) : 0;
    if (!myraaOutputActive && !this.userSpeaking) this.noiseFloor = this.noiseFloor * 0.98 + rms * 0.02;
    if (this.userSpeaking && timestamp - this.lastVoiceAt >= this.voiceStopDelayMs) {
      this.userSpeaking = false;
      this.timings.lastSpeechEndAt = Date.now();
      // No Voice ID: Gemini's own end-of-speech detection ends the turn; time it from the last loud frame.
      this.turn.stopped(Date.now() - (timestamp - this.lastVoiceAt), 0, 0);
      this.emitConversationEvent("user_stopped_speaking", rms);
      this.onUserSpeech?.(false);
    }
    return !myraaOutputActive || this.userSpeaking;
  }

  private readOutputRms(): number {
    if (!this.outputAnalyser) return 0;
    const samples = new Uint8Array(this.outputAnalyser.fftSize);
    this.outputAnalyser.getByteTimeDomainData(samples);
    let energy = 0;
    for (let i = 0; i < samples.length; i++) {
      const normalized = (samples[i] - 128) / 128;
      energy += normalized * normalized;
    }
    return Math.sqrt(energy / Math.max(1, samples.length));
  }

  private emitConversationEvent(event: string, rms?: number) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    this.ws.send(JSON.stringify({ type: "conversationEvent", event, rms }));
  }

  // Direct raw PCM chunk scheduled playback at 24kHz
  private playAudioPCMChunk(base64Audio: string) {
    if (!this.outputAudioCtx || !this.outputGainNode) return;

    try {
      if (this.currentState !== "speaking" || this.activeSources.length === 0) {
        this.outputSpeechStartedAt = performance.now();
      }
      this.setState("speaking");
      const uint8Array = base64ToUint8Array(base64Audio);
      const raw = pcm16ToFloats(uint8Array);
      const floats = this.pitchShifter ? this.pitchShifter.process(raw) : raw;

      // Create AudioBuffer of 24000Hz (the exact playback sample rate of Gemini outputs)
      const buffer = this.outputAudioCtx.createBuffer(1, floats.length, 24000);
      buffer.getChannelData(0).set(floats);

      // Create Buffer source
      const source = this.outputAudioCtx.createBufferSource();
      source.buffer = buffer;

      // Connect source to gain which is routed to analyser & speakers
      source.connect(this.outputGainNode);

      const currentTime = this.outputAudioCtx.currentTime;
      
      // Gapless scheduler sync
      if (this.nextStartTime < currentTime) {
        // Start fresh: 30ms ahead to bridge schedule timing
        this.nextStartTime = currentTime + 0.03;
      }

      source.start(this.nextStartTime);
      this.turn.playing(Date.now() + Math.max(0, (this.nextStartTime - currentTime) * 1000));
      this.nextStartTime += buffer.duration;

      // Keep reference to handle real-time interruptions
      source.onended = () => {
        const index = this.activeSources.indexOf(source);
        if (index > -1) {
          this.activeSources.splice(index, 1);
        }
        
        // If there are no more active play nodes, revert state back to listening
        if (this.activeSources.length === 0 && this.currentState === "speaking") {
          this.outputSpeechStartedAt = 0;
          this.setState("listening");
        }
      };

      this.activeSources.push(source);

    } catch (playbackError) {
      console.error("PCM Chunk buffering/playback failed:", playbackError);
    }
  }

  // Fully cleanup and release microphones & connection sockets
  public disconnect() {
    this.isActivated = false;
    this.liveReady = false;
    this.outputReady = false;
    this.micReady = false;
    this.micStarting = false;
    if (this.micWatchdog) { clearInterval(this.micWatchdog); this.micWatchdog = null; }
    if (this.turnWatchdog) { clearTimeout(this.turnWatchdog); this.turnWatchdog = null; }
    this.preReadyAudio = [];
    this.bargeInPreRoll = [];
    if (this.gate) { try { this.gate.stop(); } catch { /* already stopped */ } this.gate = null; }
    this.setState("disconnected");

    // Close WS socket
    if (this.ws) {
      try {
        this.ws.close();
      } catch (e) {}
      this.ws = null;
    }

    // Stop and release the microphone stream and its routing nodes
    this.releaseMicrophone();

    // Close Audio contexts
    if (this.inputAudioCtx) {
      try {
        this.inputAudioCtx.close();
      } catch (e) {}
      this.inputAudioCtx = null;
    }

    if (this.outputAudioCtx) {
      try {
        this.outputAudioCtx.close();
      } catch (e) {}
      this.outputAudioCtx = null;
    }

    this.activeSources = [];
    this.nextStartTime = 0;
    this.userSpeaking = false;
    this.voiceFrames = 0;
    this.lastVoiceAt = 0;
    this.outputSpeechStartedAt = 0;
    this.pendingTextMessages = [];
    this.inputAnalyser = null;
    this.outputAnalyser = null;
    this.outputGainNode = null;
  }
}
