import type { AudioSocket } from '../lib/audio';
import { MOBILE_TOOL } from './actions';

export interface MobileLiveBridge { startLive(id: string): void; endLive(): void; reconnectLive?(id: string): void; }

/**
 * Session resumption: Google sends a handle during the call; an automatic reconnect (session ending,
 * dropped connection) passes it back so the conversation carries on. A call the user starts is fresh.
 * Turned off for good if Google ever refuses the setting, so calls still start.
 */
/**
 * handoff: an automatic reconnect is replacing this connection, so closing it must not end the phone's
 * call service (which keeps FRIDAY running while she is in the background); the next connection reuses it.
 */
export const liveResume = { handle: '', resuming: false, handoff: false, off: (() => { try { return localStorage.getItem('friday.resumeOff') === '1'; } catch { return false; } })() };
/**
 * Microphone mute for the Live call: while muted no microphone audio leaves the phone, and Google is
 * told the audio stream paused, so it does not wait for the rest of a sentence. Turned off with each new call.
 */
export const liveMic = { muted: false, ended: false };
/** Adapt the native short-lived token and Gemini Live protocol to the PC audio engine. */
export class MobileLiveTransport implements AudioSocket {
  readyState = 0;
  binaryType = 'blob';
  onopen: AudioSocket['onopen'] = null;
  onmessage: AudioSocket['onmessage'] = null;
  onerror: AudioSocket['onerror'] = null;
  onclose: AudioSocket['onclose'] = null;
  private socket: WebSocket | null = null;
  private ready = false;
  private toolCalls = new Map<string, { id?: string; name: string }>();
  private id = crypto.randomUUID();
  private timeout: ReturnType<typeof setTimeout>;
  private receiveChain = Promise.resolve();
  constructor(private bridge: MobileLiveBridge) {
    window.addEventListener('myraa:mobile-live', this.nativeEvent);
    this.timeout = setTimeout(() => this.fail(navigator.onLine === false ? 'No internet connection. Connect to the internet and try again.' : 'Live voice timed out while connecting. Your network may be slow; check your connection and Live model access.'), 20000);
    if (liveResume.handoff && bridge.reconnectLive) bridge.reconnectLive(this.id); else bridge.startLive(this.id);
    liveResume.handoff = false;
  }
  private packet(value: unknown) { if (this.readyState !== 3) this.onmessage?.({ data: JSON.stringify(value) }); }
  private fail(message: string) { this.packet({ type: 'error', error: message }); this.close(); }
  private nativeEvent = (event: Event) => {
    const value = (event as CustomEvent).detail;
    if (!value || value.id !== this.id || this.readyState === 3) return;
    if (value.type === 'live-error') { this.fail(value.message || 'Gemini Live could not start.'); return; }
    if (value.type !== 'live-token' || this.socket) return;
    if (typeof value.token !== 'string' || !value.token.startsWith('auth_tokens/') || !/^[A-Za-z0-9._-]{1,100}$/.test(value.model)) {
      this.fail('The Live session credential was invalid. Try connecting again.'); return;
    }
    const socket = new WebSocket('wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=' + encodeURIComponent(value.token));
    this.socket = socket;
    socket.onopen = () => {
      if (this.readyState === 3) { socket.close(); return; }
      this.readyState = 1;
      socket.send(JSON.stringify({ setup: {
        model: 'models/' + value.model,
        generationConfig: { responseModalities: ['AUDIO'], speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: value.voice || 'Aoede' } } } },
        systemInstruction: { parts: [{ text: value.instruction || 'You are MYRAA. Speak warmly and naturally in the user’s language.' }] },
        // Hear normal and short commands right away (the phone already keeps FRIDAY's own voice and small noises
        // from interrupting her while she talks), and wait 0.8 s of silence so a brief pause doesn't end the sentence.
        realtimeInputConfig: { automaticActivityDetection: { startOfSpeechSensitivity: 'START_SENSITIVITY_HIGH', prefixPaddingMs: 200, silenceDurationMs: 800 } },
        inputAudioTranscription: {}, outputAudioTranscription: {}, tools: [{ functionDeclarations: [MOBILE_TOOL] }, { googleSearch: {} }],
        ...(liveResume.off ? {} : { sessionResumption: liveResume.resuming && liveResume.handle ? { handle: liveResume.handle } : {} }),
      } }));
      this.onopen?.({});
    };
    socket.onmessage = event => {
      // Blob decoding must preserve arrival order, especially interruption vs audio.
      this.receiveChain = this.receiveChain.then(async () => {
        if (this.readyState === 3) return;
        const raw = typeof event.data === 'string' ? event.data : await (event.data as Blob).text();
        const data = JSON.parse(raw);
        if (data.error) { this.fail('Gemini Live rejected the session. Check the key, quota and Live model access in Settings.'); return; }
        if (data.setupComplete) { this.ready = true; clearTimeout(this.timeout); this.packet({ type: 'status', status: 'connected' }); }
        for (const call of data.toolCall?.functionCalls || []) {
          const callId = call.id || crypto.randomUUID();
          if (this.toolCalls.has(callId)) continue;
          if (this.toolCalls.size >= 128) { this.fail('Too many actions in this call. Reconnect to continue.'); return; }
          this.toolCalls.set(callId, { id: call.id, name: call.name });
          this.packet({ type: 'toolCall', callId, name: call.name, args: call.args || {} });
        }
        const content = data.serverContent;
        if (content?.interrupted) this.packet({ type: 'interrupted' });
        if (content?.inputTranscription?.text) this.packet({ type: 'transcription', role: 'user', text: content.inputTranscription.text });
        if (content?.outputTranscription?.text) this.packet({ type: 'transcription', role: 'model', text: content.outputTranscription.text });
        for (const part of content?.modelTurn?.parts || []) {
          if (part.inlineData?.mimeType?.startsWith('audio/pcm') && part.inlineData.data) this.packet({ type: 'audio', audio: part.inlineData.data });
        }
        if (content?.turnComplete) this.packet({ type: 'turnComplete' });
        const update = data.sessionResumptionUpdate;
        if (update?.resumable && typeof update.newHandle === 'string' && update.newHandle.length < 4096) liveResume.handle = update.newHandle;
        if (data.goAway) this.fail('This Live session is ending. Tap Talk to start a fresh call.');
      }).catch(() => this.fail('The Live audio connection returned an unreadable message. Reconnect to continue.'));
    };
    socket.onerror = () => this.fail('The Live audio connection failed. Check your network and Gemini Live access.');
    socket.onclose = event => {
      if (this.readyState === 3) return;
      if (event.code !== 1000) {
        // Show Google's own reason so a rejected setup, quota or model problem can be told apart.
        const reason = String(event.reason || '').replace(/\s+/g, ' ').slice(0, 160);
        const when = this.ready ? 'during the call' : 'while starting';
        // Google refused the resumption setting itself: stop asking for it so calls keep starting.
        if (!this.ready && !liveResume.off && (event.code === 1007 || event.code === 1008) && /resum/i.test(reason)) {
          liveResume.off = true; liveResume.handle = ''; try { localStorage.setItem('friday.resumeOff', '1'); } catch { /* private mode */ }
        }
        this.fail(`Gemini Live disconnected ${when} (code ${event.code}${reason ? ': ' + reason : ''}). Check your key, quota and Live model in Settings.`);
      }
      else { this.packet({ type: 'status', status: 'session_closed' }); this.close(); }
    };
  };
  send(raw: string) {
    if (!this.ready || this.readyState !== 1 || this.socket?.readyState !== WebSocket.OPEN) return;
    const data = JSON.parse(raw);
    if (data.audio) {
      if (liveMic.muted) { if (!liveMic.ended) { liveMic.ended = true; this.socket.send(JSON.stringify({ realtimeInput: { audioStreamEnd: true } })); } return; }
      liveMic.ended = false;
      this.socket.send(JSON.stringify({ realtimeInput: { audio: { data: data.audio, mimeType: 'audio/pcm;rate=16000' } } }));
    }
    else if (data.type === 'text' && data.text) this.socket.send(JSON.stringify({ realtimeInput: { text: data.text } }));
    else if (data.type === 'video' && typeof data.video === 'string' && data.video.length > 0 && data.video.length < 600000) this.socket.send(JSON.stringify({ realtimeInput: { video: { data: data.video, mimeType: 'image/jpeg' } } }));
    else if (data.type === 'toolResponse') {
      const call = this.toolCalls.get(data.id);
      if (call && call.name === data.name) this.socket.send(JSON.stringify({ toolResponse: { functionResponses: [{ ...(call.id ? { id: call.id } : {}), name: call.name, response: data.output }] } }));
    }
    // Only audio, text, camera/screen JPEG frames and mobile_action results; no desktop tools.
  }
  close() {
    if (this.readyState === 3) return;
    this.readyState = 3; this.ready = false; clearTimeout(this.timeout);
    window.removeEventListener('myraa:mobile-live', this.nativeEvent);
    if (this.socket) { this.socket.onopen = null; this.socket.onmessage = null; this.socket.onclose = null; this.socket.onerror = null; this.socket.close(); this.socket = null; }
    if (!liveResume.handoff) this.bridge.endLive();
  }
}
