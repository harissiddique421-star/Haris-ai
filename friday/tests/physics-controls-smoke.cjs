const { app, BrowserWindow } = require('electron');
const fs = require('node:fs'), path = require('node:path'), os = require('node:os'), assert = require('node:assert/strict');
app.setPath('userData', fs.mkdtempSync(path.join(os.tmpdir(), 'myraa-physics-ui-')));
try { os.setPriority(0, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {}
const record = { scope: 'Actual React PhysicsPanel with a simulated API transport and system; real storage and deformation are tested separately.', checks: [], errors: [] };
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
app.whenReady().then(async () => {
  const window = new BrowserWindow({ show: true, width: 500, height: 1000, webPreferences: { contextIsolation: true, nodeIntegration: false } });
  const run = async script => {
    const result = await window.webContents.executeJavaScript(`(()=>{try{return ${script}}catch(error){return{__scriptError:error.stack||String(error)}}})()`);
    if (result?.__scriptError) throw new Error(result.__scriptError);
    return result;
  };
  try {
    await window.loadURL('http://127.0.0.1:5189/tests/physics-controls.html');
    for(let i=0;i<100;i++){if(await run(`Boolean(document.querySelector('input[aria-label="Upper-body strength value"]'))`))break;await wait(100)}
    assert.deepEqual(await run(`(()=>{const s=document.querySelector('input[aria-label="Upper-body strength"]'),a=document.querySelector('input[aria-label="Maximum upper-body swing"]');return[s.min,s.max,a.max,s.matches(':disabled')]})()`), ['0','100','6',false]);
    record.checks.push('Built-in character controls are available and expose bounded ranges');
    window.show(); window.focus();
    await run(`(()=>{const i=document.querySelector('input[aria-label="Upper-body strength value"]');i.focus();Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(i,'')})()`);
    await window.webContents.insertText('35');
    await wait(50); record.editProbe=await run(`({value:document.querySelector('input[aria-label="Upper-body strength value"]').value,active:document.activeElement.getAttribute('aria-label'),requests:window.__physicsRequests.length})`); assert.equal(await run('window.__physicsRequests.length'),0);
    await run(`document.querySelector('input[aria-label="Upper-body strength value"]').blur()`); await wait(100);
    assert.equal(await run('window.__physicsRequests.at(-1).physics.upperBodyByModel.evelyn.strength'),0.35);
    assert.equal(await run('window.__physicsRequests.length'),1);
    record.checks.push('Editing commits one model setting after completion');
    await run(`document.querySelector('#switch-test-model').click()`); await wait(100);
    assert.equal(await run(`document.querySelector('input[aria-label="Upper-body strength"]').value`),'25');
    await run(`document.querySelector('#switch-test-model').click()`); await wait(100);
    assert.equal(await run(`document.querySelector('input[aria-label="Upper-body strength"]').value`),'35');
    record.checks.push('Switching characters restores each model’s own control values');
    await run(`document.querySelector('input[aria-label="Enable upper-body physics for this model"]').click()`); await wait(100);
    assert.equal(await run(`document.querySelector('input[aria-label="Upper-body strength"]').matches(':disabled')`),true);
    await run(`document.querySelector('input[aria-label="Enable upper-body physics for this model"]').click()`); await wait(100);
    record.checks.push('Upper-body off disables its tuning controls');
    await run(`[...document.querySelectorAll('button')].find(b=>b.textContent.includes('Restore this model')).click()`); await wait(100);
    assert.equal(await run(`document.querySelector('input[aria-label="Upper-body strength"]').value`),'100');
    assert.equal(await run(`window.__physicsRequests.every(p=>Object.keys(p.physics.upperBodyByModel).length===1&&Object.keys(p.physics.upperBodyByModel)[0]==='evelyn')`),true);
    record.checks.push('Restore defaults edits only the selected model, including the built-in model');
    fs.writeFileSync(path.join(__dirname, '../artifacts/upper-body-controls-ui.png'), (await window.webContents.capturePage()).toPNG());
  } catch(error) { record.errors.push(error.stack || String(error)); }
  finally { fs.writeFileSync(path.join(__dirname, '../artifacts/upper-body-controls-ui.json'), JSON.stringify(record,null,2)); app.exit(record.errors.length?1:0); }
});
