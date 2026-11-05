/** Real desktop-agent smoke test. Uses a new policy/profile and its own note. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { ManagedDesktopAgent, verifiedFrozenAgent } from '../agent/desktopTransport';
import { ActionExecutor } from '../agent/actions/executor';
import { CancellationScope } from '../agent/cancellation';
import { AuditLog } from '../agent/audit';
import { DownloadManager } from '../agent/downloads';
import { PerceptionEngine } from '../agent/perception/engine';
import { InputArbiter, ResourceScheduler } from '../agent/scheduler';
import { ContactBook } from '../memory/contacts';
import { PermissionEngine } from '../permissions/engine';
import { ConfirmationBroker } from '../permissions/confirmations';
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'myraa-actions-'));
const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
const record: any = { checks: [], errors: [] };
const executable = path.resolve(process.env.MYRAA_QA_AGENT_EXE || 'agent_dist/myraa-agent/myraa-agent.exe');
const agent = new ManagedDesktopAgent({ launch: () => verifiedFrozenAgent(executable) });
const calls: string[] = [];
const call = async (tool: string, args: Record<string, unknown>, signal?: AbortSignal) => {
  calls.push(tool);
  return agent.request('/execute', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool, args }), signal: signal || AbortSignal.timeout(30000) }).then(response => response.json());
};
const scope = new CancellationScope('real-desktop-smoke');
const token = path.basename(profile);
const note = path.join(profile, `${token}.txt`);
let opened = false;
try {
  if (!await agent.ensure()) throw new Error('Verified frozen desktop agent did not start.');
  record.checks.push({ name: 'verified frozen Python agent starts in an authenticated owned session', passed: (await agent.health())?.protocol === 2 });
  record.checks.push({ name: 'direct requests cannot bypass the Node permission layer', passed: (await fetch(`${agent.endpoint}/execute`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool: 'createFile', args: { path: note, content: 'must not write' } }) })).status === 401 && !fs.existsSync(note) });
  const permissions = new PermissionEngine(profile, {}); await permissions.initialize();
  const confirmations = new ConfirmationBroker();
  const contacts = new ContactBook(profile); await contacts.initialize();
  const executor = new ActionExecutor({ call, perception: new PerceptionEngine(call, null), permissions, confirmations,
    scheduler: new ResourceScheduler(), arbiter: new InputArbiter(), downloads: new DownloadManager(profile, async () => profile),
    contacts, audit: new AuditLog(profile), remember: async () => {}, notify: () => {} });
  const context = { taskId: token, goal: 'Verify the MYRAA test note only', state: null, scope };
  await permissions.setDecision('WRITE_FILE', 'allow');
  const text = 'MYRAA deterministic desktop verification';
  const created = await executor.execute({ tool: 'fs.create_text', args: { path: note, content: text } }, context);
  record.checks.push({ name: 'create text file through real action executor and native tool', passed: created.ok && fs.readFileSync(note, 'utf8') === text });
  const read = await executor.execute({ tool: 'fs.read_text', args: { path: note } }, context);
  record.checks.push({ name: 'read text through real native tool', passed: read.ok && JSON.stringify(read.data).includes(text) });
  await permissions.setDecision('LAUNCH_APP', 'deny');
  const denied = await executor.execute({ tool: 'fs.open', args: { path: note } }, context);
  record.checks.push({ name: 'denial prevents native file launch', passed: denied.category === 'PERMISSION_DENIED' && !calls.includes('openPath') });
  if (process.env.MYRAA_QA_OPEN_NOTE === '1') {
    await permissions.setDecision('LAUNCH_APP', 'ask');
    const pending = executor.execute({ tool: 'fs.open', args: { path: note } }, context);
    let approval;
    for (let i = 0; i < 100; i++) { approval = confirmations.list()[0]; if (approval) break; await wait(50); }
    if (!approval) throw new Error('File launch did not request consent.');
    // Approval is only for this explicit test action in the disposable profile.
    confirmations.resolve(approval.id, { approved: true, remember: 'once', via: 'api' });
    const result = await pending; opened = result.ok;
    let found = false;
    for (let i = 0; i < 20; i++) {
      const windows = await call('listVisibleWindows', { limit: 100 });
      found = windows.result?.windows?.some((window: any) => String(window.title).includes(token));
      if (found) break; await wait(500);
    }
    record.checks.push({ name: 'one-time consent opens the exact note in a real application', passed: result.ok && found && calls.includes('openPath') && !calls.includes('selectFiles') });
  }
} catch (error) { record.errors.push(String(error)); }
finally {
  if (opened) { try { await call('closeWindow', { title: token }); } catch {} }
  scope.dispose(); agent.dispose();
  fs.writeFileSync('artifacts/desktop-actions-authenticated.json', JSON.stringify(record, null, 2));
}
console.log(JSON.stringify(record));
process.exitCode = record.errors.length || record.checks.some((check: any) => !check.passed) ? 1 : 0;
