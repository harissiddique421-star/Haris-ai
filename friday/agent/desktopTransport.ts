/** Authenticated, owned loopback session for the native desktop worker. */
import { spawn, type ChildProcess } from 'node:child_process';
import { randomBytes, createHash } from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';

export interface DesktopAgentLaunch { command: string; args: string[]; cwd: string; }
export interface DesktopAgentHealth { protocol: 2; authenticated: true; tool_count: number; tools?: string[]; }
interface Options {
  launch: () => DesktopAgentLaunch | null;
  externalUrl?: string;
  externalToken?: string;
  log?: (message: string) => void;
  startupTimeoutMs?: number;
}

export function nativeAgentEnvironment(source: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  return Object.fromEntries(Object.entries(source).filter(([key]) =>
    !/(?:^|_)(?:API_KEY|TOKEN|SECRET|SECRET_KEY|ACCESS_KEY|PASSWORD|CREDENTIALS?|PRIVATE_KEY)$/i.test(key)
    && !['ELECTRON_RUN_AS_NODE', 'NODE_OPTIONS'].includes(key)));
}

/** A stale frozen binary must never be launched as an unauthenticated worker. */
export function verifiedFrozenAgent(executable: string | undefined): DesktopAgentLaunch | null {
  if (!executable) return null;
  try {
    const stamp = JSON.parse(fs.readFileSync(path.join(path.dirname(executable), 'agent-protocol.json'), 'utf8'));
    if (stamp.protocol !== 2 || !/^[a-f0-9]{64}$/.test(stamp.executableSha256)) return null;
    const actual = createHash('sha256').update(fs.readFileSync(executable)).digest('hex');
    return actual === stamp.executableSha256 ? { command: executable, args: [], cwd: path.dirname(executable) } : null;
  } catch { return null; }
}

function localEndpoint(input: string): string {
  const url = new URL(input);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
    || url.username || url.password || url.search || url.hash || !['', '/'].includes(url.pathname)) throw new Error('Desktop worker must use a loopback HTTP endpoint.');
  if (url.hostname === 'localhost') url.hostname = '127.0.0.1';
  return url.origin;
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const probe = net.createServer();
    probe.once('error', reject);
    probe.listen(0, '127.0.0.1', () => { const address = probe.address(); probe.close(() => typeof address === 'object' && address ? resolve(address.port) : reject(new Error('No worker port.'))); });
  });
}

function withAbort<T>(operation: Promise<T>, signal?: AbortSignal | null): Promise<T> {
  if (!signal) return operation;
  if (signal.aborted) return Promise.reject(new DOMException('Desktop action cancelled.', 'AbortError'));
  return new Promise((resolve, reject) => {
    const abort = () => reject(new DOMException('Desktop action cancelled.', 'AbortError'));
    signal.addEventListener('abort', abort, { once: true });
    operation.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort));
  });
}

export class ManagedDesktopAgent {
  private token = randomBytes(32).toString('hex');
  private endpointValue: string | null = null;
  private child: ChildProcess | null = null;
  private ready: DesktopAgentHealth | null = null;
  private starting: Promise<boolean> | null = null;
  private disposed = false;
  private invalidConfiguration = false;
  private failures = 0;
  private retryAfter = 0;
  private readonly external: boolean;

  constructor(private readonly options: Options) {
    this.external = Boolean(options.externalUrl);
    if (this.external) {
      try {
        this.endpointValue = localEndpoint(options.externalUrl!);
        if (!/^[A-Za-z0-9_-]{32,256}$/.test(options.externalToken || '')) throw new Error('Missing session authentication.');
        this.token = options.externalToken!;
      } catch { this.invalidConfiguration = true; this.note('AGENT_SESSION_CONFIG_INVALID'); }
    }
  }

  get endpoint(): string | null { return this.endpointValue; }
  get ownedPid(): number | null { return this.child?.pid ?? null; }
  private note(message: string): void { try { this.options.log?.(message); } catch {} }

  private async probe(signal?: AbortSignal): Promise<DesktopAgentHealth | null> {
    if (!this.endpointValue) return null;
    try {
      const response = await fetch(`${this.endpointValue}/health`, { headers: { Authorization: `Bearer ${this.token}` },
        redirect: 'error', signal: signal || AbortSignal.timeout(1000) });
      if (!response.ok) return null;
      const result = await response.json() as DesktopAgentHealth;
      return result.protocol === 2 && result.authenticated === true && Number.isInteger(result.tool_count) && result.tool_count >= 0 ? result : null;
    } catch { return null; }
  }

  ensure(signal?: AbortSignal | null): Promise<boolean> {
    if (signal?.aborted) return Promise.reject(new DOMException('Desktop action cancelled.', 'AbortError'));
    if (this.disposed || this.invalidConfiguration) return Promise.resolve(false);
    if (this.ready) return Promise.resolve(true);
    if (this.starting) return withAbort(this.starting, signal);
    if (Date.now() < this.retryAfter) return Promise.resolve(false);
    this.starting = this.start().finally(() => { this.starting = null; });
    return withAbort(this.starting, signal);
  }

  private async start(): Promise<boolean> {
    try {
      if (this.external) { this.ready = await this.probe(); return Boolean(this.ready); }
      // A busy or temporarily unreachable live worker may be finishing a user
      // action. Never terminate it or replay that action because health failed.
      if (this.child && this.child.exitCode === null) { this.ready = await this.probe(); return Boolean(this.ready); }
      this.stopOwnedChild();
      const launch = this.options.launch();
      if (!launch) throw new Error('No secured desktop worker is available.');
      const port = await freePort();
      if (this.disposed) return false;
      this.endpointValue = `http://127.0.0.1:${port}`;
      this.token = randomBytes(32).toString('hex');
      const child = spawn(launch.command, launch.args, { cwd: launch.cwd, detached: false, windowsHide: true, stdio: 'ignore',
        env: { ...nativeAgentEnvironment(process.env), MYRAA_AGENT_HOST: '127.0.0.1', MYRAA_AGENT_PORT: String(port),
          MYRAA_AGENT_SESSION_TOKEN: this.token, MYRAA_AGENT_PARENT_PID: String(process.pid) } });
      this.child = child;
      let failed = false;
      child.once('error', () => { failed = true; });
      child.once('exit', () => { if (this.child === child) { this.child = null; this.ready = null; } });
      this.note(`AGENT_SESSION_START pid=${child.pid ?? 0}`);
      const deadline = Date.now() + (this.options.startupTimeoutMs ?? 20_000);
      while (!this.disposed && !failed && child.exitCode === null && Date.now() < deadline) {
        this.ready = await this.probe();
        if (this.disposed) { this.ready = null; return false; }
        if (this.ready) { this.failures = 0; this.note(`AGENT_SESSION_READY protocol=2 tools=${this.ready.tool_count}`); return true; }
        await new Promise(resolve => setTimeout(resolve, 200));
      }
    } catch { /* Deliberately exclude child environment, arguments and raw errors from logs. */ }
    this.stopOwnedChild(); this.ready = null;
    this.retryAfter = Date.now() + Math.min(60_000, 2000 * 2 ** Math.min(++this.failures, 5));
    this.note('AGENT_SESSION_UNAVAILABLE');
    return false;
  }

  async health(signal?: AbortSignal): Promise<DesktopAgentHealth | null> {
    if (!await this.ensure(signal)) return null;
    const health = await this.probe(signal);
    if (this.disposed) return null;
    if (health) this.ready = health;
    return health;
  }

  async request(route: '/execute' | '/observe' | '/tools', init: RequestInit = {}): Promise<Response> {
    if (!await this.ensure(init.signal)) throw new Error('Desktop control is unavailable. Restart MYRAA.');
    if (this.disposed) throw new Error('Desktop control session has closed.');
    if (init.signal?.aborted) throw new DOMException('Desktop action cancelled.', 'AbortError');
    const headers = new Headers(init.headers); headers.set('Authorization', `Bearer ${this.token}`);
    try {
      const response = await fetch(`${this.endpointValue}${route}`, { ...init, headers, redirect: 'error', signal: init.signal || AbortSignal.timeout(25_000) });
      if (response.status === 401 || response.status === 403) { this.ready = null; throw new Error('The desktop session could not be authenticated. Restart MYRAA.'); }
      return response;
    } catch (error) { this.ready = null; throw error; }
  }

  private stopOwnedChild(): void {
    const child = this.child; this.child = null;
    if (child && child.exitCode === null) { try { child.kill(); } catch {} }
  }

  dispose(): void { this.disposed = true; this.ready = null; this.stopOwnedChild(); }
}
