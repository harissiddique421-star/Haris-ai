const { app, BrowserWindow } = require('electron');
const fs = require('fs');
const path = require('path');
const log = message => fs.appendFileSync(path.join(__dirname, '../artifacts/renderer-progress.log'), `${new Date().toISOString()} ${message}\n`);
log('Runner entered');
process.on('uncaughtException', error => { log(error.stack); app.exit(1); });
app.setPath('userData', path.join(require('os').tmpdir(), `myraa-renderer-qa-${process.pid}`));
app.whenReady().then(async () => {
  log('Electron ready');
  const win = new BrowserWindow({ show: false, width: 1000, height: 900, webPreferences: { contextIsolation: true, nodeIntegration: false, backgroundThrottling: false } });
  win.webContents.on('console-message', (event) => { const message = event.message || ''; if (message.startsWith('QA:') || event.level === 'error') log(message); });
  try {
    await win.loadURL('http://127.0.0.1:5189/tests/renderer.html');
    log('QA page loaded');
    await win.webContents.executeJavaScript(`new Promise((resolve,reject)=>{let n=0;const t=setInterval(()=>{if(window.runAvatarQA){clearInterval(t);resolve(true)}else if(++n>300){clearInterval(t);reject(new Error('QA module did not load'))}},100)})`);
    const result = await win.webContents.executeJavaScript(`window.runAvatarQA(${JSON.stringify(process.env.MYRAA_QA_ONLY || null)})`);
    fs.mkdirSync(path.join(__dirname, '../artifacts'), { recursive: true });
    const filename = process.env.MYRAA_QA_ONLY ? `renderer-isolated-${process.env.MYRAA_QA_ONLY.replace(/[^a-z0-9_-]/gi,'')}.json` : 'renderer-results.json';
    fs.writeFileSync(path.join(__dirname, '../artifacts', filename), JSON.stringify(result, null, 2));
    fs.writeFileSync(path.join(__dirname, '../artifacts/renderer-final.png'), (await win.webContents.capturePage()).toPNG());
    const failures = result.results.filter(r => r.error || Object.values(r.checks).some(c => c.status === 'fail') || !r.wave.finite || r.wave.handTravel < 0.1 || r.wave.straightHorizontalFrames > 0 || r.wave.maxStepHeightRatio > 0.12);
    console.log(JSON.stringify({ models: result.results.length, failures: failures.map(r => r.id), presets: result.presets, gpu: result.gpu }));
    app.exit(failures.length ? 1 : 0);
  } catch (error) { log(error.stack || String(error)); app.exit(1); }
});
