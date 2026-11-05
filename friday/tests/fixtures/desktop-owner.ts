/** Test-owned backend for abrupt-parent cleanup verification. No PC actions. */
import path from 'node:path';
import { ManagedDesktopAgent, verifiedFrozenAgent } from '../../agent/desktopTransport';
const frozen = process.env.MYRAA_QA_AGENT_MODE === 'frozen';
const client = new ManagedDesktopAgent({ launch: () => frozen
  ? verifiedFrozenAgent(path.resolve(process.env.MYRAA_QA_AGENT_EXE || 'agent_dist/myraa-agent/myraa-agent.exe'))
  : { command: process.env.MYRAA_PYTHON || 'python', args: ['-m', 'desktop_agent.main'], cwd: process.cwd() } });
if (!await client.ensure()) { client.dispose(); process.exit(1); }
process.send?.({ endpoint: client.endpoint, pid: client.ownedPid });
// Deliberately no exit cleanup: the test terminates this exact process and
// verifies the real Python worker's owner watchdog, independently of dispose.
setInterval(() => {}, 1000);
