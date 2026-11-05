const { app, BrowserWindow } = require('electron');
const fs = require('fs'); const path = require('path');
app.setPath('userData', path.join(require('os').tmpdir(), `myraa-benchmark-${process.pid}`));
app.whenReady().then(async () => {
  const win = new BrowserWindow({ show: false, width: 1000, height: 900, webPreferences: { offscreen: true, backgroundThrottling: false, contextIsolation: true, nodeIntegration: false } });
  win.webContents.setFrameRate(60);
  try {
    await win.loadURL('http://127.0.0.1:5189/tests/renderer.html');
    await win.webContents.executeJavaScript(`new Promise((resolve,reject)=>{let n=0;const t=setInterval(()=>{if(window.runPresetBenchmark){clearInterval(t);resolve(true)}else if(++n>300){clearInterval(t);reject(new Error('Benchmark module did not load'))}},100)})`);
    const samples = [];
    app.getAppMetrics(); // Prime CPU counters before taking interval samples.
    const timer = setInterval(() => samples.push({ at: Date.now(), processes: app.getAppMetrics().map(({pid,type,cpu,memory}) => ({pid,type,cpu,memory})) }), 1000);
    let result;
    try { result = await win.webContents.executeJavaScript('window.runPresetBenchmark()'); }
    finally { clearInterval(timer); }
    result.processSamples = samples;
    fs.writeFileSync(path.join(__dirname,'../artifacts/renderer-benchmark.json'),JSON.stringify(result,null,2));
    app.exit(0);
  } catch(error) { fs.writeFileSync(path.join(__dirname,'../artifacts/renderer-benchmark-error.log'),String(error)); app.exit(1); }
});
