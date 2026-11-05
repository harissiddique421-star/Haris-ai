import './mobile.css';
import { MyraaAudioSession, type LiveState } from '../lib/audio';
import { MobileLiveTransport } from './LiveTransport';
import { parseMobileAction } from './actions';
import fridayLogo from '../../branding/friday-icon-small.png';
import fridayCore from '../../branding/friday-icon.png';
import { CharacterSystem } from '../character/core/CharacterSystem';
import { evelynConfig } from '../character/config/characters/evelyn';
import { qualityProfile, withQuality } from '../character/core/quality';
import { setCharacterRealism } from '../character/materials/AnimeMaterial';
import { parseCharacterCommand } from '../../shared/character/commands';

interface Bridge { getState(): string; configure(json: string): void; checkConnection(json: string): void; chat(text: string, id: string): void; cancel(): void; startLive(id: string): void; endLive(): void; speak(text: string): void; stopSpeech(): void; clearHistory(): void; clearKey(): void; openSettings(): void; mobileAction(id: string, name: string, args: string): void; openFloatingSettings(): void; openAccessibilitySettings?(): void; openNotificationSettings?(): void; requestCamera?(): void; startScreenShare?(): void; stopScreenShare?(): void; cancelMobileAction(id: string): void; }
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
let cameraStream: MediaStream | null = null;
let cameraTimer: ReturnType<typeof setInterval> | null = null;
let cameraFacing: 'environment' | 'user' = 'environment';
let screenSharing = false;
const frameCanvas = document.createElement('canvas');
function liveActive() { return !!liveSession && liveSession.getState() !== 'disconnected'; }
function updateVisionButtons() {
  const live = liveActive();
  for (const id of ['camera', 'screen']) (document.getElementById(id) as HTMLButtonElement | null)?.toggleAttribute('hidden', !live);
  document.getElementById('camera')?.setAttribute('aria-pressed', String(!!cameraStream));
  document.getElementById('screen')?.setAttribute('aria-pressed', String(screenSharing));
  const screenButton = document.getElementById('screen'); if (screenButton) screenButton.textContent = screenSharing ? 'Stop sharing' : 'Share screen';
}
function stopCamera() {
  if (cameraTimer) clearInterval(cameraTimer); cameraTimer = null;
  cameraStream?.getTracks().forEach(track => track.stop()); cameraStream = null;
  const preview = document.getElementById('camera-preview') as HTMLVideoElement | null; if (preview) { preview.srcObject = null; preview.hidden = true; }
  updateVisionButtons();
}
async function startCamera() {
  if (!liveActive()) { notice('Start a Live call first, then turn on the camera.'); return; }
  try {
    cameraStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: cameraFacing, width: { ideal: 960 }, height: { ideal: 960 } }, audio: false });
  } catch { cameraStream = null; notice('Camera could not start. Allow Camera for FRIDAY in Android settings.'); updateVisionButtons(); return; }
  const preview = document.getElementById('camera-preview') as HTMLVideoElement;
  preview.srcObject = cameraStream; preview.hidden = false; void preview.play();
  cameraTimer = setInterval(() => {
    if (!liveActive() || !cameraStream || preview.videoWidth === 0) return;
    const scale = Math.min(1, 768 / Math.max(preview.videoWidth, preview.videoHeight));
    frameCanvas.width = Math.round(preview.videoWidth * scale); frameCanvas.height = Math.round(preview.videoHeight * scale);
    frameCanvas.getContext('2d')?.drawImage(preview, 0, 0, frameCanvas.width, frameCanvas.height);
    const jpeg = frameCanvas.toDataURL('image/jpeg', 0.6).split(',')[1];
    if (jpeg) liveSession?.sendVideoFrame(jpeg);
  }, 1000);
  notice('Camera on. FRIDAY can see what the camera sees.'); updateVisionButtons();
}
function stopVision() { stopCamera(); if (screenSharing) bridge?.stopScreenShare?.(); screenSharing = false; updateVisionButtons(); }
function stopVoice() { stopVision(); backgroundLive = false; const session = liveSession; liveSession = null; if (session) cancelMobileActions(); session?.disconnect(); bridge?.endLive(); }
/** Builds without the licensed 3D model show the FRIDAY core instead of an error. */
function showCore() {
  el('avatar-status').textContent = '';
  const avatar = el('avatar'); avatar.replaceChildren();
  const core = document.createElement('img'); core.src = fridayCore; core.alt = ''; core.className = 'friday-core'; avatar.append(core);
  document.querySelector('.gestures')?.setAttribute('hidden', '');
}
function liveState(value: LiveState) {
  queueMicrotask(() => { if (value === "disconnected") stopVision(); else updateVisionButtons(); });
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
  <header><div class="brand"><img class="brand-logo" src="${fridayLogo}" alt="" width="36" height="36"><div>FRIDAY<small>Your companion, wherever you are.</small></div></div><button id="settings" aria-label="Open settings">⚙</button></header>
  <main><section class="portrait" aria-label="FRIDAY character"><video id="camera-preview" class="camera-preview" playsinline muted hidden title="Tap to switch camera"></video><div class="halo"></div><div id="avatar"></div><div class="avatar-status" id="avatar-status" role="status">Loading your character…</div><span class="presence"><i></i> <span id="presence">Here with you</span></span><div class="gestures"><button id="wave">Wave</button><button id="sit">Sit</button><button id="stand">Stand</button><button id="happy">Smile</button><button id="view">Portrait</button></div></section>
  <section class="conversation"><div class="section-heading"><span>OUR CONVERSATION</span><button id="history-toggle">History</button></div><div id="messages" role="log" aria-live="polite"></div><div id="notice" class="notice" role="status"></div></section></main>
  <footer><div class="composer"><button id="voice" aria-label="Voice conversation" aria-pressed="false" title="Start voice conversation"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8"/></svg><span>Talk</span></button><textarea id="input" rows="1" maxlength="8000" placeholder="Talk to FRIDAY…" aria-label="Message"></textarea><button id="send" aria-label="Send message">↑</button></div><div class="footer-actions"><span id="connection">Private on this device · Gemini for replies</span><button id="camera" aria-pressed="false" hidden>Camera</button><button id="screen" aria-pressed="false" hidden>Share screen</button><button id="stop">Stop</button><button id="speak" aria-label="Read last reply aloud">Read aloud</button></div></footer>
  <dialog id="settings-panel"><form method="dialog"><div class="dialog-heading"><h2>Make yourself at home</h2><button aria-label="Close settings">×</button></div></form><p class="muted">Your key and conversations stay on this device. Messages and recent context are sent directly to Google when you ask for a reply.</p><label>Gemini API key<input id="key" type="password" autocomplete="off" placeholder="Enter your own key"></label><p id="key-state" class="muted"></p><label>Text model<input id="model" autocomplete="off" placeholder="Model identifier"></label><label>Live voice model<input id="live-model" autocomplete="off" placeholder="gemini-3.1-flash-live-preview"></label><label>Language<select id="language"><option value="hi-IN">Hindi / Hinglish</option><option value="en-IN">English (India)</option><option value="en-US">English</option></select></label><label>Graphics<select id="quality"><option value="low">Low · save battery</option><option value="balanced">Medium · balanced</option><option value="high">High · sharper</option></select></label><label class="check"><input id="spoken" type="checkbox"> Read text replies with Android voice</label><button id="save" class="primary">Save settings</button><button id="check-connection">Check key &amp; model</button><p id="settings-status" class="muted" role="status" aria-live="polite"></p><div class="privacy-actions"><button id="remove-key">Remove API key</button><button id="clear-history">Clear conversation history</button><button id="android-settings">Android app settings</button><button id="floating-settings">Enable floating voice controls</button><button id="autosend-settings">Let FRIDAY send WhatsApp messages</button><button id="notification-settings">Let FRIDAY read notifications and announce calls</button><button id="phone-control-settings">Let FRIDAY control the phone</button></div><p class="muted">Talk opens an in-app Gemini Live audio call. Your microphone streams directly to Gemini, which generates FRIDAY’s voice. Android microphone permission is required. Read aloud is a separate text-reading option. During an active call, the microphone can stay on while you use other apps; stop it from FRIDAY or its notification. Floating voice controls allow app switching while FRIDAY is behind another app. During a Live call, Camera and Share screen send about one picture per second to Gemini until you turn them off or end the call. Contacts stay on this device and are read only to resolve a message or call recipient. Calls and WhatsApp messages always ask you to confirm first. WhatsApp sends only if you enable FRIDAY in Android Accessibility settings; otherwise it opens a draft and you tap Send. With Notification access on, FRIDAY keeps your last 30 notifications in memory only and sends them to Gemini only when you ask it to read them; during a Live call it announces incoming callers. Phone control works only if you turn on “FRIDAY phone control” in Android Accessibility settings: FRIDAY can then press Back and Home, read the screen, tap, type and scroll in the app in front when you ask. It never reads or fills password fields, and taps that could send, pay, delete, install or allow something wait for your yes.</p><output id="diagnostics" class="muted"></output></dialog>`;
const el = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const notice = (message: string) => { el('notice').textContent = message; };
function append(role: string, text: string) {
  const item = document.createElement('article'); item.className = `message ${role}`;
  const label = document.createElement('small'); label.textContent = role === 'user' ? 'YOU' : 'FRIDAY';
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
      onError: error => { if (mine === generation) { if (/404|Failed to fetch model/i.test(error.message)) showCore(); else notice(`Avatar: ${error.message}`); } } });
    system = instance; const box = el('avatar').getBoundingClientRect(); instance.resize(box.width, box.height);
    setCharacterRealism(0.65); await instance.load(); if (mine !== generation) return;
    if (fullBody) instance.frameFullBody();
    instance.setEyeTracking(true); if (foreground && !document.hidden) instance.start();
    el('avatar-status').textContent = '';
  } catch (error) {
    if (mine !== generation) return;
    if (/404|Failed to fetch model/i.test(String(error))) { showCore(); return; }
    el('avatar-status').textContent = `Avatar could not load. ${String(error)}`;
  }
}
window.MyraaEvents = event => {
  if (event.type === 'mobile-action-result') {
    const request = actionRequests.get(event.id); if (request) { clearTimeout(request.timer); actionRequests.delete(event.id); request.resolve(event.result); }
    notice(event.result?.message || 'Android action finished.'); return;
  }
  if (event.type === 'screen-frame') { if (screenSharing && liveActive() && typeof event.data === 'string') liveSession?.sendVideoFrame(event.data); return; }
  if (event.type === 'screen-share') { screenSharing = !!event.active; if (event.message) notice(event.message); else notice(screenSharing ? 'Sharing your screen with FRIDAY.' : 'Screen sharing stopped.'); updateVisionButtons(); return; }
  if (event.type === 'camera-permission') { if (event.granted) void startCamera(); else notice('Camera permission was denied.'); return; }
  if (event.type === 'incoming-call') {
    const caller = String(event.caller || 'Unknown caller').slice(0, 80);
    notice(`Incoming call: ${caller}`);
    if (liveSession && liveSession.getState() !== 'disconnected') liveSession.sendText(`[Phone] Incoming call from ${caller}. Tell me who is calling and ask whether to pick up or reject.`);
    return;
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
  if (mobile) { input.value = ''; append('user', text); void runMobileAction('mobile_action', mobile.action === 'whatsapp_message' || mobile.action === 'call' || mobile.action === 'send_sms' ? { ...mobile, confirm_on_screen: true } : mobile).then(result => { append('model', result.message); notice(result.message); }); return; }
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
  notice('Connecting to FRIDAY Live…'); void liveSession.connect(true);
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
el('autosend-settings').onclick = () => bridge?.openAccessibilitySettings?.();
el('camera').onclick = () => { if (cameraStream) { stopCamera(); notice('Camera off.'); } else if (bridge?.requestCamera) bridge.requestCamera(); else void startCamera(); };
el('screen').onclick = () => { if (screenSharing) bridge?.stopScreenShare?.(); else if (!liveActive()) notice('Start a Live call first, then share your screen.'); else bridge?.startScreenShare?.(); };
el('camera-preview').onclick = () => { cameraFacing = cameraFacing === 'environment' ? 'user' : 'environment'; stopCamera(); bridge?.requestCamera ? bridge.requestCamera() : void startCamera(); };
el('notification-settings').onclick = () => bridge?.openNotificationSettings?.();
el('phone-control-settings').onclick = () => bridge?.openAccessibilitySettings?.();
const resize = new ResizeObserver(() => { const box = el('avatar').getBoundingClientRect(); system?.resize(box.width, box.height); if (fullBody) system?.frameFullBody(); }); resize.observe(el('avatar'));
el('avatar').onpointermove = event => { const box = el('avatar').getBoundingClientRect(); system?.setPointer((event.clientX - box.left) / box.width * 2 - 1, 1 - (event.clientY - box.top) / box.height * 2); };
document.addEventListener('visibilitychange', () => { if (document.hidden) { if (!backgroundLive && !permissionPrompt) stopVoice(); system?.stop(); bridge?.stopSpeech(); } else system?.start(); });
window.addEventListener('myraa:lifecycle', event => { foreground = !!(event as CustomEvent).detail?.active; permissionPrompt = !!(event as CustomEvent).detail?.permissionPrompt; backgroundLive = !!(event as CustomEvent).detail?.backgroundLive; if (!foreground && !backgroundLive && !(event as CustomEvent).detail?.permissionPrompt) stopVoice(); if (foreground) system?.start(); else { system?.stop(); bridge?.stopSpeech(); } });
window.addEventListener('myraa:stop-live', () => { stopVoice(); notice('Live call stopped.'); });
window.addEventListener('pagehide', () => { stopVoice(); generation++; system?.dispose(); bridge?.cancel(); });
window.__mobileDiagnostics = () => system?.diagnostics;
renderHistory(); refreshSettings(); void loadAvatar();
if (!bridge) notice('Browser preview — native chat and voice run in the Android app.');
