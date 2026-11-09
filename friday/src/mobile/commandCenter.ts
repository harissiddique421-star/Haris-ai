/**
 * FRIDAY Command Center: one view of every module's health, the switches that turn whole modules
 * off, and recent activity. It only reads the phone's state: it never changes a setting by itself.
 */
import type { AgentName, AgentStats } from './agents';

/** Modules the owner can switch off. Health (status, autonomy, pause) always stays on, so FRIDAY can always be resumed. */
export const MODULES: { agent: AgentName; label: string; covers: string }[] = [
  { agent: 'communication', label: 'Messages & calls', covers: 'WhatsApp, calls, SMS, email, notifications, SOS, voice notes' },
  { agent: 'media', label: 'Media', covers: 'YouTube, music, playback' },
  { agent: 'device', label: 'Phone controls', covers: 'Torch, volume, brightness, alarms, apps, maps' },
  { agent: 'accessibility', label: 'Screen control', covers: 'Reading the screen, tapping, typing, scrolling' },
  { agent: 'memory', label: 'Memory & routines', covers: 'Remember, recall, persona, routines' },
  { agent: 'document', label: 'Documents', covers: 'Making documents' },
  { agent: 'research', label: 'Web search', covers: 'Searching the web' },
  { agent: 'core', label: 'Reminders & calendar', covers: 'Reminders, calendar, briefing, prayer times' },
];
export function moduleLabel(agent: AgentName) { return MODULES.find(m => m.agent === agent)?.label ?? agent; }
export function normalizeOff(raw: unknown): AgentName[] {
  const known = new Set(MODULES.map(m => m.agent));
  return Array.isArray(raw) ? [...new Set(raw.filter((a): a is AgentName => typeof a === 'string' && known.has(a as AgentName)))] : [];
}

export type Health = 'ok' | 'warn' | 'off';
export interface HealthRow { module: string; health: Health; detail: string; }

/** Health of each part, from the phone's state and this session's agent measurements. Missing data is reported as unknown, never as fine. */
export function moduleHealth(state: any, stats: Map<AgentName, AgentStats> = new Map()): HealthRow[] {
  const s = state || {}, rows: HealthRow[] = [];
  const add = (module: string, health: Health, detail: string) => rows.push({ module, health, detail });
  const a = s.autonomy || {};
  add('Autonomy', a.paused ? 'warn' : 'ok', a.paused ? 'Paused: no phone actions or check-ins' : `${a.level === 'assisted' ? 'Assisted' : a.level === 'maximum' ? 'Maximum' : 'Balanced'} mode`);
  add('Gemini', !s.keyConfigured ? 'off' : s.providerErrorCode ? 'warn' : s.keyVerified ? 'ok' : 'warn',
    !s.keyConfigured ? 'No key saved' : s.providerErrorCode ? `Last error: ${s.providerErrorCode}` : s.keyVerified ? `Key accepted for ${s.model || 'the text model'}` : 'Key saved, not checked for this model');
  const p = s.providers || {};
  add('Second provider', !p.kind || p.kind === 'none' ? 'off' : p.ready ? 'ok' : 'warn',
    !p.kind || p.kind === 'none' ? 'Not set up' : !p.ready ? 'Key or model missing' : `${p.kind === 'openrouter' ? 'OpenRouter' : 'Custom'} · ${p.model} · ${p.use === 'primary' ? 'main' : p.use === 'backup' ? 'backup' : 'off'}`);
  const v = s.voiceId || {};
  add('Voice ID', v.unreadable ? 'warn' : !v.registered ? 'off' : v.on ? 'ok' : 'warn',
    v.unreadable ? 'Profile unreadable: register again' : !v.registered ? 'Not registered' : v.on ? 'Only your voice in Live calls' : 'Registered, switched off');
  const w = s.wake || {};
  add('Wake word', !w.on ? 'off' : w.running ? 'ok' : 'warn', !w.on ? 'Off' : w.running ? 'Listening' : `On but not running${w.status ? ` (${w.status})` : ''}`);
  add('Notifications', s.notificationAccess ? 'ok' : 'off', s.notificationAccess ? 'Access allowed' : 'No notification access: callers, messages and voice notes are not seen');
  add('Screen control', s.phoneControl ? 'ok' : 'off', s.phoneControl ? 'Accessibility on' : 'Accessibility off: tapping, typing and WhatsApp Send do not work');
  add('Floating controls', s.floatingControlsAllowed ? 'ok' : 'off', s.floatingControlsAllowed ? 'Allowed (voice works over other apps)' : 'Not allowed');
  const off = new Set(normalizeOff(a.modulesOff));
  for (const m of MODULES) {
    const st = stats.get(m.agent);
    if (off.has(m.agent)) { add(m.label, 'off', 'Switched off in the Command Center'); continue; }
    if (!st) { add(m.label, 'ok', 'On · no actions yet this session'); continue; }
    const rate = st.failures / Math.max(1, st.runs);
    add(m.label, st.runs >= 3 && rate >= 0.5 ? 'warn' : 'ok', `${st.runs} run${st.runs === 1 ? '' : 's'}, ${st.failures} failed, avg ${Math.round(st.totalMs / Math.max(1, st.runs))} ms`);
  }
  return rows;
}

/** One line for the header: how many parts need attention. */
export function healthSummary(rows: HealthRow[]) {
  const warn = rows.filter(r => r.health === 'warn').length;
  return warn ? `${warn} part${warn === 1 ? '' : 's'} need attention` : 'Everything that is switched on looks healthy';
}
