export type MobileAction =
  | { action: 'open_app'; app: string }
  | { action: 'search_youtube' | 'search_web'; query: string }
  | { action: 'whatsapp_message'; recipient: string; message: string }
  | { action: 'whatsapp_call'; recipient: string; video?: boolean }
  | { action: 'open_contact'; recipient: string }
  | { action: 'remember'; fact: string }
  | { action: 'forget'; query: string }
  | { action: 'list_memories' }
  | { action: 'set_persona'; persona: 'friday' | 'gf' | 'professional' | 'venom' }
  | { action: 'open_dialer' }
  | { action: 'call'; recipient: string }
  | { action: 'set_alarm'; hour: number; minute: number; label?: string }
  | { action: 'set_reminder'; text: string; in_minutes?: number; hour?: number; minute?: number }
  | { action: 'play_music'; query: string }
  | { action: 'torch'; on: boolean }
  | { action: 'volume'; stream?: 'media' | 'ring' | 'alarm'; direction?: 'up' | 'down' | 'mute' | 'max'; percent?: number }
  | { action: 'ringer_mode'; mode: 'silent' | 'vibrate' | 'normal' }
  | { action: 'brightness'; direction?: 'up' | 'down' | 'max' | 'auto'; percent?: number }
  | { action: 'battery' }
  | { action: 'navigate'; place: string; mode?: 'driving' | 'walking' | 'bicycling' }
  | { action: 'read_notifications'; limit?: number }
  | { action: 'send_sms'; recipient: string; message: string }
  | { action: 'answer_call' | 'reject_call' }
  | { action: 'open_settings'; page: string }
  | { action: 'phone_control'; control: 'back' | 'home' | 'recents' | 'notifications' | 'quick_settings' | 'power_menu' | 'lock_screen' | 'screenshot' }
  | { action: 'read_screen' }
  | { action: 'tap'; target?: string; confirm_token?: string }
  | { action: 'type_text'; text: string; target?: string }
  | { action: 'scroll'; direction: 'up' | 'down' | 'left' | 'right' };
export const MOBILE_TOOL = {
  name: 'mobile_action',
  description: 'Perform an Android action requested by the user: remember a fact about the user (remember), forget it (forget), say what you remember (list_memories), switch persona (set_persona: friday, gf, professional, venom), open an installed app, search YouTube, search the web, send a WhatsApp message or SMS, start a WhatsApp voice or video call (whatsapp_call, video true for video), open a saved contact, place a phone call, answer or reject a ringing call, set an alarm or reminder, play music, torch, volume, silent/vibrate mode, brightness, battery level, navigation, read recent notifications, open a settings page or panel, or control the app in front (back, home, recents, read_screen, tap a label, type_text, scroll). For phone control, read_screen first, tap the exact label it shows, then read_screen again to check. Never just open WhatsApp when the user wants to message or call someone: use whatsapp_message or whatsapp_call. WhatsApp messages and calls, SMS, phone calls and risky taps return needs_confirmation with a confirm_token first: read the details back, wait for a clear yes, then call again with only action and confirm_token. Never invent recipients, numbers, times or successful actions.',
  parameters: { type: 'OBJECT', properties: {
    action: { type: 'STRING', enum: ['remember', 'forget', 'list_memories', 'set_persona', 'open_dialer', 'open_app', 'search_youtube', 'search_web', 'whatsapp_message', 'whatsapp_call', 'open_contact', 'call', 'set_alarm', 'set_reminder', 'play_music', 'torch', 'volume', 'ringer_mode', 'brightness', 'battery', 'navigate', 'read_notifications', 'send_sms', 'answer_call', 'reject_call', 'open_settings', 'phone_control', 'read_screen', 'tap', 'type_text', 'scroll'] },
    app: { type: 'STRING', description: 'App name, such as WhatsApp (WP), YouTube or Chrome. Only for open_app.' },
    recipient: { type: 'STRING', description: 'Saved contact name as spoken, or a full international phone number with country code. For whatsapp_message, whatsapp_call, open_contact, send_sms and call. Contacts and their WhatsApp accounts are resolved locally.' },
    message: { type: 'STRING', description: 'Complete WhatsApp or SMS message, exactly as the user said it. Ask for it if missing.' },
    confirm_token: { type: 'STRING', description: 'Token from a needs_confirmation result. Send it only after the user clearly said yes.' },
    hour: { type: 'INTEGER', description: '0-23, 24-hour clock. For set_alarm, or set_reminder at a clock time.' },
    minute: { type: 'INTEGER', description: '0-59.' },
    label: { type: 'STRING', description: 'Optional alarm label.' },
    text: { type: 'STRING', description: 'For set_reminder: what to remind the user about. For type_text: the exact text to type.' },
    in_minutes: { type: 'INTEGER', description: 'For set_reminder: minutes from now (1-1440). Use instead of hour/minute.' },
    fact: { type: 'STRING', description: 'For remember: one short fact about the user, in their words. Never passwords, PINs, OTPs or card numbers.' },
    query: { type: 'STRING', description: 'Exact search, or song/artist/playlist for play_music, or for forget: words from the memory to delete (or "everything").' },
    persona: { type: 'STRING', enum: ['friday', 'gf', 'professional', 'venom'], description: 'For set_persona.' },
    video: { type: 'BOOLEAN', description: 'For whatsapp_call: true for a video call, false or omitted for a voice call.' },
    on: { type: 'BOOLEAN', description: 'For torch: true to turn on, false to turn off.' },
    stream: { type: 'STRING', enum: ['media', 'ring', 'alarm'], description: 'For volume. Default media.' },
    direction: { type: 'STRING', enum: ['up', 'down', 'mute', 'max', 'auto', 'left', 'right'], description: 'For volume or brightness (auto is brightness only), or scroll (up, down, left, right).' },
    percent: { type: 'INTEGER', description: '0-100 for volume or brightness.' },
    mode: { type: 'STRING', description: 'ringer_mode: silent, vibrate or normal. navigate: driving, walking or bicycling.' },
    place: { type: 'STRING', description: 'For navigate: destination name or address.' },
    limit: { type: 'INTEGER', description: 'For read_notifications: how many (1-20).' },
    page: { type: 'STRING', description: 'For open_settings: wifi, internet, bluetooth, hotspot, airplane, location, display, sound, battery, apps, storage, date, language, nfc or settings.' },
    control: { type: 'STRING', enum: ['back', 'home', 'recents', 'notifications', 'quick_settings', 'power_menu', 'lock_screen', 'screenshot'], description: 'For phone_control.' },
    target: { type: 'STRING', description: 'For tap: the button or item label exactly as read_screen showed it. For type_text: optional label or hint of the text box.' },
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
  match = /^(?:whatsapp|wp|wa)\s+(?:par|pe|per)\s+(.+?)\s+ko\s+(?:message|msg)\s+(?:karo|kar do|bhejo|bhej do)\s*:\s*(.+)$/is.exec(text) || /^(.+?)\s+ko\s+(?:whatsapp|wp|wa)(?:\s+(?:par|pe))?\s+(?:message|msg)\s+(?:karo|kar do|bhejo|bhej do)\s*:\s*(.+)$/is.exec(text);
  if (match) return { action: 'whatsapp_message', recipient: match[1].trim(), message: match[2].trim() };
  match = /^(?:remember(?: that)?|yaad rakho(?: ki)?|yaad rakhna(?: ki)?)\s*:?\s+(.{2,300})$/is.exec(text);
  if (match) return { action: 'remember', fact: match[1].trim() };
  const plain = text.replace(/[.!?।]+$/u, '');
  if (/^(?:what do you remember(?: about me)?|tumhe kya yaad hai|kya yaad hai)$/i.test(plain)) return { action: 'list_memories' };
  match = /^(?:forget|bhool jao|bhul jao)\s+(.{2,100})$/i.exec(plain);
  if (match) return { action: 'forget', query: match[1].trim() };
  match = /^(?:switch to|use|change to)\s+(friday|gf|girlfriend|professional|venom)(?:\s+(?:mode|persona|voice))?$/i.exec(plain) || /^(friday|gf|girlfriend|professional|venom)\s+mode(?:\s+(?:on|karo|kar do|lagao))?$/i.exec(plain);
  if (match) { const p = match[1].toLowerCase(); return { action: 'set_persona', persona: p === 'girlfriend' ? 'gf' : p as 'gf' }; }
  match = /^(.+?)\s+ko\s+(?:whatsapp|wp|wa)(?:\s+(?:par|pe))?\s+(video\s+)?call\s+(?:karo|kar do|lagao|milao)$/i.exec(plain) || /^(?:whatsapp|wp|wa)\s+(?:par|pe)\s+(.+?)\s+ko\s+(video\s+)?call\s+(?:karo|kar do|lagao|milao)$/i.exec(plain);
  if (match && !/\band\b/i.test(match[1])) return match[2] ? { action: 'whatsapp_call', recipient: match[1].trim(), video: true } : { action: 'whatsapp_call', recipient: match[1].trim() };
  match = /^(?:whatsapp|wp|wa)\s+(video\s+)?call\s+(?:to\s+)?(.+)$/i.exec(plain);
  if (match && !/\band\b/i.test(match[2])) return match[1] ? { action: 'whatsapp_call', recipient: match[2].trim(), video: true } : { action: 'whatsapp_call', recipient: match[2].trim() };
  match = /^(?:go|press|tap)\s+(back|home)$/i.exec(plain) || /^(back|home)\s+(?:jao|karo|dabao)$/i.exec(plain);
  if (match) return { action: 'phone_control', control: match[1].toLowerCase() as 'back' };
  match = /^open\s+(?:the\s+)?contact\s+(?:of\s+|for\s+)?(.+)$/i.exec(plain) || /^(.+?)\s+ka\s+contact\s+(?:kholo|khol do|dikhao)$/i.exec(plain);
  if (match && !/\band\b/i.test(match[1])) return { action: 'open_contact', recipient: match[1].trim() };
  match = /^(?:turn\s+)?(?:the\s+)?(?:torch|flashlight|flash light)\s+(on|off)$/i.exec(plain) || /^turn\s+(on|off)\s+(?:the\s+)?(?:torch|flashlight)$/i.exec(plain) || /^torch\s+(on|off)\s+(?:karo|kar do)$/i.exec(plain);
  if (match) return { action: 'torch', on: match[1].toLowerCase() === 'on' };
  match = /^(?:volume)\s+(up|down|max|mute)$/i.exec(plain) || /^(?:set\s+)?volume\s+(?:to\s+)?(\d{1,3})\s*%?$/i.exec(plain);
  if (match) return /^\d+$/.test(match[1]) ? (Number(match[1]) <= 100 ? { action: 'volume', percent: Number(match[1]) } : null) : { action: 'volume', direction: match[1].toLowerCase() as 'up' };
  match = /^(?:put\s+(?:the\s+)?phone\s+on\s+|set\s+(?:the\s+)?phone\s+to\s+)?(silent|vibrate|normal)(?:\s+mode)?(?:\s+(?:karo|kar do))?$/i.exec(plain);
  if (match) return { action: 'ringer_mode', mode: match[1].toLowerCase() as 'silent' };
  match = /^brightness\s+(up|down|max|auto)$/i.exec(plain) || /^(?:set\s+)?brightness\s+(?:to\s+)?(\d{1,3})\s*%?$/i.exec(plain);
  if (match) return /^\d+$/.test(match[1]) ? (Number(match[1]) <= 100 ? { action: 'brightness', percent: Number(match[1]) } : null) : { action: 'brightness', direction: match[1].toLowerCase() as 'up' };
  if (/^(?:battery|battery level|how much battery(?: is left)?|battery kitni hai)$/i.test(plain)) return { action: 'battery' };
  if (/^(?:read|show)\s+(?:my\s+)?notifications$/i.test(plain)) return { action: 'read_notifications' };
  match = /^(?:navigate|directions)\s+to\s+(.+)$/i.exec(plain);
  if (match) return { action: 'navigate', place: match[1].trim() };
  match = /^(?:sms|send\s+(?:an?\s+)?sms\s+to|text)\s+(.+?)\s*:\s*(.+)$/is.exec(text);
  if (match && /^(?:sms|send)/i.test(text)) return { action: 'send_sms', recipient: match[1].trim(), message: match[2].trim() };
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
  match = /^(?:open\s+)?(wi-?fi|wifi|internet|mobile data|bluetooth|hotspot|airplane mode|flight mode|location|gps|display|sound|battery saver|nfc)\s+settings$/i.exec(plain) || /^(wi-?fi|bluetooth|hotspot|location)\s+(?:settings\s+)?(?:kholo|khol do|open karo)$/i.exec(plain);
  if (match) return { action: 'open_settings', page: match[1].toLowerCase().replace(/-/g, '').replace(/ /g, '_') };
  if (/^(?:take\s+(?:a\s+)?)?screenshot(?:\s+(?:lo|le lo|karo))?$/i.test(plain)) return { action: 'phone_control', control: 'screenshot' };
  if (/^(?:lock(?:\s+the)?\s+(?:phone|screen)|(?:phone|screen)\s+lock\s+(?:karo|kar do))$/i.test(plain)) return { action: 'phone_control', control: 'lock_screen' };
  const openText = text.replace(/[.!?।]+$/u, '');
  match = /^(?:open|launch)\s+([\p{L}\p{N}][\p{L}\p{N} ._-]{0,79})$/iu.exec(openText) || /^(whatsapp|wp|wa|youtube|chrome)\s+(?:kholo|khol do|open karo)$/i.exec(openText);
  if (match && !/\band\b/i.test(match[1])) return { action: 'open_app', app: match[1].trim() };
  return null;
}
