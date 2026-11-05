import assert from 'node:assert/strict';
import test from 'node:test';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { getEventListeners } from 'node:events';
import { ManagedDesktopAgent, nativeAgentEnvironment, verifiedFrozenAgent } from '../agent/desktopTransport';
const fixture = fileURLToPath(new URL('./fixtures/agent-session.cjs', import.meta.url));
const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

test('native child environments exclude provider credentials and Electron backend flags', () => {
  const result = nativeAgentEnvironment({ PATH: 'fixture-path', MYRAA_EXECUTABLE: 'fixture-app.exe', GEMINI_API_KEY: 'test-only', OPENAI_API_KEY: 'test-only',
    GOOGLE_APPLICATION_CREDENTIALS: 'test-only.json', GITHUB_TOKEN: 'test-only', ELECTRON_RUN_AS_NODE: '1', NODE_OPTIONS: '--require fixture' });
  assert.deepEqual(result, { PATH: 'fixture-path', MYRAA_EXECUTABLE: 'fixture-app.exe' });
});

test('frozen workers need a matching protocol stamp and executable digest', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'myraa-protocol-'));
  const exe = path.join(root, 'myraa-agent.exe'), stamp = path.join(root, 'agent-protocol.json');
  try {
    fs.writeFileSync(exe, 'Not an executable; protocol validation fixture.');
    assert.equal(verifiedFrozenAgent(exe), null);
    fs.writeFileSync(stamp, JSON.stringify({ protocol: 2, executableSha256: createHash('sha256').update(fs.readFileSync(exe)).digest('hex') }));
    assert.equal(verifiedFrozenAgent(exe)?.command, exe);
    fs.writeFileSync(exe, 'Changed fixture'); assert.equal(verifiedFrozenAgent(exe), null);
  } finally { fs.unlinkSync(exe); if(fs.existsSync(stamp))fs.unlinkSync(stamp); fs.rmdirSync(root); }
});

test('concurrent requests share one authenticated owned worker and cleanup closes it', async () => {
  let launches = 0; const logs: string[] = [];
  const client = new ManagedDesktopAgent({ launch: () => { launches++; return { command: process.execPath, args: [fixture], cwd: path.dirname(fixture) }; }, log: value => logs.push(value) });
  try {
    assert.deepEqual(await Promise.all(Array.from({ length: 6 }, () => client.ensure())), Array(6).fill(true));
    assert.equal(launches, 1); assert.ok(client.ownedPid);
    assert.equal((await fetch(`${client.endpoint}/health`)).status, 401);
    assert.equal((await client.request('/execute', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool: 'fixture_only' }) })).status, 200);
    assert.equal((await client.health())?.protocol, 2);
    assert.ok(!logs.join('\n').includes('Bearer') && !/[a-f0-9]{64}/.test(logs.join('\n')));
    const endpoint = client.endpoint; client.dispose();
    let closed = false;
    for(let i=0;i<20;i++){try{await fetch(`${endpoint}/health`,{signal:AbortSignal.timeout(200)})}catch{closed=true;break}await wait(25)}
    assert.equal(closed,true);
  } finally { client.dispose(); }
});

test('legacy HTTP 200 is not authenticated readiness and external services are never killed', async () => {
  let executed = 0;
  const server = http.createServer((request, response) => { if(request.url==='/execute')executed++; response.setHeader('Content-Type','application/json');response.end('{"tool_count":84}'); });
  await new Promise<void>(resolve => server.listen(0,'127.0.0.1',resolve));
  const address = server.address() as import('node:net').AddressInfo;
  const url = `http://127.0.0.1:${address.port}`;
  const client = new ManagedDesktopAgent({ externalUrl: url, externalToken: 'fixture_only_session_token_0000000000000000', launch: () => { throw new Error('Must not spawn'); } });
  try {
    assert.equal(await client.ensure(),false);
    await assert.rejects(client.request('/execute',{method:'POST'}),/unavailable/);
    assert.equal(executed,0); client.dispose();
    assert.equal((await fetch(`${url}/health`)).status,200);
  } finally { client.dispose(); await new Promise<void>(resolve => server.close(()=>resolve())); }
});

test('cancellation during shared startup is prompt and does not leak listeners or start a duplicate worker', async () => {
  let launches=0;
  const client = new ManagedDesktopAgent({ launch:()=>{launches++;return{command:process.execPath,args:[fixture,'--delay=500'],cwd:path.dirname(fixture)}} });
  const controller = new AbortController();
  try {
    const pending=client.ensure(controller.signal);controller.abort();
    await assert.rejects(pending,{name:'AbortError'});
    assert.equal(await client.ensure(),true); assert.equal(launches,1);
    await wait(0); assert.equal(getEventListeners(controller.signal,'abort').length,0);
  } finally { client.dispose(); }
});

test('non-loopback or unauthenticated custom endpoints fail closed without launching', async () => {
  for(const options of [{externalUrl:'https://example.com'}, {externalUrl:'http://127.0.0.1:8765'}]) {
    let launched=false; const client=new ManagedDesktopAgent({...options,launch:()=>{launched=true;return null}});
    assert.equal(await client.ensure(),false); assert.equal(launched,false);client.dispose();
  }
});

test('a transient health failure does not terminate an authenticated live worker', async () => {
  let launches = 0;
  const client = new ManagedDesktopAgent({ launch: () => { launches++; return { command: process.execPath, args: [fixture, '--health-fail-once'], cwd: path.dirname(fixture) }; } });
  try {
    assert.equal(await client.ensure(), true); const pid = client.ownedPid;
    assert.equal(await client.health(), null);
    assert.equal((await client.request('/execute', { method: 'POST' })).status, 200);
    assert.equal(launches, 1); assert.equal(client.ownedPid, pid);
  } finally { client.dispose(); }
});
