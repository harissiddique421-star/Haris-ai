/** The legacy voice executor must obey the same policy as planned PC tasks. */
import type { PermissionEngine } from './engine';
import type { ConfirmationBroker } from './confirmations';
import { CAPABILITY_INFO, type Capability, type PermissionContext } from './types';

export function legacyCapability(tool: string): Capability {
  if (['openApplication', 'openFolder', 'selectFiles'].includes(tool)) return 'LAUNCH_APP';
  if (/^(readFile|listFiles|searchFiles)$/.test(tool)) return 'READ_FILE';
  if (tool === 'deleteFile') return 'DELETE_FILE';
  if (/^(createFile|renameFile|moveFile|createPythonFile|createProjectFolder|writeCodeFile)$/.test(tool)) return 'WRITE_FILE';
  if (tool === 'runPythonScript') return 'RUN_COMMAND';
  if (/^(requestPowerAction|executePowerAction)$/.test(tool)) return 'POWER_CONTROL';
  if (/^(enableAutoStart|disableAutoStart|brightnessUp|brightnessDown|setBrightness|volumeUp|volumeDown|muteToggle|setVolume)$/.test(tool)) return 'MODIFY_SYSTEM_SETTINGS';
  if (/^(takeScreenshot|saveScreenshot|analyzeScreenshot|readScreen|viewScreen|observeDesktopState|locateText|getActiveWindow|listVisibleWindows|waitForUi)$/.test(tool)) return 'SCREEN_CAPTURE';
  if (/^(openWebsite|searchWeb|searchYouTube|searchGoogle|searchGitHub)$/.test(tool)) return 'BROWSE_WEB';
  if (/clipboard|copySelected/i.test(tool)) return 'CLIPBOARD';
  if (/^(systemInfo|gpuInfo|temperatureInfo|getAutoStartStatus|getCursorPosition)$/.test(tool)) return 'READ_FILE';
  return 'CONTROL_INPUT';
}

export async function authorizeLegacyTool(permissions: PermissionEngine, broker: ConfirmationBroker,
  tool: string, args: Record<string, unknown>, signal: AbortSignal): Promise<void> {
  if (signal.aborted) throw new Error('Action cancelled.');
  const capability = legacyCapability(tool);
  const opensApp = ['openApplication', 'openFolder', 'selectFiles', 'openWebsite', 'searchWeb', 'searchYouTube', 'searchGoogle', 'searchGitHub'].includes(tool);
  const context: PermissionContext = {
    ...(opensApp ? { application: String(args.name || args.path || args.url || args.query || tool) } : {}),
    ...(typeof args.path === 'string' ? { path: args.path } : {}),
    ...(typeof args.command === 'string' ? { command: args.command } : {}),
    ...(args.permanent === true ? { permanent: true } : {}),
  };
  if (typeof args.url === 'string') { try { context.domain = new URL(args.url).hostname; } catch { throw new Error('Invalid URL.'); } }
  for (const required of opensApp && capability !== 'LAUNCH_APP' ? [capability, 'LAUNCH_APP' as const] : [capability]) {
  const verdict = permissions.check(required, context);
  if (verdict.decision === 'deny') throw new Error(`Permission denied: ${CAPABILITY_INFO[required].label}.`);
  if (verdict.decision === 'ask') {
    const { answer } = broker.request({ taskId: null, capability: required, title: CAPABILITY_INFO[required].label,
      description: `${tool}: ${context.application || context.path || context.domain || CAPABILITY_INFO[required].label}`,
      details: { tool, ...context }, allowRemember: !verdict.locked && !context.permanent,
    }, { signal, timeoutMs: 180_000 });
    const result = await answer;
    if (!result.approved || signal.aborted) throw new Error('Action was not approved.');
    if (result.remember === 'session') permissions.grantForSession(required, context);
  }
  if (signal.aborted || permissions.check(required, context).decision === 'deny') throw new Error('Action cancelled or permission revoked.');
  }
}
