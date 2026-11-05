/** Inspect distributable bytes without installing or running either installer. */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { pipeline } from 'node:stream/promises';
const root = path.resolve(process.argv[2] || '');
const unpacked = path.resolve(process.argv[6] || path.join(root, 'win-unpacked'));
const seven = 'C:/Program Files/7-Zip/7z.exe';
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'myraa-payload-'));
const record: any = { scope: 'Archive integrity and exact payload hashes only; not installer/upgrade/portable execution.', checks: [], errors: [] };
async function fileHash(file: string) {
  const hash = createHash('sha256'); for await (const chunk of fs.createReadStream(file)) hash.update(chunk); return hash.digest('hex');
}
function extract(archive: string, entry: string, destination?: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = spawn(seven, ['x', '-so', archive, entry], { windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] });
    const hash = createHash('sha256'); let bytes = 0;
    child.stdout.on('data', chunk => { bytes += chunk.length; hash.update(chunk); });
    const output = destination ? pipeline(child.stdout, fs.createWriteStream(destination)) : Promise.resolve();
    child.once('error', reject);
    child.once('close', code => { void output.then(() => code === 0 && bytes > 0 ? resolve(hash.digest('hex')) : reject(new Error('Archive entry extraction failed.')), reject); });
  });
}
async function integrity(archive: string) {
  await new Promise<void>((resolve, reject) => {
    const child = spawn(seven, ['t', archive], { windowsHide: true, stdio: 'ignore' });
    child.once('error', reject); child.once('exit', code => code === 0 ? resolve() : reject(new Error('Archive integrity check failed.')));
  });
}
try {
  const members = ['MYRAA.exe', 'MYRAA-runtime.exe', 'resources/app/dist/server.cjs', 'resources/agent/myraa-agent.exe', 'resources/agent/agent-protocol.json'];
  const expected = Object.fromEntries(await Promise.all(members.map(async entry => [entry, await fileHash(path.join(unpacked, entry))])));
  for (const kind of (process.argv[5] ? process.argv[5].split(',') : ['Setup', 'Portable'])) {
    const executable = path.join(root, `MYRAA-${kind}-${process.argv[3] || '2.0.0-rc.3'}.exe`);
    const payload = path.join(temp, `${kind}.7z`);
    await integrity(executable);
    record.checks.push({ name: `${kind} outer archive integrity`, passed: true, executableSha256: await fileHash(executable) });
    await extract(executable, '$PLUGINSDIR/app-64.7z', payload);
    await integrity(payload);
    record.checks.push({ name: `${kind} embedded payload integrity`, passed: true });
    for (const member of members) {
      const digest = await extract(payload, member);
      const passed = digest === expected[member];
      record.checks.push({ name: `${kind} exact ${member}`, passed, sha256: digest });
      if (!passed) throw new Error(`${kind} payload differs from its tested unpacked runtime: ${member}`);
    }
    fs.unlinkSync(payload);
  }
} catch (error) { record.errors.push(String(error)); }
finally {
  try { fs.rmdirSync(temp); } catch {}
  fs.writeFileSync(process.argv[4] || 'artifacts/rc3-payload-verification.json', JSON.stringify(record, null, 2));
}
console.log(JSON.stringify(record));
process.exitCode = record.errors.length || record.checks.some((check: any) => !check.passed) ? 1 : 0;
