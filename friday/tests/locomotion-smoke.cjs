const { app, BrowserWindow } = require('electron');
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const run = process.env.MYRAA_LOCOMOTION_RUN || 'locomotion-baseline';
const ids = (process.env.MYRAA_LOCOMOTION_IDS || 'evelyn,azur-promilia-199001-p1').split(',');
if (!/^[a-z0-9_-]+$/i.test(run) || ids.some(id => !/^[a-z0-9_-]+$/i.test(id))) throw new Error('Invalid test selection.');
const output = path.resolve(__dirname, '../artifacts', run);
fs.mkdirSync(output, { recursive: true });
app.setPath('userData', fs.mkdtempSync(path.join(os.tmpdir(), 'myraa-gait-')));
try { os.setPriority(0, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {}
const record = { sourceCommit: process.env.MYRAA_QA_SOURCE_COMMIT || 'working-tree', contactAssertions: process.env.MYRAA_ASSERT_CONTACT === '1', characters: [], errors: [], failures: [] };
app.whenReady().then(async () => {
  try {
    const window = new BrowserWindow({ show: false, width: 980, height: 840, webPreferences: { offscreen: true, backgroundThrottling: false, contextIsolation: true, nodeIntegration: false } });
    window.webContents.setFrameRate(30);
    await window.loadURL('http://127.0.0.1:5189/tests/renderer.html');
    for (const process of app.getAppMetrics()) { try { os.setPriority(process.pid, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {} }
    await window.webContents.executeJavaScript(`new Promise((resolve,reject)=>{let n=0;const timer=setInterval(()=>{if(window.runLocomotionQA){clearInterval(timer);resolve(true)}else if(++n>150){clearInterval(timer);reject(new Error('Gait QA not ready'))}},100)})`);
    for (const id of ids) {
      const result = await window.webContents.executeJavaScript(`window.runLocomotionQA(${JSON.stringify(id)})`);
      for (const scenario of result.results) {
        scenario.captures.forEach((capture, index) => fs.writeFileSync(path.join(output, `${id}-${scenario.dir}-${scenario.sneak ? 'sneak' : 'walk'}-${index}.png`), Buffer.from(capture.split(',')[1], 'base64')));
        delete scenario.captures;
        if (record.contactAssertions && (!scenario.finite || scenario.stanceSamples < 100 || scenario.stanceWindows < 5
          || scenario.maximumPlantDriftCssPx > 4 || scenario.maximumGroundErrorHeightRatio > 0.01
          || scenario.maximumFootStepHeightRatio > 0.05 || scenario.stopSoleErrorHeightRatio > 0.005)) record.failures.push({ id, dir: scenario.dir, sneak: scenario.sneak });
      }
      record.characters.push(result);
      fs.writeFileSync(path.join(output, 'result.json'), JSON.stringify(record, null, 2));
      console.log(JSON.stringify({ id, results: result.results }));
    }
  } catch (error) { record.errors.push(String(error)); }
  finally {
    fs.writeFileSync(path.join(output, 'result.json'), JSON.stringify(record, null, 2));
    app.exit(record.errors.length || record.failures.length ? 1 : 0);
  }
});
