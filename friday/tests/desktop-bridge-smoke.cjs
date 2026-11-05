/** Actual bundled Node backend + verified frozen worker, disposable offline profile. */
const { spawn } = require('node:child_process');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), net = require('node:net'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'myraa-bridge-'));
fs.writeFileSync(path.join(profile, 'secrets.json'), JSON.stringify({ ignoreEnvironmentApiKey: true }));
fs.writeFileSync(path.join(profile, 'app-settings.v3.json'), JSON.stringify({ version: 3, onboardingComplete: true,
  companion: { enabled: false }, behavior: { proactivity: 'quiet', awayCheckin: false, returnGreeting: false } }));
const record = { scope: 'Actual bundled Node health proxy and frozen worker lifecycle; no provider calls or PC actions.', checks: [], errors: [] };
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (name, passed) => { record.checks.push({ name, passed }); assert.ok(passed, name); };
const log = fs.openSync(path.join(root, 'artifacts/desktop-bridge-server.log'), 'w');
let server, workerPid;
function alive(pid) { try { process.kill(pid, 0); return true; } catch { return false; } }
(async () => {
  try {
    const port = await new Promise((resolve, reject) => {
      const probe = net.createServer(); probe.once('error', reject);
      probe.listen(0, '127.0.0.1', () => { const address = probe.address(); probe.close(() => resolve(address.port)); });
    });
    server = spawn(process.execPath, [path.join(root, 'dist/server.cjs')], { cwd: root, windowsHide: true,
      env: { ...process.env, NODE_ENV: 'production', MYRAA_DATA_DIR: profile, MYRAA_PORT: String(port), GEMINI_API_KEY: '', GOOGLE_API_KEY: '',
        DESKTOP_AGENT_URL: '', MYRAA_AGENT_SESSION_TOKEN: '', MYRAA_PACKAGED_APP: '1', MYRAA_AGENT_EXE: path.join(root, 'agent_dist/myraa-agent/myraa-agent.exe') },
      stdio: ['ignore', log, log, 'ipc'] });
    try { os.setPriority(server.pid, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch {}
    let health;
    for (let attempt = 0; attempt < 100; attempt++) {
      if (server.exitCode !== null) throw new Error('Bundled backend exited during startup.');
      try {
        const response = await fetch(`http://127.0.0.1:${port}/api/agent-health`, { signal: AbortSignal.timeout(1000) });
        if (response.ok) { health = await response.json(); if (health.online) break; }
      } catch {}
      await wait(200);
    }
    check('bundled backend starts its verified frozen worker without source Python', Boolean(health?.online && Number.isInteger(health.tool_count) && health.tool_count > 0));
    check('renderer health proxy returns only online state and tool count', JSON.stringify(Object.keys(health).sort()) === JSON.stringify(['online', 'tool_count']));
    const startup = fs.readFileSync(path.join(profile, 'logs/startup.log'), 'utf8');
    workerPid = Number(startup.match(/AGENT_SESSION_START pid=(\d+)/)?.[1]);
    check('backend owns one live frozen worker', workerPid > 0 && alive(workerPid) && startup.match(/AGENT_SESSION_START pid=/g)?.length === 1);
    check('startup log records authenticated protocol and contains no bearer credential', /AGENT_SESSION_READY protocol=2 tools=\d+/.test(startup) && !/Bearer |MYRAA_AGENT_SESSION_TOKEN|[a-f0-9]{64}/i.test(startup));
    const repeated = await Promise.all(Array.from({ length: 5 }, () => fetch(`http://127.0.0.1:${port}/api/agent-health`).then(response => response.json())));
    check('concurrent renderer health queries share the existing session', repeated.every(value => value.online && value.tool_count === health.tool_count)
      && fs.readFileSync(path.join(profile, 'logs/startup.log'), 'utf8').match(/AGENT_SESSION_START pid=/g)?.length === 1);
    const exit = new Promise(resolve => {
      const timer = setTimeout(() => resolve(false), 5000);
      server.once('exit', () => { clearTimeout(timer); resolve(true); });
    });
    // Electron owns this private IPC channel. Losing it must close the backend,
    // whereas standalone npm runs without IPC remain supported.
    server.disconnect();
    check('losing the owning shell IPC channel closes the bundled backend', await exit);
    for (let i = 0; i < 70 && alive(workerPid); i++) await wait(100);
    check('shell loss leaves no owned frozen launcher', !alive(workerPid));
  } catch (error) { record.errors.push(error.stack || String(error)); }
  finally {
    if (server && server.exitCode === null) server.kill();
    fs.closeSync(log);
    fs.writeFileSync(path.join(root, 'artifacts/desktop-bridge-smoke.json'), JSON.stringify(record, null, 2));
    console.log(JSON.stringify(record)); process.exitCode = record.errors.length ? 1 : 0;
  }
})();
