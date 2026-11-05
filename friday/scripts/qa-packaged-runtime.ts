/** Real packaged Electron launch and shell-crash cleanup in an offline profile. */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import net from 'node:net';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { nativeAgentEnvironment, verifiedFrozenAgent } from '../agent/desktopTransport';
const packaged = path.resolve(process.argv[2] || '');
const record: any = { scope: 'Actual private packaged app, isolated offline profile; API/asset/lifecycle checks, no live inference or desktop actions.', packaged, checks: [], errors: [] };
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'myraa-packaged-'));
const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
const check = (name: string, passed: boolean) => { record.checks.push({ name, passed }); assert.ok(passed, name); };
const alive = (pid: number) => { try { process.kill(pid, 0); return true; } catch { return false; } };
const digest = (file: string) => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function children(pid: number): any[] {
  assert.ok(Number.isInteger(pid) && pid > 0);
  const script = `@(Get-CimInstance Win32_Process -Filter 'ParentProcessId = ${pid}' | Select-Object ProcessId,ExecutablePath,CommandLine) | ConvertTo-Json -Compress`;
  const result = execFileSync('powershell.exe', ['-NoProfile', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], { windowsHide: true, encoding: 'utf8', timeout: 5000 }).trim();
  const parsed = result ? JSON.parse(result) : []; return Array.isArray(parsed) ? parsed : [parsed];
}
let launcher: ReturnType<typeof spawn> | undefined;
let runtimePid = 0, backendPid = 0, workerPid = 0;
try {
  const runtime = path.join(packaged, 'MYRAA-runtime.exe');
  check('packaged backend exactly matches the tested source bundle', digest(path.join(packaged, 'resources/app/dist/server.cjs')) === digest('dist/server.cjs'));
  check('packaged worker passes protocol/digest validation', Boolean(verifiedFrozenAgent(path.join(packaged, 'resources/agent/myraa-agent.exe'))));
  const port = await new Promise<number>((resolve, reject) => {
    const probe = net.createServer(); probe.once('error', reject);
    probe.listen(0, '127.0.0.1', () => { const address = probe.address() as net.AddressInfo; probe.close(() => resolve(address.port)); });
  });
  fs.writeFileSync(path.join(profile, 'secrets.json'), JSON.stringify({ ignoreEnvironmentApiKey: true }));
  fs.writeFileSync(path.join(profile, 'app-settings.v3.json'), JSON.stringify({ version: 3, onboardingComplete: true,
    companion: { enabled: false }, behavior: { proactivity: 'quiet', awayCheckin: false, returnGreeting: false } }));
  launcher = spawn(path.join(packaged, 'MYRAA.exe'), [], { cwd: packaged, windowsHide: true, stdio: 'ignore',
    env: { ...nativeAgentEnvironment(process.env), MYRAA_DATA_DIR: profile, MYRAA_PORT: String(port), DESKTOP_AGENT_URL: '' } });
  let config: any, health: any;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (launcher.exitCode !== null) throw new Error('Packaged launcher exited before readiness.');
    try {
      config = await fetch(`http://127.0.0.1:${port}/api/config`, { signal: AbortSignal.timeout(1000) }).then(response => response.json());
      health = await fetch(`http://127.0.0.1:${port}/api/agent-health`, { signal: AbortSignal.timeout(1000) }).then(response => response.json());
      if (health.online) break;
    } catch {}
    await wait(200);
  }
  check('packaged launcher starts the real offline backend and authenticated worker', Boolean(config && health?.online && health.tool_count > 0));
  check('fresh test profile borrows no owner provider credential', config.hasApiKey === false);
  const policy: any = await fetch(`http://127.0.0.1:${port}/api/permissions`).then(response => response.json());
  check('fresh packaged profile asks before application launch', policy.policy.capabilities.LAUNCH_APP.decision === 'ask');
  const page = await fetch(`http://127.0.0.1:${port}/`).then(response => response.text());
  const asset = page.match(/src="([^"\s]+\.js)"/)?.[1];
  check('packaged HTML and its real JavaScript asset are served', Boolean(asset && (await fetch(`http://127.0.0.1:${port}${asset}`)).ok));
  const runtimeProcess = children(launcher.pid!).find(child => String(child.ExecutablePath).toLowerCase() === runtime.toLowerCase());
  runtimePid = runtimeProcess?.ProcessId || 0;
  check('test launcher owns the exact packaged Electron shell', runtimePid > 0 && alive(runtimePid));
  const backend = children(runtimePid).find(child => String(child.CommandLine).includes('server.cjs') && String(child.ExecutablePath).toLowerCase() === runtime.toLowerCase());
  backendPid = backend?.ProcessId || 0;
  const startup = fs.readFileSync(path.join(profile, 'logs/startup.log'), 'utf8');
  workerPid = Number(startup.match(/AGENT_SESSION_START pid=(\d+)/)?.[1]);
  check('packaged shell owns the backend and one live frozen launcher', backendPid > 0 && workerPid > 0 && alive(backendPid) && alive(workerPid));
  // Terminate only the exact Electron child this harness launched. The owner app
  // and all executables outside this private candidate directory stay untouched.
  process.kill(runtimePid);
  for (let i = 0; i < 70 && (alive(backendPid) || alive(workerPid)); i++) await wait(100);
  check('hard packaged-shell crash closes both backend and frozen worker', !alive(backendPid) && !alive(workerPid));
  let closed = false;
  try { await fetch(`http://127.0.0.1:${port}/api/config`, { signal: AbortSignal.timeout(500) }); } catch { closed = true; }
  check('hard shell crash closes the candidate backend port', closed);
} catch (error) { record.errors.push(String(error)); }
finally {
  for (const pid of [runtimePid, backendPid]) if (pid > 0 && alive(pid)) { try { process.kill(pid); } catch {} }
  if (launcher?.exitCode === null) { try { launcher.kill(); } catch {} }
  fs.writeFileSync(process.argv[3] || 'artifacts/rc3-packaged-runtime.json', JSON.stringify(record, null, 2));
}
console.log(JSON.stringify(record));
process.exitCode = record.errors.length || record.checks.some((item: any) => !item.passed) ? 1 : 0;
