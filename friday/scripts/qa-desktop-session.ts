/** Real HTTP worker authentication/lifecycle checks; never observes the desktop. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { ManagedDesktopAgent, nativeAgentEnvironment, verifiedFrozenAgent } from '../agent/desktopTransport';
const mode = process.env.MYRAA_QA_AGENT_MODE === 'frozen' ? 'frozen' : 'source';
const record: { mode: string; checks: { name: string; passed: boolean }[]; errors: string[] } = { mode, checks: [], errors: [] };
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'myraa-session-'));
const note = path.join(temp, 'must-not-exist.txt');
const client = new ManagedDesktopAgent({ launch: () => mode === 'frozen'
  ? verifiedFrozenAgent(path.resolve(process.env.MYRAA_QA_AGENT_EXE || 'agent_dist/myraa-agent/myraa-agent.exe'))
  : { command: process.env.MYRAA_PYTHON || 'python', args: ['-m', 'desktop_agent.main'], cwd: process.cwd() } });
const check = (name: string, condition: boolean) => { record.checks.push({ name, passed: condition }); assert.ok(condition, name); };
const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
async function isClosed(endpoint: string, timeout = 7000): Promise<boolean> {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try { await fetch(`${endpoint}/health`, { signal: AbortSignal.timeout(300) }); }
    catch { return true; }
    await wait(100);
  }
  return false;
}
let owner: ReturnType<typeof spawn> | undefined;
try {
  check('real Python worker authenticates and reports protocol 2', await client.ensure() && (await client.health())?.protocol === 2);
  const endpoint = client.endpoint!;
  const health = await client.health();
  check('registered tool count comes from the real worker', Boolean(health && health.tool_count > 0 && health.tools?.length === health.tool_count));
  for (const route of ['/health', '/tools', '/observe', '/docs', '/openapi.json']) {
    const response = await fetch(endpoint + route);
    check(`unauthenticated ${route} is rejected`, response.status === 401 && !response.headers.has('access-control-allow-origin'));
  }
  const blocked = await fetch(endpoint + '/execute', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tool: 'createFile', args: { path: note, content: 'must not write' } }) });
  check('direct execution cannot write the disposable note without authentication', blocked.status === 401 && !fs.existsSync(note));
  const wrong = await fetch(endpoint + '/tools', { headers: { Authorization: 'Bearer invalid-test-session' } });
  check('wrong session is rejected', wrong.status === 401);
  // Node fetch normalizes Host; raw http is needed to exercise a forged header.
  const reboundStatus = await new Promise<number>((resolve, reject) => {
    const request = http.get(endpoint + '/tools', { headers: { Host: 'rebound.example.test' } }, response => {
      response.resume(); resolve(response.statusCode || 0);
    });
    request.once('error', reject);
    request.setTimeout(2000, () => request.destroy(new Error('Host check timed out.')));
  });
  check('nonloopback Host is rejected', reboundStatus === 403);
  let browserRejected = false;
  try { await client.request('/tools', { headers: { Origin: 'http://localhost:3000' } }); }
  catch (error) { browserRejected = /authenticated/.test(String(error)); }
  check('browser Origin is rejected even on an authenticated request', browserRejected);
  const unknown = await client.request('/execute', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tool: 'myraa_qa_unknown_no_action', args: {} }) });
  const result = await unknown.json() as { ok?: boolean; tool?: string };
  check('authenticated dispatch reaches the real registry without performing an action', unknown.ok && result.ok === false && result.tool === 'myraa_qa_unknown_no_action');
  client.dispose();
  check('disposing the backend session closes its worker port', await isClosed(endpoint));

  owner = spawn(process.execPath, ['--import', 'tsx', path.resolve('tests/fixtures/desktop-owner.ts')], { windowsHide: true,
    env: { ...nativeAgentEnvironment(process.env), MYRAA_QA_AGENT_MODE: mode }, stdio: ['ignore', 'ignore', 'ignore', 'ipc'] });
  const info = await new Promise<{ endpoint: string; pid: number }>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Test owner did not start its worker.')), 25000);
    owner!.once('message', value => { clearTimeout(timer); resolve(value as { endpoint: string; pid: number }); });
    owner!.once('exit', () => { clearTimeout(timer); reject(new Error('Test owner exited before readiness.')); });
    owner!.once('error', error => { clearTimeout(timer); reject(error); });
  });
  check('test backend owns a separate real worker', Boolean(info.pid && info.endpoint !== endpoint));
  owner.kill();
  check('abrupt backend termination closes the worker through its owner watchdog', await isClosed(info.endpoint));
} catch (error) { record.errors.push(String(error)); }
finally {
  client.dispose(); if (owner && owner.exitCode === null) owner.kill();
  // Only this test's empty directory is removed; unexpected contents are kept.
  try { fs.rmdirSync(temp); } catch {}
  fs.mkdirSync('artifacts', { recursive: true });
  fs.writeFileSync(`artifacts/desktop-session-${mode}.json`, JSON.stringify(record, null, 2));
}
console.log(JSON.stringify(record));
process.exitCode = record.errors.length || record.checks.some(check => !check.passed) ? 1 : 0;
