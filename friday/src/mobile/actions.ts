export type MobileAction = { action: 'open_app'; app: string } | { action: 'search_youtube' | 'search_web'; query: string } | { action: 'whatsapp_message'; recipient: string; message: string };
export const MOBILE_TOOL = {
  name: 'mobile_action',
  description: 'Perform an Android action requested by the user: open an installed app, search YouTube, search the web in Chrome/browser, or open a WhatsApp message draft for a saved contact or international number. WhatsApp drafts are NOT sent; the user taps Send in WhatsApp. Never invent recipients, numbers or successful actions.',
  parameters: { type: 'OBJECT', properties: {
    action: { type: 'STRING', enum: ['open_app', 'search_youtube', 'search_web', 'whatsapp_message'] },
    app: { type: 'STRING', description: 'App name, such as WhatsApp (WP), YouTube or Chrome. Only for open_app.' },
    query: { type: 'STRING', description: 'The exact search requested by the user. Required for search actions.' },
    recipient: { type: 'STRING', description: 'Saved contact name as spoken, or a full international phone number with country code. Contacts are resolved locally.' },
    message: { type: 'STRING', description: 'Complete message the user requested. Ask for it if missing.' },
  }, required: ['action'] },
};
/** Exact local typed commands; Live voice uses structured Gemini function calls. */
export function parseMobileAction(input: string): MobileAction | null {
  const text = input.trim().replace(/^(?:hey\s+)?(?:myraa|myra|मायरा)[,\s:]+/iu, '').replace(/^(?:please|can you|could you)\s+/i, '').trim();
  let match = /^(?:open\s+)?(?:youtube|you tube)(?:\s+and)?\s+search(?:\s+for)?\s+(.+)$/i.exec(text) || /^search(?:\s+for)?\s+(.+?)\s+on\s+youtube$/i.exec(text);
  if (match) return { action: 'search_youtube', query: match[1].trim() };
  match = /^(?:open\s+)?(?:chrome|google chrome|browser)(?:\s+and)?\s+search(?:\s+for)?\s+(.+)$/i.exec(text) || /^search(?:\s+for)?\s+(.+?)\s+(?:on|in)\s+(?:chrome|google|browser)$/i.exec(text);
  if (match) return { action: 'search_web', query: match[1].trim() };
  match = /^(?:message|msg|text)\s+(.+?)\s+(?:on|using)\s+(?:whatsapp|wp|wa)\s*:\s*(.+)$/is.exec(text) || /^(?:whatsapp|wp|wa)\s+(?:message|msg)(?:\s+to)?\s+(.+?)\s*:\s*(.+)$/is.exec(text);
  if (match) return { action: 'whatsapp_message', recipient: match[1].trim(), message: match[2].trim() };
  const openText = text.replace(/[.!?।]+$/u, '');
  match = /^(?:open|launch)\s+([\p{L}\p{N}][\p{L}\p{N} ._-]{0,79})$/iu.exec(openText) || /^(whatsapp|wp|wa|youtube|chrome)\s+(?:kholo|khol do|open karo)$/i.exec(openText);
  if (match && !/\band\b/i.test(match[1])) return { action: 'open_app', app: match[1].trim() };
  return null;
}
