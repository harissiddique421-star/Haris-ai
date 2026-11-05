export type MobileAction =
  | { action: 'open_app'; app: string }
  | { action: 'search_youtube' | 'search_web'; query: string }
  | { action: 'whatsapp_message'; recipient: string; message: string }
  | { action: 'call'; recipient: string }
  | { action: 'set_alarm'; hour: number; minute: number; label?: string }
  | { action: 'set_reminder'; text: string; in_minutes?: number; hour?: number; minute?: number }
  | { action: 'play_music'; query: string };
export const MOBILE_TOOL = {
  name: 'mobile_action',
  description: 'Perform an Android action requested by the user: open an installed app, search YouTube, search the web, send a WhatsApp message, place a phone call, set an alarm or reminder, or play music. WhatsApp messages and calls return needs_confirmation with a confirm_token first: read the details back, wait for a clear yes, then call again with only action and confirm_token. Never invent recipients, numbers, times or successful actions.',
  parameters: { type: 'OBJECT', properties: {
    action: { type: 'STRING', enum: ['open_app', 'search_youtube', 'search_web', 'whatsapp_message', 'call', 'set_alarm', 'set_reminder', 'play_music'] },
    app: { type: 'STRING', description: 'App name, such as WhatsApp (WP), YouTube or Chrome. Only for open_app.' },
    query: { type: 'STRING', description: 'Exact search, or song/artist/playlist for play_music.' },
    recipient: { type: 'STRING', description: 'Saved contact name as spoken, or a full international phone number with country code. For whatsapp_message and call. Contacts are resolved locally.' },
    message: { type: 'STRING', description: 'Complete WhatsApp message, exactly as the user said it. Ask for it if missing.' },
    confirm_token: { type: 'STRING', description: 'Token from a needs_confirmation result. Send it only after the user clearly said yes.' },
    hour: { type: 'INTEGER', description: '0-23, 24-hour clock. For set_alarm, or set_reminder at a clock time.' },
    minute: { type: 'INTEGER', description: '0-59.' },
    label: { type: 'STRING', description: 'Optional alarm label.' },
    text: { type: 'STRING', description: 'What to remind the user about. For set_reminder.' },
    in_minutes: { type: 'INTEGER', description: 'For set_reminder: minutes from now (1-1440). Use instead of hour/minute.' },
  }, required: ['action'] },
};
/** Exact local typed commands; Live voice uses structured Gemini function calls. */
export function parseMobileAction(input: string): MobileAction | null {
  const text = input.trim().replace(/^(?:hey\s+)?(?:friday|फ्राइडे|myraa|myra|मायरा)[,\s:]+/iu, '').replace(/^(?:please|can you|could you)\s+/i, '').trim();
  let match = /^(?:open\s+)?(?:youtube|you tube)(?:\s+and)?\s+search(?:\s+for)?\s+(.+)$/i.exec(text) || /^search(?:\s+for)?\s+(.+?)\s+on\s+youtube$/i.exec(text);
  if (match) return { action: 'search_youtube', query: match[1].trim() };
  match = /^(?:open\s+)?(?:chrome|google chrome|browser)(?:\s+and)?\s+search(?:\s+for)?\s+(.+)$/i.exec(text) || /^search(?:\s+for)?\s+(.+?)\s+(?:on|in)\s+(?:chrome|google|browser)$/i.exec(text);
  if (match) return { action: 'search_web', query: match[1].trim() };
  match = /^(?:message|msg|text)\s+(.+?)\s+(?:on|using)\s+(?:whatsapp|wp|wa)\s*:\s*(.+)$/is.exec(text) || /^(?:whatsapp|wp|wa)\s+(?:message|msg)(?:\s+to)?\s+(.+?)\s*:\s*(.+)$/is.exec(text);
  if (match) return { action: 'whatsapp_message', recipient: match[1].trim(), message: match[2].trim() };
  match = /^(?:call|phone|dial)\s+(.+?)$/i.exec(text.replace(/[.!?।]+$/u, '')) || /^(.+?)\s+ko\s+(?:call|phone)\s+(?:karo|kar do|lagao)$/i.exec(text.replace(/[.!?।]+$/u, ''));
  if (match && !/\band\b/i.test(match[1])) return { action: 'call', recipient: match[1].trim() };
  match = /^remind me in\s+(\d{1,4})\s*(?:min|mins|minute|minutes)\s+(?:to\s+)?(.+)$/i.exec(text);
  if (match) return { action: 'set_reminder', in_minutes: Number(match[1]), text: match[2].trim() };
  match = /^(?:set\s+(?:an?\s+)?)?alarm(?:\s+(?:for|at))?\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$/i.exec(text.replace(/[.!?।]+$/u, ''));
  if (match) {
    let hour = Number(match[1]); const minute = Number(match[2] || 0); const half = match[3]?.toLowerCase();
    if (half === 'pm' && hour < 12) hour += 12; if (half === 'am' && hour === 12) hour = 0;
    if (hour <= 23 && minute <= 59) return { action: 'set_alarm', hour, minute };
  }
  match = /^play\s+(.+?)(?:\s+(?:song|songs|music))?$/i.exec(text.replace(/[.!?।]+$/u, '')) || /^(.+?)\s+(?:gaana|gana|song)\s+(?:chalao|bajao|lagao)$/i.exec(text.replace(/[.!?।]+$/u, ''));
  if (match && !/\band\b/i.test(match[1])) return { action: 'play_music', query: match[1].trim() };
  const openText = text.replace(/[.!?।]+$/u, '');
  match = /^(?:open|launch)\s+([\p{L}\p{N}][\p{L}\p{N} ._-]{0,79})$/iu.exec(openText) || /^(whatsapp|wp|wa|youtube|chrome)\s+(?:kholo|khol do|open karo)$/i.exec(openText);
  if (match && !/\band\b/i.test(match[1])) return { action: 'open_app', app: match[1].trim() };
  return null;
}
