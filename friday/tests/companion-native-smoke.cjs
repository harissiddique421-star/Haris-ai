/** Native manager + production CompanionApp in an isolated local settings fixture. */
const { app, BrowserWindow, screen } = require('electron');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const { CompanionManager } = require('../electron/companion.cjs');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'myraa-overlay-'));
app.setPath('userData', profile);
try { os.setPriority(0, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {}
const run = process.env.MYRAA_NATIVE_TEST_RUN || 'companion-native-smoke';
if (!/^[a-z0-9_-]{1,64}$/i.test(run)) throw new Error('Invalid native test run.');
const output = path.resolve(__dirname, '../artifacts', run + '.json');
const record = { scope: 'Real Electron window and production CompanionApp; fixture settings, existing Evelyn model; no microphone, provider, external input or game interactions.', profile, checks: [], errors: [] };
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
let manager, backdrop;
const check = (name, passed, detail) => record.checks.push({ name, passed, ...(detail ? { detail } : {}) });
app.whenReady().then(async () => {
  try {
    const { session } = require('electron');
    session.defaultSession.webRequest.onBeforeRequest((details, callback) => {
      let local = false;
      try { const url = new URL(details.url); local = ['data:', 'blob:', 'devtools:'].includes(url.protocol) || (url.hostname === '127.0.0.1' && url.port === '5189'); } catch {}
      callback({ cancel: !local });
    });
    const displays = screen.getAllDisplays();
    record.displays = displays.map(d => ({ id: d.id, scaleFactor: d.scaleFactor, bounds: d.bounds, workArea: d.workArea }));
    // Our own small window is the focus reference; never activate another app.
    backdrop = new BrowserWindow({ width: 320, height: 120, show: false, title: 'MYRAA overlay QA', webPreferences: { contextIsolation: true, nodeIntegration: false } });
    await backdrop.loadURL('data:text/html,<body style="background:%23142736;color:white">MYRAA local overlay verification</body>');
    backdrop.show(); backdrop.focus(); await wait(300);
    const settings = { companion: { enabled: true, mode: 'focus', scale: 1, alwaysOnTop: true, clickThrough: false,
      fullscreenBehavior: 'game_aware', interactionLevel: 'minimal', walkAround: false, hideAndPeek: false, iconPlay: false }, graphics: { quality: 'low' } };
    manager = new CompanionManager({ origin: 'http://127.0.0.1:5189', preload: path.resolve(__dirname, '../electron/preload.cjs'),
      dataDir: profile, ownPids: () => app.getAppMetrics().map(item => item.pid), sendToBackend: () => {}, backend: async () => ({}),
      saveSettings: patch => Object.assign(settings.companion, patch.companion || {}), openMain: () => {}, openStudio: () => {}, emergencyStop: () => {} });
    manager.applySettings(settings);
    // Geometry tests use our isolated virtual floor; never inspect other app windows.
    manager.windowsInZOrder = () => [];
    const window = manager.win;
    await window.loadURL('http://127.0.0.1:5189/tests/companion.html');
    const diagnostics = () => window.webContents.executeJavaScript(`(()=>{window.dispatchEvent(new CustomEvent('myraa:request-character-diagnostics'));return window.__companionDiagnostics})()`);
    let state;
    for (let i = 0; i < 120; i++) { state = await diagnostics(); if (state?.loaded && state?.running) break; await wait(500); }
    for (const item of app.getAppMetrics()) { try { os.setPriority(item.pid, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {} }
    check('production companion loads real character', state?.loaded && state?.running, state);
    check('overlay creation does not take focus', !window.isFocused() && backdrop.isFocused());
    const nativeFlags = { focusable: window.isFocusable(), alwaysOnTop: window.isAlwaysOnTop(), backgroundColor: window.getBackgroundColor() };
    check('native overlay focus and stacking flags', !nativeFlags.focusable && nativeFlags.alwaysOnTop, nativeFlags);
    await window.webContents.executeJavaScript('window.__myraa.stopBehaviour();window.__myraa.setIdleBehaviours(false)');
    await wait(1200);
    const alpha = await window.webContents.executeJavaScript(`(()=>{const s=window.__myraa;s.stage.render();const c=s.stage.renderer.domElement,w=c.width,h=c.height,gl=s.stage.renderer.getContext();const pixels=new Uint8Array(w*h*4);gl.readPixels(0,0,w,h,gl.RGBA,gl.UNSIGNED_BYTE,pixels);let opaque=0,clear=0,bottomEdgeOpaque=0;for(let y=0;y<h;y+=20)for(let x=0;x<w;x+=20){if(pixels[(y*w+x)*4+3]>24)opaque++;else clear++}for(let x=0;x<w;x++)if(pixels[x*4+3]>240)bottomEdgeOpaque++;return{opaque,clear,corner:pixels[3],bottomEdgeOpaque,background:getComputedStyle(document.body).backgroundColor}})()`);
    record.bottomEdgeVisualReview = { opaquePixels: alpha.bottomEdgeOpaque, scope: 'Resting shoe silhouette only; planted-foot gait and broader clipping review remain open.' };
    check('resting shoe silhouette fits above the canvas edge', alpha.bottomEdgeOpaque === 0, record.bottomEdgeVisualReview);
    check('rendered alpha contains silhouette and transparent background', alpha.opaque > 5 && alpha.clear > alpha.opaque && alpha.corner === 0 && alpha.background === 'rgba(0, 0, 0, 0)', alpha);
    fs.writeFileSync(path.join(__dirname, '../artifacts', (run === 'companion-native-smoke' ? 'companion-native' : run) + '.png'), (await window.webContents.capturePage()).toPNG());
    settings.companion.mode = 'passive'; manager.applySettings(settings); manager.setInteractive(true); manager.beginDrag();
    check('passive rejects drag and stale hit-test requests', manager.interactive === false && manager.drag === null);
    await wait(100);
    await window.webContents.executeJavaScript(`(()=>{const s=window.__myraa;const original=s.alphaAt.bind(s);window.__alphaReads=0;s.alphaAt=(...args)=>{window.__alphaReads++;return original(...args)}})()`);
    manager.send('companion:cursor', { x: 150, y: 250, width: 300, height: 560 }); await wait(100);
    check('passive cursor movement avoids GPU alpha hit-test rendering', await window.webContents.executeJavaScript('window.__alphaReads===0'));
    settings.companion.mode = 'hidden'; manager.applySettings(settings); await wait(600); state = await diagnostics();
    check('hidden stops actual renderer and shell movement timers', !window.isVisible() && state?.running === false && !manager.cursorTimer && !manager.anchorTimer && !manager.brainTimer, state);
    manager.onForeground({ fullscreen: true }); manager.onForeground({ fullscreen: false });
    check('fullscreen exit preserves hidden mode', !window.isVisible());
    settings.companion.mode = 'gaming'; manager.applySettings(settings); await wait(200);
    check('manual gaming keeps overlay suspended', !window.isVisible() && (await diagnostics())?.running === false);
    settings.companion.mode = 'focus'; manager.applySettings(settings); await wait(700);
    check('focus mode resumes renderer without stealing focus', window.isVisible() && (await diagnostics())?.running === true && !window.isFocused());
    if (process.env.MYRAA_QA_WALKING === '1') {
      await window.webContents.executeJavaScript(`(()=>{
        window.__walkSamples=[];
        window.__walkProbe=setInterval(()=>{const a=window.__companion,s=window.__myraa;if(!a||!s)return;
          const feet=a.locomotionDiagnostics?.feet;
          window.__walkSamples.push({active:a.walkWeight>.995,feet:['LEFT_FOOT','RIGHT_FOOT'].map((slot,i)=>{
            const point=s.slotWorld(slot);return{planted:feet?.[i]?.planted===true,x:point?s.worldToCanvas(point).x+window.screenX:null};})});
        },16);
      })()`);
      const homeX = window.getPosition()[0];
      const leftGoal = manager.clampX(homeX - 160);
      const left = await Promise.race([manager.walkTo(leftGoal), wait(7000).then(() => 'timeout')]);
      check('native walk reaches its left target through actual shell movement', left === true && Math.abs(window.getPosition()[0] - leftGoal) <= 2);
      await wait(1400);
      const right = await Promise.race([manager.walkTo(homeX), wait(7000).then(() => 'timeout')]);
      check('native walk returns to its right target', right === true && Math.abs(window.getPosition()[0] - homeX) <= 2);
      const samples = await window.webContents.executeJavaScript('(()=>{clearInterval(window.__walkProbe);return window.__walkSamples})()');
      let maxDrift = 0, count = 0;
      const planted = [false,false], first = [0,0];
      for (const sample of samples) for (let side=0;side<2;side++) {
        const foot=sample.feet[side], active=sample.active&&foot.planted&&Number.isFinite(foot.x);
        if(active&&!planted[side])first[side]=foot.x;
        if(active){maxDrift=Math.max(maxDrift,Math.abs(foot.x-first[side]));count++;}
        planted[side]=active;
      }
      record.nativeWalking={scope:'Actual OS window DIP coordinates, one physical display; asynchronous renderer/IPC sampling.',samples:count,maximumPlantDriftCssPx:maxDrift};
      check('native stance-foot drift remains bounded while the real window travels', count>10&&maxDrift<=8,record.nativeWalking);
      const pending=manager.walkTo(manager.clampX(homeX-160)); await wait(350);
      settings.companion.mode='hidden';manager.applySettings(settings);
      check('hiding mid-walk resolves the actual native movement action',await Promise.race([pending,wait(1200).then(()=>'unresolved')])===false);
      await wait(300);
      check('mid-walk hide stops renderer and movement timers',!window.isVisible()&&!manager.moveTimer&&(await diagnostics())?.running===false);
      settings.companion.mode='focus';manager.applySettings(settings);await wait(1600);
      check('resume discards stale planted-foot anchors and finishes stopping',await window.webContents.executeJavaScript('window.__companion.locomotionDiagnostics.active===false'));
    }
    manager.applySettings({ ...settings, physics: { enabled: false } }); await wait(200);
    check('live physics settings reach the production companion renderer', (await diagnostics())?.physicsEnabled === false);
    manager.onForeground({ fullscreen: true }); await wait(400);
    check('fullscreen notification suspends overlay rendering', !window.isVisible() && (await diagnostics())?.running === false);
    manager.onForeground({ fullscreen: false }); await wait(400);
    check('fullscreen exit resumes visible focus mode', window.isVisible() && (await diagnostics())?.running === true && !window.isFocused());
    manager.saveHome(); const home = window.getPosition();
    manager.placeAt(home[0] - 60, home[1] - 20); manager.returnHome();
    check('saved home restores native DIP placement', JSON.stringify(window.getPosition()) === JSON.stringify(home));
    check('monitor targeting accepts actual display', manager.moveToMonitor(displays[0].id));
    record.multiMonitorPhysical = displays.length > 1 ? 'Multiple displays detected; per-display visual review remains required.' : 'One connected display; mixed-DPI topology covered only by unit simulation.';
    const contents = window.webContents;
    manager.destroy(); await wait(200);
    check('native companion cleanup', window.isDestroyed() && contents.isDestroyed() && !manager.cursorTimer && !manager.anchorTimer && !manager.brainTimer);
  } catch (error) { record.errors.push(error.stack || String(error)); }
  finally {
    manager?.destroy(); if (backdrop && !backdrop.isDestroyed()) backdrop.destroy();
    fs.writeFileSync(output, JSON.stringify(record, null, 2));
    app.exit(record.errors.length || record.checks.some(item => !item.passed) ? 1 : 0);
  }
});
setTimeout(() => { record.errors.push('Native overlay verification timed out.'); fs.writeFileSync(output, JSON.stringify(record, null, 2)); app.exit(1); }, 100_000).unref();
