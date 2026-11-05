/** Real settings API and disk persistence; no owner profile, keys or inference. */
const { spawn } = require('node:child_process');
const fs = require('node:fs'), path = require('node:path'), os = require('node:os'), assert = require('node:assert/strict');
try { os.setPriority(0, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {}
const root = path.resolve(__dirname, '..'), profile = fs.mkdtempSync(path.join(os.tmpdir(), 'myraa-body-settings-'));
fs.writeFileSync(path.join(profile, 'secrets.json'), JSON.stringify({ ignoreEnvironmentApiKey: true }));
fs.writeFileSync(path.join(profile, 'app-settings.v3.json'), JSON.stringify({ version: 3, onboardingComplete: true, companion: { enabled: false }, behavior: { proactivity: 'quiet', awayCheckin: false, returnGreeting: false } }));
const log = fs.openSync(path.join(root, 'artifacts/upper-body-route-server.log'), 'w');
const record = { scope: 'Actual isolated backend settings routes and restart; no live provider calls or owner credentials.', checks: [], errors: [] };
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
let server;
async function start() {
  server = spawn(process.execPath, [path.join(root, 'dist/server.cjs')], { cwd: root, windowsHide: true,
    env: { ...process.env, NODE_ENV: 'production', MYRAA_DATA_DIR: profile, MYRAA_PORT: '5192', GEMINI_API_KEY: '', GOOGLE_API_KEY: '' }, stdio: ['ignore', log, log] });
  try { os.setPriority(server.pid, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {}
  for (let i = 0; i < 80; i++) {
    try { if ((await fetch('http://127.0.0.1:5192/api/config')).ok) return; } catch {}
    if (server.exitCode !== null) throw new Error('Isolated backend stopped during startup.');
    await wait(200);
  }
  throw new Error('Isolated backend did not become ready.');
}
async function stop() {
  if (!server || server.exitCode !== null) return;
  await new Promise(resolve => { const timer = setTimeout(() => { server.kill(); resolve(); }, 3000); server.once('exit', () => { clearTimeout(timer); resolve(); }); server.kill(); });
}
async function settings(patch) {
  const response = await fetch('http://127.0.0.1:5192/api/app-settings', patch ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(patch) } : {});
  assert.equal(response.status, 200); return response.json();
}
(async () => {
  try {
    await start();
    const first = await settings({ physics: { upperBodyByModel: { evelyn: { enabled: false, strength: 0.4 }, astra: { strength: 0.25 } } } });
    assert.equal(first.physics.upperBodyByModel.evelyn.enabled, false);
    assert.equal(first.physics.upperBodyByModel.astra.strength, 0.25);
    record.checks.push('Built-in and imported model settings are accepted independently');
    const next = await settings({ physics: { upperBodyByModel: { evelyn: { maxAngleDeg: 99, quality: 'low' } } } });
    assert.equal(next.physics.upperBodyByModel.evelyn.maxAngleDeg, 6);
    assert.equal(next.physics.upperBodyByModel.evelyn.strength, 0.4);
    assert.equal(next.physics.upperBodyByModel.astra.strength, 0.25);
    record.checks.push('Partial model updates preserve other fields/models and clamp unsafe angles');
    const persisted = JSON.parse(fs.readFileSync(path.join(profile, 'app-settings.v3.json'), 'utf8'));
    assert.deepEqual(persisted.physics.upperBodyByModel, next.physics.upperBodyByModel);
    record.checks.push('Normalized per-model settings are written to the real settings file');
    const oldPid = server.pid; await stop(); await start();
    assert.notEqual(server.pid, oldPid);
    assert.deepEqual((await settings()).physics.upperBodyByModel, next.physics.upperBodyByModel);
    record.checks.push('Per-model settings survive a fresh backend process');
  } catch (error) { record.errors.push(error.stack || String(error)); }
  finally { await stop(); fs.closeSync(log); fs.writeFileSync(path.join(root, 'artifacts/upper-body-routes.json'), JSON.stringify(record, null, 2)); console.log(JSON.stringify({ checks: record.checks.length, errors: record.errors })); process.exitCode = record.errors.length ? 1 : 0; }
})();
