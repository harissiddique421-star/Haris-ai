const { app, BrowserWindow } = require('electron');
const fs = require('node:fs'); const path = require('node:path'); const os = require('node:os');
const id = process.env.MYRAA_SECONDARY_ID || 'evelyn';
const preset = process.env.MYRAA_SECONDARY_PRESET || 'balanced';
const runName = process.env.MYRAA_SECONDARY_RUN || 'secondary';
if (!/^[a-z0-9_-]{1,64}$/i.test(runName)) throw new Error('Invalid run name.');
if (!/^[a-z0-9_-]+$/i.test(id) || !['low', 'balanced', 'high'].includes(preset)) throw new Error('Invalid scene selection.');
const directory = path.resolve(__dirname, '../artifacts', runName, `${id}-${preset}`);
fs.mkdirSync(directory, { recursive: true });
app.setPath('userData', path.join(os.tmpdir(), `myraa-secondary-${process.pid}`));
try { os.setPriority(0, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {}
app.whenReady().then(async () => {
  const window = new BrowserWindow({ show: false, width: 980, height: 840,
    webPreferences: { offscreen: true, backgroundThrottling: false, contextIsolation: true, nodeIntegration: false } });
  window.webContents.setFrameRate(30);
  const result = { id, preset, scope: 'Existing CompanionActor in a virtual desktop test scene, not an OS overlay test.',
    source: 'Fixed-step display pose, primary animation and authored post-physics grants; SOFT_BODY tuning unchanged.',
    sourceCommit: process.env.MYRAA_QA_SOURCE_COMMIT || 'working-tree', inventory: null, phases: [], errors: [] };
  try {
    let ready = false;
    for (let i = 0; i < 150; i++) {
      try { ready = (await fetch('http://127.0.0.1:5189/qa/catalogue')).ok; } catch {}
      if (ready) break; await new Promise(resolve => setTimeout(resolve, 200));
    }
    if (!ready) throw new Error('QA asset server not ready.');
    await window.loadURL('http://127.0.0.1:5189/tests/renderer.html');
    await window.webContents.executeJavaScript(`new Promise((resolve,reject)=>{let n=0;const t=setInterval(()=>{if(window.startSecondaryQA){clearInterval(t);resolve(true)}else if(++n>300){clearInterval(t);reject(new Error('Secondary QA module did not load'))}},100)})`);
    for (const process of app.getAppMetrics()) { try { os.setPriority(process.pid, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {} }
    result.inventory = await window.webContents.executeJavaScript(`window.startSecondaryQA(${JSON.stringify(id)},${JSON.stringify(preset)})`);
    for (const phase of ['rest', 'wave', 'walk', 'turn', 'stop', 'sit', 'stand', 'dance', 'rapid', 'listen', 'speak', 'idle', 'teleport', 'longStall', 'settle', 'upperOff', 'upperZero', 'upperLimited', 'upperReset']) {
      const item = await window.webContents.executeJavaScript(`window.runSecondaryPhase(${JSON.stringify(phase)})`);
      if (item.capture) fs.writeFileSync(path.join(directory, `${phase}.png`), Buffer.from(item.capture.split(',')[1], 'base64'));
      if (item.detailCapture) fs.writeFileSync(path.join(directory, `${phase}-detail.png`), Buffer.from(item.detailCapture.split(',')[1], 'base64'));
      if (item.finalCapture) fs.writeFileSync(path.join(directory, `${phase}-final.png`), Buffer.from(item.finalCapture.split(',')[1], 'base64'));
      delete item.capture; delete item.detailCapture; delete item.finalCapture; result.phases.push(item);
      fs.writeFileSync(path.join(directory, 'result.json'), JSON.stringify(result, null, 2));
      console.log(JSON.stringify({ id, preset, phase, deviationDeg: item.maxDeviationDeg, meshDeltaHeightRatio: item.maxMeshDisplacementHeightRatio }));
    }
    const active = result.phases.filter(phase => ['walk','turn','dance'].includes(phase.phase));
    result.motionMeasured = Math.max(...active.map(phase => phase.maxMeshDisplacementHeightRatio)) > 1e-6;
    result.numericHealthy = result.phases.every(phase => phase.finite && phase.healthy && phase.maxDeviationDeg <= result.inventory.configuredMaxAngleDeg + 0.01);
    result.listenMouthClosed = !result.inventory.mappedVertexVisemes || result.phases.find(phase => phase.phase === 'listen').peakVisemeWeight < 0.01;
    result.speechStateArticulates = !result.inventory.mappedVertexVisemes || result.phases.find(phase => phase.phase === 'speak').peakVisemeWeight > 0.02;
    result.mouthReturnsToIdle = !result.inventory.mappedVertexVisemes || result.phases.find(phase => phase.phase === 'idle').finalVisemeWeight < 0.01;
    result.sitStandCompletes = result.phases.find(phase => phase.phase === 'sit').seat?.style === 'handsOnKnees' && result.phases.find(phase => phase.phase === 'stand').seat === null;
    result.upperBodyControls = ['upperOff', 'upperZero'].every(name => result.phases.find(phase => phase.phase === name).maxMeshDisplacementHeightRatio < 1e-6)
      && result.phases.find(phase => phase.phase === 'upperLimited').maxDeviationDeg <= 2.01
      && result.phases.find(phase => phase.phase === 'upperReset').upperBodyTuning.enabled === true
      && result.phases.find(phase => phase.phase === 'upperReset').upperBodyTuning.maxAngleDeg === 6;
    result.visualReview = 'pending; numeric health is not a natural-animation verdict';
  } catch (error) { result.errors.push(String(error)); }
  fs.writeFileSync(path.join(directory, 'result.json'), JSON.stringify(result, null, 2));
  app.exit(result.errors.length || result.numericHealthy === false || result.listenMouthClosed === false || result.speechStateArticulates === false || result.mouthReturnsToIdle === false || result.sitStandCompletes === false || result.upperBodyControls === false ? 1 : 0);
});
