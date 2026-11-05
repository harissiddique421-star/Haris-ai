/** Actual main preload/IPC, app UI and companion; disposable credential-free profile. */
const { app, BrowserWindow, session } = require('electron');
const fs = require('node:fs'), path = require('node:path'), os = require('node:os'), assert = require('node:assert/strict');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'myraa-manual-controls-'));
process.env.MYRAA_DATA_DIR = profile; process.env.MYRAA_PORT = '5193';
process.env.MYRAA_CHARACTERS_DIR = path.join(profile, 'characters');
process.env.GEMINI_API_KEY = ''; process.env.GOOGLE_API_KEY = '';
fs.writeFileSync(path.join(profile, 'secrets.json'), JSON.stringify({ ignoreEnvironmentApiKey: true }));
fs.writeFileSync(path.join(profile, 'app-settings.v3.json'), JSON.stringify({ version: 3, onboardingComplete: true,
  companion: { enabled: true, mode: 'passive', fullscreenBehavior: 'always', interactionLevel: 'minimal', walkAround: false, hideAndPeek: false, iconPlay: false },
  graphics: { quality: 'low' }, behavior: { proactivity: 'quiet', awayCheckin: false, returnGreeting: false } }));
try { os.setPriority(0, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {}
const record = { scope: 'Real bundled app UI, preload/IPC and native companion, no credentials or Google calls; no external mouse/keyboard input.', checks: [], errors: [] };
const output = path.resolve(__dirname, '../artifacts/manual-controls-app-smoke.json');
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (name, passed, detail) => { record.checks.push({ name, passed, ...(detail ? { detail } : {}) }); assert.ok(passed, name); };
let started = false, chatRequests = 0;
async function until(read, condition, label, timeout = 25000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { const value = await read(); if (condition(value)) return value; await wait(150); }
  throw new Error(label + ' timed out.');
}
async function diagnostics(contents) {
  return contents.executeJavaScript(`(()=>{if(!window.__manualBound){window.__manualBound=true;window.addEventListener('myraa:character-diagnostics',e=>window.__manualDiag=e.detail)}window.dispatchEvent(new CustomEvent('myraa:request-character-diagnostics'));return window.__manualDiag})()`);
}
app.whenReady().then(() => session.defaultSession.webRequest.onBeforeRequest((details, callback) => {
  if (details.url.startsWith('http://127.0.0.1:5193/api/chat')) chatRequests++;
  callback({ cancel: false });
}));
app.on('web-contents-created', (_event, contents) => contents.on('did-finish-load', async () => {
  if (started || contents.getURL() !== 'http://127.0.0.1:5193/') return;
  started = true;
  try {
    await until(() => contents.executeJavaScript(`(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.textContent.includes('Use the character without AI'));if(b)b.click();return!!document.querySelector('[aria-label="Character controls"]')})()`), Boolean, 'offline app controls');
    check('manual controls appear without a Gemini key', true);
    const layout = await contents.executeJavaScript(`(()=>{const f=document.querySelector('footer').getBoundingClientRect();return {height:f.height,width:f.width,viewport:innerWidth}})()`);
    check('compact message dock stays under 170px high', layout.height < 170, layout);
    const stageBefore = await contents.executeJavaScript(`document.querySelector('#myraa-holographic-desktop').getBoundingClientRect().x`);
    for(let cycle=0;cycle<4;cycle++) {
      await contents.executeJavaScript(`document.querySelector('button[aria-label="Settings"]').click()`);
      await wait(450);
      const position = await contents.executeJavaScript(`(()=>{const s=document.querySelector('#myraa-holographic-desktop');return {x:s.getBoundingClientRect().x,scroll:s.scrollLeft,body:document.scrollingElement.scrollLeft}})()`);
      check('settings preserves stage position cycle '+cycle, Math.abs(position.x-stageBefore)<1 && position.scroll===0 && position.body===0,position);
      await contents.executeJavaScript(`document.querySelector('button[aria-label="Close settings"]').click()`);
      await wait(450);
    }
    await contents.executeJavaScript(`document.querySelector('footer details').open=true`);
    const config = await fetch('http://127.0.0.1:5193/api/config').then(response => response.json());
    check('test profile borrows no owner credential', config.hasApiKey === false);
    await until(() => diagnostics(contents), value => value?.loaded, 'main model');
    const companion = await until(() => Promise.resolve(BrowserWindow.getAllWindows().find(window => window.webContents.getURL().includes('mode=companion'))), Boolean, 'companion window');
    await until(() => diagnostics(companion.webContents), value => value?.loaded, 'companion model');
    const click = label => contents.executeJavaScript(`document.querySelector(${JSON.stringify(`button[aria-label="${label}"]`)}).click()`);
    await click('Enable character dragging');
    await until(() => fetch('http://127.0.0.1:5193/api/app-settings').then(response => response.json()), value => value.companion.mode === 'interactive' && value.companion.enabled, 'interactive mode');
    check('Drag button enables and persists interactive companion mode', companion.isVisible());
    const start = companion.getPosition()[0];
    await click('Move companion left');
    await until(() => Promise.resolve(companion.getPosition()[0]), x => x < start - 60, 'left movement');
    check('visible Move Left button moves the real desktop window', true);
    await click('Stop character action'); await wait(350);
    const stopped = companion.getPosition()[0]; await wait(350);
    check('Stop settles native walking without further window travel', Math.abs(companion.getPosition()[0] - stopped) <= 1);
    await click('Sit character');
    await until(() => diagnostics(companion.webContents), value => value?.seat?.style === 'handsOnKnees', 'companion sit');
    check('Sit button reaches the actual companion pose without AI', true);
    await until(() => diagnostics(contents), value => value?.seat !== null && value?.seat !== undefined, 'main preview sit');
    check('Sit also updates the main character preview', true);
    await click('Stand character');
    await until(() => diagnostics(companion.webContents), value => value?.seat === null, 'companion stand');
    check('Stand button restores the companion standing pose', true);
    const textCommand = async text => {
      await contents.executeJavaScript(`(()=>{const input=document.querySelector('input[aria-label="Message MYRAA"]');input.focus();input.select()})()`);
      await contents.insertText(text);
      await until(() => contents.executeJavaScript(`!document.querySelector('button[aria-label="Send message"]').disabled`), Boolean, 'typed command input');
      await contents.executeJavaScript(`document.querySelector('input[aria-label="Message MYRAA"]').form.requestSubmit()`);
    };
    await textCommand('MYRAA, sit.');
    await until(() => diagnostics(companion.webContents), value => value?.seat?.style === 'handsOnKnees', 'local typed sit');
    check('typed sit is handled locally with no API key', true);
    await textCommand('stand up');
    await until(() => diagnostics(companion.webContents), value => value?.seat === null, 'local typed stand');
    check('typed stand is handled locally with no API key', true);
    const beforeRight = companion.getPosition()[0];
    await textCommand('move right');
    await until(() => Promise.resolve(companion.getPosition()[0]), x => x > beforeRight + 60, 'typed right movement');
    check('typed movement moves the real companion without AI', true);
    await click('Stop character action'); await wait(350);
    const invalid = await contents.executeJavaScript(`window.myraa.controlCharacter('deleteFile')`);
    check('control channel rejects unrelated desktop-tool actions', invalid.ok === false);
    const rogue = new BrowserWindow({ show: false, webPreferences: { preload: path.resolve(__dirname, '../electron/preload.cjs'), contextIsolation: true, nodeIntegration: false } });
    await rogue.loadURL('data:text/html,<body>isolated IPC origin test</body>');
    const denied = await rogue.webContents.executeJavaScript(`window.myraa.controlCharacter('sit')`);
    check('another renderer cannot issue main-window character commands', denied.ok === false); rogue.destroy();
    check('avatar commands never call the chat API', chatRequests === 0, { chatRequests });
    fs.writeFileSync(path.resolve(__dirname, '../artifacts/manual-controls-app.png'), (await contents.capturePage()).toPNG());
    record.finalCompanionBounds = companion.getBounds();
  } catch (error) { record.errors.push(error.stack || String(error)); }
  finally { fs.writeFileSync(output, JSON.stringify(record, null, 2)); app.quit(); }
}));
setTimeout(() => { record.errors.push('Manual controls app test timed out.'); fs.writeFileSync(output, JSON.stringify(record, null, 2)); app.quit(); }, 120000).unref();
require('../electron/main.cjs');
