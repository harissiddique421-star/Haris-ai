import type { AudioSocket } from '../lib/audio';
import { MOBILE_TOOL } from './actions';

export interface MobileLiveBridge { startLive(id: string): void; endLive(): void; }
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
    this.timeout = setTimeout(() => this.fail('Live voice timed out while connecting. Check your connection and Live model access.'), 35000);
    bridge.startLive(this.id);
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
        inputAudioTranscription: {}, outputAudioTranscription: {}, tools: [{ functionDeclarations: [MOBILE_TOOL] }],
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
        if (data.goAway) this.fail('This Live session is ending. Tap Talk to start a fresh call.');
      }).catch(() => this.fail('The Live audio connection returned an unreadable message. Reconnect to continue.'));
    };
    socket.onerror = () => this.fail('The Live audio connection failed. Check your network and Gemini Live access.');
    socket.onclose = event => {
      if (this.readyState === 3) return;
      if (event.code !== 1000) this.fail('Gemini Live disconnected. Check your key, quota and Live model in Settings.');
      else { this.packet({ type: 'status', status: 'session_closed' }); this.close(); }
    };
  };
  send(raw: string) {
    if (!this.ready || this.readyState !== 1 || this.socket?.readyState !== WebSocket.OPEN) return;
    const data = JSON.parse(raw);
    if (data.audio) this.socket.send(JSON.stringify({ realtimeInput: { audio: { data: data.audio, mimeType: 'audio/pcm;rate=16000' } } }));
    else if (data.type === 'text' && data.text) this.socket.send(JSON.stringify({ realtimeInput: { text: data.text } }));
    else if (data.type === 'toolResponse') {
      const call = this.toolCalls.get(data.id);
      if (call && call.name === data.name) this.socket.send(JSON.stringify({ toolResponse: { functionResponses: [{ ...(call.id ? { id: call.id } : {}), name: call.name, response: data.output }] } }));
    }
    // No desktop tools or unrestricted phone UI authority.
  }
  close() {
    if (this.readyState === 3) return;
    this.readyState = 3; this.ready = false; clearTimeout(this.timeout);
    window.removeEventListener('myraa:mobile-live', this.nativeEvent);
    if (this.socket) { this.socket.onopen = null; this.socket.onmessage = null; this.socket.onclose = null; this.socket.onerror = null; this.socket.close(); this.socket = null; }
    this.bridge.endLive();
  }
}
