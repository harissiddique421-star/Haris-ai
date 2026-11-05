import './mobile.css';
import { MyraaAudioSession, type LiveState } from '../lib/audio';
import { MobileLiveTransport } from './LiveTransport';
import { parseMobileAction } from './actions';
import { CharacterSystem } from '../character/core/CharacterSystem';
import { evelynConfig } from '../character/config/characters/evelyn';
import { qualityProfile, withQuality } from '../character/core/quality';
import { setCharacterRealism } from '../character/materials/AnimeMaterial';
import { parseCharacterCommand } from '../../shared/character/commands';

interface Bridge { getState(): string; configure(json: string): void; checkConnection(json: string): void; chat(text: string, id: string): void; cancel(): void; startLive(id: string): void; endLive(): void; speak(text: string): void; stopSpeech(): void; clearHistory(): void; clearKey(): void; openSettings(): void; mobileAction(id: string, name: string, args: string): void; openFloatingSettings(): void; cancelMobileAction(id: string): void; }
declare global { interface Window { MyraaNative?: Bridge; MyraaEvents?: (event: any) => void; __mobileDiagnostics?: () => unknown; } }
const bridge = window.MyraaNative;
let state: any = bridge ? JSON.parse(bridge.getState()) : { history: [], model: 'gemini-3.5-flash', quality: 'low', language: 'hi-IN', keyConfigured: false };
let system: CharacterSystem | null = null;
let generation = 0;
let busy = false;
let requestId = '';
let lastReply = '';
let pendingText = '';
let foreground = true;
let backgroundLive = false;
let permissionPrompt = false;
const actionRequests = new Map<string, { resolve: (result: any) => void; timer: ReturnType<typeof setTimeout> }>();
function runMobileAction(name: string, args: unknown): Promise<any> {
  if (!bridge?.mobileAction) return Promise.resolve({ status: 'unavailable', message: 'Mobile actions require the updated Android app.' });
  const id = crypto.randomUUID();
  return new Promise(resolve => {
    const timer = setTimeout(() => { actionRequests.delete(id); bridge.cancelMobileAction(id); resolve({ status: 'timeout', message: 'The Android action timed out and was cancelled. Try again when ready to choose a recipient or allow permission.' }); }, 60000);
    actionRequests.set(id, { resolve, timer }); bridge.mobileAction(id, name, JSON.stringify(args));
  });
}
let fullBody = true;
let liveSession: MyraaAudioSession | null = null;
let liveCaption: { role: string; body: HTMLElement } | null = null;
function cancelMobileActions() { for (const [id, request] of actionRequests) { clearTimeout(request.timer); bridge?.cancelMobileAction(id); request.resolve({ status: 'cancelled', message: 'Action cancelled.' }); } actionRequests.clear(); }
function stopVoice() { backgroundLive = false; const session = liveSession; liveSession = null; if (session) cancelMobileActions(); session?.disconnect(); bridge?.endLive(); }
function liveState(value: LiveState) {
  const active = value !== 'disconnected';
  if (value === 'listening') notice('Live voice connected. Speak naturally.');
  el('voice').setAttribute('aria-pressed', String(active));
  el('voice').querySelector('span')!.textContent = active ? 'End' : 'Talk';
  el('presence').textContent = value === 'connecting' ? 'Connecting live…' : value === 'speaking' ? 'Speaking with you' : value === 'listening' ? 'Live · listening to you' : 'Here with you';
  system?.setFrameInput({ activity: value === 'speaking' ? 'talking' : value === 'listening' ? 'listening' : 'idle',
    outputAnalyser: liveSession?.outputAnalyser ?? null, inputAnalyser: liveSession?.inputAnalyser ?? null });
}
function liveTranscription(role: 'user' | 'model', text: string) {
  if (!liveCaption || liveCaption.role !== role) {
    append(role, ''); liveCaption = { role, body: el('messages').lastElementChild!.querySelector('p')! };
  }
  liveCaption.body.textContent = ((liveCaption.body.textContent || '') + text).slice(-8000);
  el('messages').scrollTop = el('messages').scrollHeight;
  if (role === 'model') lastReply = liveCaption.body.textContent;
}

const app = document.querySelector('#app')!;
app.innerHTML = `
  <header><div class="brand"><span class="brand-mark">M</span><div>MYRAA <span class="version">MOBILE</span><small>Your companion, wherever you are.</small></div></div><button id="settings" aria-label="Open settings">⚙</button></header>
  <main><section class="portrait" aria-label="MYRAA character"><div class="halo"></div><div id="avatar"></div><div class="avatar-status" id="avatar-status" role="status">Loading your character…</div><span class="presence"><i></i> <span id="presence">Here with you</span></span><div class="gestures"><button id="wave">Wave</button><button id="sit">Sit</button><button id="stand">Stand</button><button id="happy">Smile</button><button id="view">Portrait</button></div></section>
  <section class="conversation"><div class="section-heading"><span>OUR CONVERSATION</span><button id="history-toggle">History</button></div><div id="messages" role="log" aria-live="polite"></div><div id="notice" class="notice" role="status"></div></section></main>
  <footer><div class="composer"><button id="voice" aria-label="Voice conversation" aria-pressed="false" title="Start voice conversation"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8"/></svg><span>Talk</span></button><textarea id="input" rows="1" maxlength="8000" placeholder="Talk to MYRAA…" aria-label="Message"></textarea><button id="send" aria-label="Send message">↑</button></div><div class="footer-actions"><span id="connection">Private on this device · Gemini for replies</span><button id="stop">Stop</button><button id="speak" aria-label="Read last reply aloud">Read aloud</button></div></footer>
  <dialog id="settings-panel"><form method="dialog"><div class="dialog-heading"><h2>Make yourself at home</h2><button aria-label="Close settings">×</button></div></form><p class="muted">Your key and conversations stay on this device. Messages and recent context are sent directly to Google when you ask for a reply.</p><label>Gemini API key<input id="key" type="password" autocomplete="off" placeholder="Enter your own key"></label><p id="key-state" class="muted"></p><label>Text model<input id="model" autocomplete="off" placeholder="Model identifier"></label><label>Live voice model<input id="live-model" autocomplete="off" placeholder="gemini-3.1-flash-live-preview"></label><label>Language<select id="language"><option value="hi-IN">Hindi / Hinglish</option><option value="en-IN">English (India)</option><option value="en-US">English</option></select></label><label>Graphics<select id="quality"><option value="low">Low · save battery</option><option value="balanced">Medium · balanced</option><option value="high">High · sharper</option></select></label><label class="check"><input id="spoken" type="checkbox"> Read text replies with Android voice</label><button id="save" class="primary">Save settings</button><button id="check-connection">Check key &amp; model</button><p id="settings-status" class="muted" role="status" aria-live="polite"></p><div class="privacy-actions"><button id="remove-key">Remove API key</button><button id="clear-history">Clear conversation history</button><button id="android-settings">Android app settings</button><button id="floating-settings">Enable floating voice controls</button></div><p class="muted">Talk opens an in-app Gemini Live audio call. Your microphone streams directly to Gemini, which generates MYRAA’s voice. Android microphone permission is required. Read aloud is a separate text-reading option. During an active call, the microphone can stay on while you use other apps; stop it from MYRAA or its notification. Floating voice controls allow app switching while MYRAA is behind another app. Contacts stay on this device and are read only to resolve a message recipient. WhatsApp opens a draft; you tap Send. No screen capture or unrestricted device control.</p><output id="diagnostics" class="muted"></output></dialog>`;
const el = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const notice = (message: string) => { el('notice').textContent = message; };
function append(role: string, text: string) {
  const item = document.createElement('article'); item.className = `message ${role}`;
  const label = document.createElement('small'); label.textContent = role === 'user' ? 'YOU' : 'MYRAA';
  const body = document.createElement('p'); body.textContent = text;
  item.append(label, body); el('messages').appendChild(item); el('messages').scrollTop = el('messages').scrollHeight;
}
function renderHistory(all = false) { el('messages').replaceChildren(); for (const turn of (all ? state.history : state.history.slice(-4))) append(turn.role, turn.text); }
function refreshSettings() {
  el<HTMLInputElement>('model').value = state.model;
  el<HTMLInputElement>('live-model').value = state.liveModel || 'gemini-3.1-flash-live-preview';
  el<HTMLSelectElement>('quality').value = state.quality;
  el<HTMLSelectElement>('language').value = state.language;
  el<HTMLInputElement>('spoken').checked = state.spokenReplies === true;
  refreshKeyStatus();
}
function refreshKeyStatus() {
  el('key-state').textContent = state.keyConfigured ? `A key is saved in Android Keystore protected storage. ${state.keyVerified ? 'Google accepted it for this model.' : 'Use Check key & model to verify access.'}` : 'No API key configured. Add yours to chat.';
}
function settingsProgress(active: boolean, message: string) {
  el('settings-status').textContent = message;
  for (const control of document.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLButtonElement>('#settings-panel input,#settings-panel select,#save,#check-connection')) control.disabled = active;
}
async function loadAvatar() {
  const mine = ++generation; system?.dispose(); system = null;
  el('avatar').replaceChildren(); const canvas = document.createElement('canvas'); el('avatar').appendChild(canvas);
  el('avatar-status').textContent = 'Loading your character…';
  try {
    const base = qualityProfile(state.quality);
    const quality = { ...base, maxPixelRatio: state.quality === 'low' ? 0.85 : state.quality === 'balanced' ? 1 : 1.5, maxTextureSize: state.quality === 'high' ? 2048 : state.quality === 'low' ? 512 : 1024, targetFps: 30, shadows: false, cloth: false, antialias: false };
    const instance = new CharacterSystem({ canvas, config: withQuality(evelynConfig, quality), quality,
      onError: error => { if (mine === generation) notice(`Avatar: ${error.message}`); } });
    system = instance; const box = el('avatar').getBoundingClientRect(); instance.resize(box.width, box.height);
    setCharacterRealism(0.65); await instance.load(); if (mine !== generation) return;
    if (fullBody) instance.frameFullBody();
    instance.setEyeTracking(true); if (foreground && !document.hidden) instance.start();
    el('avatar-status').textContent = '';
  } catch (error) { if (mine === generation) el('avatar-status').textContent = `Avatar could not load. ${String(error)}`; }
}
window.MyraaEvents = event => {
  if (event.type === 'mobile-action-result') {
    const request = actionRequests.get(event.id); if (request) { clearTimeout(request.timer); actionRequests.delete(event.id); request.resolve(event.result); }
    notice(event.result?.message || 'Android action finished.'); return;
  }
  if (event.type === 'live-token') backgroundLive = !!event.backgroundLive;
  if (event.type === 'live-token' || event.type === 'live-error') { window.dispatchEvent(new CustomEvent('myraa:mobile-live', { detail: event })); return; }
  if (event.type === 'settings') {
    settingsProgress(event.status === 'checking', event.message || '');
    if (event.state) {
      const changedQuality = event.state.quality !== state.quality;
      state = event.state;
      if (event.status === 'saved' || event.status === 'cleared') el<HTMLInputElement>('key').value = '';
      if (event.status === 'checked') refreshKeyStatus();
      else refreshSettings();
      if (event.status === 'saved') {
        el<HTMLDialogElement>('settings-panel').close();
        notice(event.message || 'Settings saved.');
        if (changedQuality) void loadAvatar();
      }
    }
  }
  if (event.type === 'state') { state = event.state; refreshSettings(); renderHistory(); }
  if (event.type === 'reply' && event.id === requestId) {
    busy = false; lastReply = event.text; append('model', event.text); notice('');
    pendingText = '';
    state = bridge ? JSON.parse(bridge.getState()) : state;
    el('presence').textContent = 'Here with you'; system?.setFrameInput({ activity: 'idle', emotion: 'happy' });
  }
  if (event.type === 'error') {
    if (event.id && event.id !== requestId) return;
    stopVoice(); notice(event.message);
    if (event.service === 'text' || event.id === requestId) {
      busy = false;
      if (pendingText) el<HTMLTextAreaElement>('input').value = pendingText;
      el('presence').textContent = 'Here with you'; system?.setFrameInput({ activity: 'idle', emotion: 'confused' });
    }
  }
  if (event.type === 'speech' && !liveSession) system?.setFrameInput({ activity: event.active ? 'talking' : 'idle' });
};
function send() {
  const input = el<HTMLTextAreaElement>('input'); const text = input.value.trim(); if (!text || busy) return;
  const mobile = parseMobileAction(text);
  if (mobile) { input.value = ''; append('user', text); void runMobileAction('mobile_action', mobile).then(result => { append('model', result.message); notice(result.message); }); return; }
  if (liveSession && liveSession.getState() !== 'disconnected') { liveSession.sendText(text); input.value = ''; append('user', text); return; }
  const command = parseCharacterCommand(text);
  if (command) {
    input.value = ''; append('user', text);
    if (command.side) { notice('Desktop-side placement is available on Windows.'); return; }
    if (command.action === 'next_character') { notice('This Android build includes Evelyn. Additional model import is not implemented yet.'); return; }
    if (command.action === 'wave' || command.action === 'dance') system?.playBehaviour(command.action);
    else if (command.action === 'sit') system?.sit({ seat: 'chair', style: 'upright' });
    else if (command.action === 'follow_cursor') system?.setEyeTracking(true);
    else system?.stand();
    const confirmation = `Requested: ${command.action.replace('_',' ')}.`; notice(confirmation); return;
  }
  if (!bridge) { notice('Android native services are unavailable in this browser preview.'); return; }
  if (!state.keyConfigured) { notice('Add your Gemini API key in Settings before sending.'); return; }
  requestId = crypto.randomUUID(); busy = true; pendingText = text; input.value = ''; append('user', text);
  notice('Thinking…'); el('presence').textContent = 'Thinking with you';
  system?.setFrameInput({ activity: 'thinking', emotion: 'thinking' }); bridge.chat(text, requestId);
}
el('send').onclick = send;
el('input').addEventListener('keydown', event => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send(); } });
el('stop').onclick = () => { cancelMobileActions(); stopVoice(); bridge?.cancel(); bridge?.stopSpeech(); busy = false; requestId = ''; notice('Stopped.'); system?.stopBehaviour(); system?.setFrameInput({ activity: 'idle', emotion: 'idle' }); };
el('voice').onclick = () => {
  if (liveSession && liveSession.getState() !== 'disconnected') { stopVoice(); notice('Live voice ended.'); return; }
  if (!bridge) { notice('Gemini Live voice runs inside the Android app.'); return; }
  if (!state.keyConfigured) { notice('Add a working Gemini key in Settings to start Live voice.'); return; }
  if (busy) { notice('Stop the current text reply before starting Live voice.'); return; }
  bridge.stopSpeech(); liveCaption = null;
  liveSession = new MyraaAudioSession({
    connection: { createSocket: () => new MobileLiveTransport(bridge) },
    onStateChange: liveState, onTranscription: liveTranscription,
    onToolCall: (name, args, callback) => { if (name !== 'mobile_action') { callback({ status: 'unsupported', message: 'That tool is unavailable on mobile.' }); return; } void runMobileAction(name, args).then(result => { notice(result.message); callback(result); }); },
    onError: message => { notice(message); stopVoice(); },
  });
  notice('Connecting to MYRAA Live…'); void liveSession.connect(true);
};
el('speak').onclick = () => { const text = lastReply || state.history.filter((t: any) => t.role === 'model').at(-1)?.text; if (text) { stopVoice(); bridge?.speak(text); } else notice('There is no reply to read yet.'); };
el('wave').onclick = () => { system?.setFrameInput({ emotion: 'happy' }); system?.playBehaviour('wave'); };
el('sit').onclick = () => { system?.frameFullBody(); system?.sit({ seat: 'chair', style: 'upright' }); };
el('stand').onclick = () => { system?.stand(); if (!fullBody) { system?.stage.clearPan(); system?.setCameraFocus(null); } };
el('view').onclick = () => { fullBody = !fullBody; if (fullBody) system?.frameFullBody(); else { system?.stage.clearPan(); system?.setCameraFocus(null); } el('view').textContent = fullBody ? 'Portrait' : 'Full body'; };
el('happy').onclick = () => { system?.setFrameInput({ emotion: 'happy' }); setTimeout(() => system?.setFrameInput({ emotion: 'idle' }), 3000); };
el('settings').onclick = () => { stopVoice(); refreshSettings(); el<HTMLDialogElement>('settings-panel').showModal(); el('diagnostics').textContent = JSON.stringify(system?.diagnostics || {}); };
el('save').onclick = () => {
  if (!bridge) return notice('Save settings in the installed Android app.');
  settingsProgress(true, 'Checking and saving settings…');
  bridge.configure(JSON.stringify({ key: el<HTMLInputElement>('key').value.trim(), liveModel: el<HTMLInputElement>('live-model').value.trim(), model: el<HTMLInputElement>('model').value.trim(), quality: el<HTMLSelectElement>('quality').value, language: el<HTMLSelectElement>('language').value, spokenReplies: el<HTMLInputElement>('spoken').checked }));
};
el('check-connection').onclick = () => {
  if (!bridge) return notice('Check the connection in the installed Android app.');
  settingsProgress(true, 'Checking Google key and model access…');
  bridge.checkConnection(JSON.stringify({ key: el<HTMLInputElement>('key').value.trim(), liveModel: el<HTMLInputElement>('live-model').value.trim(), model: el<HTMLInputElement>('model').value.trim() }));
};
let allHistory = false; el('history-toggle').onclick = () => { allHistory = !allHistory; renderHistory(allHistory); el('history-toggle').textContent = allHistory ? 'Close history' : 'History'; document.querySelector('.conversation')!.classList.toggle('expanded', allHistory); };
el('clear-history').onclick = () => { if (confirm('Clear conversation history on this device?')) { bridge?.clearHistory(); state = bridge ? JSON.parse(bridge.getState()) : state; renderHistory(); } };
el('remove-key').onclick = () => { if (confirm('Remove your saved Gemini API key?')) { bridge?.clearKey(); state = bridge ? JSON.parse(bridge.getState()) : state; refreshSettings(); } };
el('android-settings').onclick = () => bridge?.openSettings();
el('floating-settings').onclick = () => bridge?.openFloatingSettings();
const resize = new ResizeObserver(() => { const box = el('avatar').getBoundingClientRect(); system?.resize(box.width, box.height); if (fullBody) system?.frameFullBody(); }); resize.observe(el('avatar'));
el('avatar').onpointermove = event => { const box = el('avatar').getBoundingClientRect(); system?.setPointer((event.clientX - box.left) / box.width * 2 - 1, 1 - (event.clientY - box.top) / box.height * 2); };
document.addEventListener('visibilitychange', () => { if (document.hidden) { if (!backgroundLive && !permissionPrompt) stopVoice(); system?.stop(); bridge?.stopSpeech(); } else system?.start(); });
window.addEventListener('myraa:lifecycle', event => { foreground = !!(event as CustomEvent).detail?.active; permissionPrompt = !!(event as CustomEvent).detail?.permissionPrompt; backgroundLive = !!(event as CustomEvent).detail?.backgroundLive; if (!foreground && !backgroundLive && !(event as CustomEvent).detail?.permissionPrompt) stopVoice(); if (foreground) system?.start(); else { system?.stop(); bridge?.stopSpeech(); } });
window.addEventListener('myraa:stop-live', () => { stopVoice(); notice('Live call stopped.'); });
window.addEventListener('pagehide', () => { stopVoice(); generation++; system?.dispose(); bridge?.cancel(); });
window.__mobileDiagnostics = () => system?.diagnostics;
renderHistory(); refreshSettings(); void loadAvatar();
if (!bridge) notice('Browser preview — native chat and voice run in the Android app.');
