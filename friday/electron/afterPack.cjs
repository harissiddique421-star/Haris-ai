'use strict';

const fs = require('fs');
const path = require('path');
const { createHash } = require('crypto');

module.exports = async function afterPack(context) {
  if (context.electronPlatformName !== 'win32') return;

  const agentRoot = path.join(context.appOutDir, 'resources', 'agent');
  const stamp = JSON.parse(fs.readFileSync(path.join(agentRoot, 'agent-protocol.json'), 'utf8'));
  const digest = createHash('sha256').update(fs.readFileSync(path.join(agentRoot, 'myraa-agent.exe'))).digest('hex');
  if (stamp.protocol !== 2 || stamp.executableSha256 !== digest) throw new Error('Packaged desktop worker protocol/digest is invalid; run npm run build:agent.');

  const productName = context.packager.appInfo.productFilename;
  const primaryExe = path.join(context.appOutDir, productName + '.exe');
  const runtimeExe = path.join(context.appOutDir, productName + '-runtime.exe');
  const launcherExe = path.join(__dirname, '..', 'build', 'MYRAA-launcher.exe');

  if (!fs.existsSync(primaryExe)) throw new Error('Packaged Electron runtime is missing: ' + primaryExe);
  if (!fs.existsSync(launcherExe)) throw new Error('MYRAA launcher is missing: ' + launcherExe);

  await fs.promises.copyFile(primaryExe, runtimeExe);
  await fs.promises.copyFile(launcherExe, primaryExe);
};
