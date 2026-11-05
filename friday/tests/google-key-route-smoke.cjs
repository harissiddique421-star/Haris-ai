/** Run after npm run build. Tests actual routes with an isolated provider fixture. */
const { spawn } = require('node:child_process');
const fs = require('node:fs'); const os = require('node:os'); const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'myraa-auth-routes-'));
fs.writeFileSync(path.join(profile, 'app-settings.v3.json'), JSON.stringify({ version: 3, onboardingComplete: true, companion: { enabled: false }, behavior: { proactivity: 'quiet', awayCheckin: false, returnGreeting: false } }));
const log = fs.openSync(path.join(root, 'artifacts/auth-key-route-server.log'), 'w');
const server = spawn(process.execPath, ['--require', path.join(__dirname, 'google-key-probe-fixture.cjs'), path.join(root, 'dist/server.cjs')], {
  cwd: root, env: { ...process.env, NODE_ENV: 'production', MYRAA_DATA_DIR: profile, MYRAA_PORT: '5192', GOOGLE_API_KEY: '', GEMINI_API_KEY: '' },
  windowsHide: true, stdio: ['ignore', log, log],
});
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const output = { checks: [], errors: [], provider: 'deterministic fixture; no real key or live Google request' };
const fake = name => 'AQ.fixture_' + name + '_not_a_real_credential_0000000000';
const save = async key => {
  const response = await fetch('http://127.0.0.1:5192/api/config/apikey', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ apiKey: key }) });
  return { status: response.status, body: await response.json() };
};
(async () => {
  try {
    let ready = false;
    for (let i = 0; i < 100; i++) {
      try { ready = (await fetch('http://127.0.0.1:5192/api/config')).ok; } catch {}
      if (ready) break; await wait(300);
    }
    assert.ok(ready, 'isolated backend started');
    const valid = await save(fake('valid'));
    assert.equal(valid.status, 200); assert.equal(valid.body.validation, 'verified');
    output.checks.push('AQ credential sent in x-goog-api-key header; verified success recorded');
    const protectedFile = path.join(profile, 'secrets.v2.json');
    const prior = fs.readFileSync(protectedFile, 'utf8');
    for (const [name, reason] of [['account', 'ACCOUNT_STATE_INVALID'], ['suspended', 'CONSUMER_SUSPENDED']]) {
      const denied = await save(fake(name));
      assert.equal(denied.status, 400); assert.equal(denied.body.code, reason);
      assert.ok(!denied.body.error.includes(fake(name)));
      assert.equal(fs.readFileSync(protectedFile, 'utf8'), prior);
      output.checks.push(reason + ' reported precisely; previous protected key preserved');
    }
    await wait(100);
    const errorLog = fs.readFileSync(path.join(profile, 'logs/errors.log'), 'utf8');
    assert.ok(!errorLog.includes(fake('account')) && !errorLog.includes(fake('suspended')));
    output.checks.push('Google-echoed AQ keys redacted before writing logs');
    fs.appendFileSync(path.join(profile, 'logs/errors.log'), '\nLegacy test record: ' + fake('historical') + '\n');
    const logs = await fetch('http://127.0.0.1:5192/api/logs/errors').then(r => r.json());
    assert.ok(!JSON.stringify(logs).includes(fake('historical')));
    output.checks.push('historical AQ key masked in log viewer without editing the original log');
    const quota = await save(fake('quota'));
    assert.equal(quota.status, 200); assert.equal(quota.body.validation, 'unverified');
    output.checks.push('quota does not report invalid key or verified success');
  } catch (error) { output.errors.push(String(error)); }
  finally {
    server.kill(); fs.closeSync(log);
    fs.writeFileSync(path.join(root, 'artifacts/auth-key-route-smoke.json'), JSON.stringify(output, null, 2));
    console.log(JSON.stringify(output)); process.exitCode = output.errors.length ? 1 : 0;
  }
})();
