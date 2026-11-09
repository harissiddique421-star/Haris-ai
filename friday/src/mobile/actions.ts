export type MobileAction =
  | { action: 'open_app'; app: string }
  | { action: 'search_youtube' | 'search_web'; query: string }
  | { action: 'whatsapp_message'; recipient: string; message: string }
  | { action: 'whatsapp_call'; recipient: string; video?: boolean }
  | { action: 'open_contact'; recipient: string }
  | { action: 'remember'; fact: string; category?: 'profile' | 'preference' | 'project'; allow_sensitive?: boolean }
  | { action: 'recall'; query: string }
  | { action: 'conversation_mode'; mode?: 'active' | 'normal' | 'quiet' | 'focus'; on?: boolean; hour?: number; minute?: number; in_minutes?: number; frequency?: 'low' | 'medium' | 'high' }
  | { action: 'forget'; query: string }
  | { action: 'list_memories' }
  | { action: 'set_persona'; persona: 'friday' | 'gf' | 'professional' | 'venom' }
  | { action: 'open_dialer' }
  | { action: 'set_driving_mode' | 'set_call_announce' | 'set_wake_word'; on: boolean }
  | { action: 'email'; recipient: string; subject?: string; message?: string }
  | { action: 'save_routine'; name: string; steps: string[] }
  | { action: 'make_document'; title: string; text: string; format?: 'pdf' | 'txt' }
  | { action: 'run_routine' | 'delete_routine'; name: string }
  | { action: 'list_routines' | 'sleep' | 'briefing' | 'list_reminders' | 'resume_video' | 'health_check' | 'network_status' | 'self_report' }
  | { action: 'action_history'; limit?: number }
  | { action: 'upcoming_events'; days?: number }
  | { action: 'set_proactive'; on: boolean }
  | { action: 'set_style'; style: 'normal' | 'calm' | 'funny' | 'serious' | 'technical' | 'friendly' | 'playful' }
  | { action: 'rename_routine'; name: string; new_name: string }
  | { action: 'clock'; command: 'stop_timer' | 'show_timers' | 'show_alarms' | 'dismiss_alarm' }
  | { action: 'copy_text'; text: string }
  | { action: 'missed_calls'; limit?: number }
  | { action: 'care_reminder'; text: string; hour: number; minute?: number; kind?: 'medicine' | 'bill' | 'general'; repeat?: 'none' | 'daily' | 'weekly' | 'monthly'; day?: number; date?: string }
  | { action: 'delete_reminder' | 'reminder_done' | 'reminder_skip'; text?: string }
  | { action: 'meeting_mode'; on: boolean; in_minutes?: number; message?: string }
  | { action: 'set_mode'; mode: 'normal' | 'translator' | 'english_practice' | 'developer'; language?: string }
  | { action: 'set_timer'; in_minutes?: number; seconds?: number; label?: string }
  | { action: 'add_event'; text: string; date?: string; hour: number; minute?: number; duration_minutes?: number }
  | { action: 'call'; recipient: string }
  | { action: 'set_alarm'; hour: number; minute: number; label?: string }
  | { action: 'set_reminder'; text: string; in_minutes?: number; hour?: number; minute?: number }
  | { action: 'play_music'; query: string }
  | { action: 'play_youtube'; query?: string; url?: string; kind?: 'video' | 'music' }
  | { action: 'media'; command: 'pause' | 'resume' | 'stop' | 'next' | 'previous' }
  | { action: 'torch'; on: boolean }
  | { action: 'volume'; stream?: 'media' | 'ring' | 'alarm'; direction?: 'up' | 'down' | 'mute' | 'unmute' | 'max'; percent?: number }
  | { action: 'ringer_mode'; mode: 'silent' | 'vibrate' | 'normal' }
  | { action: 'brightness'; direction?: 'up' | 'down' | 'max' | 'auto'; percent?: number }
  | { action: 'battery' }
  | { action: 'navigate'; place: string; mode?: 'driving' | 'walking' | 'bicycling' }
  | { action: 'read_notifications'; limit?: number }
  | { action: 'set_autonomy'; level: 'assisted' | 'balanced' | 'maximum' | 'pause' | 'resume' }
  | { action: 'voice_note'; command?: 'latest' | 'find' | 'pending' | 'clear'; recipient?: string }
  | { action: 'send_sms'; recipient: string; message: string }
  | { action: 'answer_call' | 'reject_call' | 'silence_call' | 'who_is_calling' }
  | { action: 'open_settings'; page: string; app?: string }
  | { action: 'phone_control'; control: 'back' | 'home' | 'recents' | 'last_app' | 'notifications' | 'quick_settings' | 'power_menu' | 'lock_screen' | 'screenshot' }
  | { action: 'read_screen' }
  | { action: 'tap'; target?: string; confirm_token?: string }
  | { action: 'type_text'; text: string; target?: string }
  | { action: 'scroll'; direction: 'up' | 'down' | 'left' | 'right' }
  | { action: 'prayer_times' | 'qibla'; city?: string }
  | { action: 'prayer_reminders'; on: boolean; in_minutes?: number; city?: string }
  | { action: 'emergency' }
  | { action: 'emergency_contact'; command: 'add' | 'remove' | 'list'; recipient?: string }
  | { action: 'reply_message'; message: string; recipient?: string; app?: string };
export const MOBILE_TOOL = {
  name: 'mobile_action',
  description: 'Perform an Android action requested by the user: remember a fact the user shared about themselves, their preferences or an ongoing project (remember with fact and category), look up saved memories on a topic (recall with query), forget a memory (forget), say what you remember (list_memories), change how much FRIDAY talks first (conversation_mode with mode active, normal, quiet or focus; focus with in_minutes ends by itself; frequency low, medium or high; for active a daily check-in with on and hour/minute, on false cancels it), switch persona (set_persona: friday, gf, professional, venom), turn driving mode on or off (set_driving_mode with on), turn spoken caller names on or off (set_call_announce with on), turn the wake phrase on or off (set_wake_word with on), open an email draft (email with recipient, subject, message; recipient "me" is the user), save, run, list or delete a routine of saved commands (save_routine, run_routine, list_routines, delete_routine), go to sleep and end the Live call when the user says Bye FRIDAY (sleep), give the morning briefing (briefing), list missed calls (missed_calls), set medicine, bill or repeating reminders that ask done? and follow up (care_reminder, list_reminders, delete_reminder, reminder_done when the user says they did it), meeting mode with vibrate and auto-reply (meeting_mode), translator or English practice mode (set_mode), continue the last video, Quran or story from where it stopped (resume_video), set a timer (set_timer), open a calendar event draft (add_event), play a song or video on YouTube and check it really plays (play_youtube with query or url), control playback (media: pause, resume, stop, next, previous), save a document you wrote as a PDF or text file in Downloads (make_document with title, text, format), open an installed app, search YouTube, search the web, send a WhatsApp message or SMS, start a WhatsApp voice or video call (whatsapp_call, video true for video), open a saved contact, place a phone call, answer, reject or silence a ringing call, say who is calling (who_is_calling), set an alarm or reminder, play music, torch, volume, silent/vibrate mode, brightness, battery level, navigation, read recent notifications, open a settings page or panel, or control the app in front (back, home, recents, read_screen, tap a label, type_text, scroll). For phone control, read_screen first, tap the exact label it shows, then read_screen again to check. Never just open WhatsApp when the user wants to message or call someone: use whatsapp_message or whatsapp_call. WhatsApp voice notes: set_autonomy when the user asks to pause FRIDAY, end the pause or change how independently she works ("FRIDAY pause ho jao", "pause khatam karo", "maximum mode"); voice_note (Ali ke voice note mein kya kaha, summary, time, translate, reply suggest); it only knows voice notes the user shared to FRIDAY from WhatsApp, so for not_heard tell the user how to share it and never guess what was said; to answer it use whatsapp_message as usual. WhatsApp messages and calls, SMS, phone calls and risky taps return needs_confirmation with a confirm_token first: call at once without asking beforehand, read the details back once, wait for a clear yes, then call again right away with only action and confirm_token (no further questions about the internal steps). Namaz: prayer_times (times for today and the next prayer), qibla (direction of the Kaaba), prayer_reminders (on, in_minutes before each prayer); add city when the user names one or the phone asks for it. Emergency: emergency when the user asks for help, SOS or says "FRIDAY emergency" (it asks for confirmation first, then SMSes the saved emergency contacts with the location); emergency_contact with command add, remove or list and recipient. reply_message answers a recent message notification (WhatsApp and other chats) with message and optional recipient (the sender) and app; it also needs confirmation. Never invent recipients, numbers, times or successful actions.',
  parameters: { type: 'OBJECT', properties: {
    action: { type: 'STRING', enum: ['set_autonomy', 'voice_note', 'remember', 'recall', 'conversation_mode', 'forget', 'list_memories', 'set_persona', 'set_driving_mode', 'set_call_announce', 'set_wake_word', 'email', 'save_routine', 'run_routine', 'list_routines', 'delete_routine', 'sleep', 'briefing', 'missed_calls', 'care_reminder', 'list_reminders', 'delete_reminder', 'reminder_done', 'reminder_skip', 'meeting_mode', 'health_check', 'network_status', 'self_report', 'action_history', 'upcoming_events', 'set_proactive', 'set_style', 'rename_routine', 'clock', 'copy_text', 'set_mode', 'resume_video', 'set_timer', 'add_event', 'make_document', 'open_dialer', 'open_app', 'search_youtube', 'search_web', 'whatsapp_message', 'whatsapp_call', 'open_contact', 'call', 'set_alarm', 'set_reminder', 'play_music', 'play_youtube', 'media', 'torch', 'volume', 'ringer_mode', 'brightness', 'battery', 'navigate', 'read_notifications', 'send_sms', 'answer_call', 'reject_call', 'silence_call', 'who_is_calling', 'open_settings', 'phone_control', 'read_screen', 'tap', 'type_text', 'scroll', 'prayer_times', 'qibla', 'prayer_reminders', 'emergency', 'emergency_contact', 'reply_message'] },
    app: { type: 'STRING', description: 'App name, such as WhatsApp (WP), YouTube or Chrome. For open_app, or open_settings with page app_notifications or app_info.' },
    recipient: { type: 'STRING', description: 'Saved contact name as spoken, or a full international phone number with country code. For whatsapp_message, whatsapp_call, open_contact, send_sms, call and emergency_contact; for reply_message the sender of the message to answer (omit for the newest message). Contacts and their WhatsApp accounts are resolved locally.' },
    message: { type: 'STRING', description: 'Complete WhatsApp, SMS, email or reply_message text, exactly as the user said it. Ask for it if missing. For meeting_mode: optional auto-reply text.' },
    subject: { type: 'STRING', description: 'For email: a short subject line.' },
    url: { type: 'STRING', description: 'For play_youtube: a YouTube link the user gave (watch, youtu.be, shorts or music link).' },
    kind: { type: 'STRING', enum: ['video', 'music', 'medicine', 'bill', 'general'], description: 'For play_youtube: music for songs (adds a mix so next/previous work), video otherwise. For care_reminder: medicine, bill or general.' },
    repeat: { type: 'STRING', enum: ['none', 'daily', 'weekly', 'monthly'], description: 'For care_reminder. Medicine defaults to daily, bills to monthly.' },
    day: { type: 'INTEGER', description: 'For care_reminder: with repeat monthly the day of the month (1-31); with weekly the weekday, 1 Monday to 7 Sunday. For upcoming_events use days instead.' },
    days: { type: 'INTEGER', description: 'For upcoming_events: how many days ahead (default 7).' },
    style: { type: 'STRING', enum: ['normal', 'calm', 'funny', 'serious', 'technical', 'friendly', 'playful'], description: 'For set_style: the speaking style on top of the persona.' },
    new_name: { type: 'STRING', description: 'For rename_routine: the new routine name.' },
    language: { type: 'STRING', description: 'For set_mode translator: the other person\'s language, like English, Arabic or Chinese.' },
    level: { type: 'STRING', enum: ['assisted', 'balanced', 'maximum', 'pause', 'resume'], description: 'For set_autonomy: assisted (ask before every action), balanced (default), maximum (do low-risk steps without asking), pause (FRIDAY stops all phone actions and check-ins), resume (end the pause). Only on the user\'s own request.' },
    command: { type: 'STRING', enum: ['pause', 'resume', 'stop', 'next', 'previous', 'stop_timer', 'show_timers', 'show_alarms', 'dismiss_alarm', 'add', 'remove', 'list', 'latest', 'find', 'pending', 'clear'], description: 'For voice_note: latest or find (with recipient = the sender) returns what a voice note the user shared to FRIDAY said; pending lists voice notes that only arrived as a notification; clear deletes saved voice note results. For media: pause, resume, stop, next or previous for whatever is playing. For clock: stop_timer, show_timers, show_alarms (to edit or delete alarms) or dismiss_alarm. For emergency_contact: add, remove or list.' },
    city: { type: 'STRING', description: 'For prayer_times, qibla and prayer_reminders: the city the user named, like Lahore. Omit to use the saved place or the phone location.' },
    title: { type: 'STRING', description: 'For make_document: the document title.' },
    format: { type: 'STRING', enum: ['pdf', 'txt'], description: 'For make_document. Default pdf.' },
    name: { type: 'STRING', description: 'For save_routine, run_routine and delete_routine: the routine name, like morning.' },
    steps: { type: 'ARRAY', items: { type: 'STRING' }, description: 'For save_routine: up to 10 short plain commands, in order.' },
    seconds: { type: 'INTEGER', description: 'For set_timer: extra seconds (use with or instead of in_minutes).' },
    date: { type: 'STRING', description: 'For add_event or care_reminder: YYYY-MM-DD. Omit for today/next time.' },
    duration_minutes: { type: 'INTEGER', description: 'For add_event: length in minutes, default 60.' },
    confirm_token: { type: 'STRING', description: 'Token from a needs_confirmation result. Send it only after the user clearly said yes.' },
    hour: { type: 'INTEGER', description: '0-23, 24-hour clock. For set_alarm, set_reminder at a clock time, or the conversation_mode daily check-in time.' },
    minute: { type: 'INTEGER', description: '0-59.' },
    label: { type: 'STRING', description: 'Optional alarm label.' },
    text: { type: 'STRING', description: 'For set_reminder or care_reminder: what to remind the user about (for delete_reminder and reminder_done: words from it). For add_event: the event title. For make_document: the complete document text you wrote. For type_text: the exact text to type.' },
    in_minutes: { type: 'INTEGER', description: 'For set_reminder or set_timer: minutes from now (1-1440). For set_reminder use instead of hour/minute. For meeting_mode: how long (default 60). For prayer_reminders: minutes before each prayer (0-60, default 10). For conversation_mode focus: how long focus mode lasts.' },
    fact: { type: 'STRING', description: 'For remember: one short lasting fact about the user, their preferences or an ongoing project, in their words. Never passwords, PINs, OTPs or card numbers.' },
    category: { type: 'STRING', enum: ['profile', 'preference', 'project'], description: 'For remember: profile (name, language, people, work), preference (favourites, likes, preferred style or voice) or project (project names, progress, decisions, pending tasks).' },
    allow_sensitive: { type: 'BOOLEAN', description: 'For remember: true only after a needs_consent result and the user clearly agreed to save that personal detail.' },
    query: { type: 'STRING', description: 'Exact search, or song/artist/playlist for play_music, or for forget: words from the memory to delete (or "everything"), or for recall: the topic to look up in memory.' },
    persona: { type: 'STRING', enum: ['friday', 'gf', 'professional', 'venom'], description: 'For set_persona.' },
    video: { type: 'BOOLEAN', description: 'For whatsapp_call: true for a video call, false or omitted for a voice call.' },
    on: { type: 'BOOLEAN', description: 'For prayer_reminders, set_proactive, torch, set_driving_mode, set_call_announce, set_wake_word and meeting_mode: true to turn on, false to turn off. For conversation_mode: the daily companion check-in (false cancels it).' },
    stream: { type: 'STRING', enum: ['media', 'ring', 'alarm'], description: 'For volume. Default media.' },
    direction: { type: 'STRING', enum: ['up', 'down', 'mute', 'unmute', 'max', 'auto', 'left', 'right'], description: 'For volume or brightness (auto is brightness only), or scroll (up, down, left, right).' },
    percent: { type: 'INTEGER', description: '0-100 for volume or brightness.' },
    mode: { type: 'STRING', description: 'ringer_mode: silent, vibrate or normal. navigate: driving, walking or bicycling. set_mode: normal, translator, english_practice or developer. conversation_mode: active, normal, quiet or focus.' },
    frequency: { type: 'STRING', enum: ['low', 'medium', 'high'], description: 'For conversation_mode: how often FRIDAY starts talking in active mode.' },
    place: { type: 'STRING', description: 'For navigate: destination name or address.' },
    limit: { type: 'INTEGER', description: 'For read_notifications (1-20) or missed_calls (1-15): how many.' },
    page: { type: 'STRING', description: 'For open_settings: wifi, internet, bluetooth, hotspot, airplane, location, display, sound, battery, apps, storage, date, language, nfc, settings, or app_notifications / app_info with app (that app\'s notification settings or app info).' },
    control: { type: 'STRING', enum: ['back', 'home', 'recents', 'last_app', 'notifications', 'quick_settings', 'power_menu', 'lock_screen', 'screenshot'], description: 'For phone_control.' },
    target: { type: 'STRING', description: 'For tap: the button or item label exactly as read_screen showed it. For type_text: optional label or hint of the text box.' },
  }, required: ['action'] },
};
/** Exact local typed commands; Live voice uses structured Gemini function calls. */
export function parseMobileAction(input: string): MobileAction | null {
  const text = input.trim().replace(/^(?:hey\s+)?(?:friday|फ्राइडे|myraa|myra|मायरा)[,\s:]+/iu, '').replace(/^(?:please|can you|could you)\s+/i, '').trim();
  const link = /(?:https?:\/\/)?(?:www\.|m\.|music\.)?(?:youtube\.com\/(?:watch\?(?:[^\s#]*&)?v=|shorts\/|live\/|embed\/)|youtu\.be\/)[A-Za-z0-9_-]{11}[^\s]*/i.exec(text);
  if (link && text.replace(link[0], ' ').trim().split(/\s+/).filter(Boolean).every(word => /^(?:play|open|this|ye|yeh|is|video|song|gaana|link|karo|chalao|lagao|kholo|please|on|youtube)$/i.test(word))) return { action: 'play_youtube', url: link[0] };
  const bare = text.replace(/[.!?।]+$/u, '');
  if (/^(?:health check|friday check(?: karo)?|check yourself|system check|sab theek hai\??|diagnostics?)$/i.test(bare)) return { action: 'health_check' };
  if (/^(?:self[- ]?(?:report|analysis|check[- ]?up)|(?:what|how) can you improve(?: yourself)?|improve yourself|apna (?:analysis|jaiza) (?:karo|lo)|khud ko (?:check|improve) karo)\??$/i.test(bare)) return { action: 'self_report' };
  if (/^(?:internet check(?: karo)?|check (?:the )?internet|network status|internet (?:nahi chal raha|kyun nahi chal raha|not working)|wifi check(?: karo)?)$/i.test(bare)) return { action: 'network_status' };
  if (/^(?:action history|history|recent actions|what did you do|kya kya kiya|abhi kya kiya)$/i.test(bare)) return { action: 'action_history' };
  if (/^(?:last app|previous app|go back to (?:the )?last app|last opened app(?: wapas)?(?: kholo)?|pichli app(?: kholo)?|wapas pichli app(?: par jao)?)$/i.test(bare)) return { action: 'phone_control', control: 'last_app' };
  if (/^(?:stop (?:the )?timer|timer (?:band|stop) karo)$/i.test(bare)) return { action: 'clock', command: 'stop_timer' };
  if (/^(?:show (?:my )?alarms|mere alarms(?: dikhao)?|alarms dikhao)$/i.test(bare)) return { action: 'clock', command: 'show_alarms' };
  if (/^(?:upcoming events|my events|next events|agle events|calendar (?:events|dikhao))$/i.test(bare)) return { action: 'upcoming_events' };
  if (/^(?:developer mode|dev mode)(?: on)?$/i.test(bare)) return { action: 'set_mode', mode: 'developer' };
  const styleMatch = /^(calm|funny|serious|technical|friendly|playful|normal) (?:style|mode)$/i.exec(bare);
  if (styleMatch && styleMatch[1].toLowerCase() !== 'normal') return { action: 'set_style', style: styleMatch[1].toLowerCase() as 'calm' };
  if (/^(?:namaz|namaaz|nimaz|prayer|salah|salat)(?: (?:times?|timings?|ka waqt|ke waqt|ka time|ke time|kab hai|kitne baje hai))?(?: (?:today|aaj))?\??$|^(?:aaj )?(?:namaz|namaaz|prayer) (?:times?|timings?|ka waqt|ke auqat)(?: batao)?\??$|^(?:agli|next) (?:namaz|prayer)(?: kab hai| kitne baje)?\??$/i.test(bare)) return { action: 'prayer_times' };
  if (/^(?:qibla|qiblah|kibla)(?: (?:kis taraf|kidhar|kahan|direction|ki direction|ka rukh))?(?: hai)?\??$|^(?:qibla|kaaba) direction\??$|^which way is (?:the )?qibla\??$/i.test(bare)) return { action: 'qibla' };
  if (/^(?:emergency|sos|send sos|emergency message bhejo)(?: (?:alert|message|karo|bhejo))?!?$/i.test(bare)) return { action: 'emergency' };
  if (/^(?:emergency contacts?|mere emergency contacts?)(?: (?:list|dikhao|batao|kaun hain))?\??$/i.test(bare)) return { action: 'emergency_contact', command: 'list' };
  if (/^(?:who(?:'s| is)? calling(?: me)?|what number is calling|kaun call kar raha hai|kis ki call hai|kiski call hai|call kaun kar raha hai)$/i.test(bare)) return { action: 'who_is_calling' };
  if (/^(?:silence|mute|quiet)(?: the)? (?:call|ringer|ringing|phone call)$|^(?:ringtone|call) (?:band|chup) karo$/i.test(bare)) return { action: 'silence_call' };
  if (/^(?:answer|pick up|accept)(?: the)? call$|^call (?:utha lo|uthao|receive karo)$/i.test(bare)) return { action: 'answer_call' };
  if (/^(?:reject|decline|cut)(?: the)? call$|^call (?:kaat do|kato|reject karo)$/i.test(bare)) return { action: 'reject_call' };
  if (/^(?:pause|pause (?:it|the (?:song|video|music))|ruko|ruk jao|pause karo|gaana roko)$/i.test(bare)) return { action: 'media', command: 'pause' };
  if (/^(?:resume|continue|unpause|resume (?:the )?(?:song|video|music)|play again|dobara chalao|phir se chalao)$/i.test(bare)) return { action: 'media', command: 'resume' };
  if (/^(?:stop|stop (?:the )?(?:song|video|music|playback)|music band karo|gaana band karo|band karo)$/i.test(bare)) return { action: 'media', command: 'stop' };
  if (/^(?:next|next (?:song|video|track)|skip|skip (?:this )?(?:song|video)|agla gaana|agla gana|agla song|next song chalao)$/i.test(bare)) return { action: 'media', command: 'next' };
  if (/^(?:previous|previous (?:song|video|track)|go back a song|pichla gaana|pichla gana|pichla song|last song)$/i.test(bare)) return { action: 'media', command: 'previous' };
  if (/^(?:mute|mute (?:it|the (?:sound|volume|phone))|awaz band karo)$/i.test(bare)) return { action: 'volume', direction: 'mute' };
  if (/^(?:unmute|volume unmute|unmute (?:it|the (?:sound|volume|phone))|awaz kholo|awaz wapas lao)$/i.test(bare)) return { action: 'volume', direction: 'unmute' };
  let yt = /^(?:open\s+youtube\s+and\s+)?play\s+(?:a\s+|the\s+)?(?:youtube\s+)?(.+?)\s+(?:on|in)\s+youtube$/i.exec(bare) || /^(?:open\s+youtube\s+and\s+)play\s+(?:a\s+|the\s+)?(.+)$/i.exec(bare) || /^play\s+(?:a\s+)?youtube\s+(?:song|video)\s+(.+)$/i.exec(bare) || /^(?:youtube|yt)\s+(?:par|pe|per|on)\s+(.+?)\s+(?:chalao|lagao|play karo|bajao)$/i.exec(bare);
  if (yt && !/\band\b/i.test(yt[1])) {
    const query = yt[1].replace(/\s+(?:song|gaana|gana|video)$/i, '').replace(/^(?:song|video)\s+/i, '').trim();
    const music = /\b(?:song|gaana|gana|music|track)\b/i.test(bare);
    if (query) return music ? { action: 'play_youtube', query, kind: 'music' } : { action: 'play_youtube', query };
  }
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
  if (/^(?:good morning|gm|subah ki briefing|morning briefing|briefing|aaj ka din kaisa hai|what'?s my day)$/i.test(plain)) return { action: 'briefing' };
  if (/^(?:missed calls?|kis ne call kiya(?: tha)?|kisne call kiya(?: tha)?|who called(?: me)?)$/i.test(plain)) return { action: 'missed_calls' };
  if (/^(?:my reminders|list reminders|reminders? (?:batao|dikhao)|mere reminders?)$/i.test(plain)) return { action: 'list_reminders' };
  if (/^(?:dawai le li|dawa le li|le li|i took it|bill (?:jama )?kar diya)$/i.test(plain)) return { action: 'reminder_done' };
  let meet = /^meeting mode\s+(on|off|chalu|band)(?:\s+(?:karo|kar do))?$/i.exec(plain);
  if (meet) return { action: 'meeting_mode', on: /^(?:on|chalu)$/i.test(meet[1]) };
  if (/^(?:english practice(?: mode)?|english (?:sikhao|practice karao))(?:\s+(?:on|karo|shuru karo))?$/i.test(plain)) return { action: 'set_mode', mode: 'english_practice' };
  meet = /^translator(?: mode)?(?:\s+(?:on|karo|chalu karo))?(?:\s+(?:for|in|mein|me)\s+([a-z ]{2,20}))?$/i.exec(plain);
  if (meet) return meet[1] ? { action: 'set_mode', mode: 'translator', language: meet[1].trim() } : { action: 'set_mode', mode: 'translator' };
  if (/^(?:normal mode|translator band karo|practice band karo|stop translator)$/i.test(plain)) return { action: 'set_mode', mode: 'normal' };
  const convMode = /^(active|companion|quiet|focus) mode(?:\s+(?:on|karo|kar do|lagao))?$/i.exec(plain);
  if (convMode) { const m = convMode[1].toLowerCase(); return { action: 'conversation_mode', mode: (m === 'companion' ? 'active' : m) as 'active' | 'quiet' | 'focus' }; }
  if (/^(?:wahin se chalao|wahin se (?:shuru|play) karo|jahan chhora tha wahan se chalao|resume (?:the )?(?:video|quran|story)|continue (?:the )?(?:video|quran|story))$/i.test(plain)) return { action: 'resume_video' };
  if (/^(?:bye|goodbye|good night|gn)(?:\s+(?:friday|फ्राइडे))?$|^(?:so jao|sleep|go to sleep|chup ho jao)$/i.test(plain)) return { action: 'sleep' };
  if (/^(?:list|show)?\s*(?:my\s+)?routines$|^(?:meri\s+)?routines\s+(?:dikhao|batao)$/i.test(plain)) return { action: 'list_routines' };
  match = /^(?:run|start|do)\s+(?:my\s+|the\s+)?(.+?)\s+routine$/i.exec(plain) || /^(?:meri\s+|mera\s+)?(.+?)\s+routine\s+(?:chalao|chala do|shuru karo|start karo)$/i.exec(plain);
  if (match && !/\band\b/i.test(match[1])) return { action: 'run_routine', name: match[1].trim() };
  match = /^(?:delete|remove)\s+(?:my\s+|the\s+)?(.+?)\s+routine$/i.exec(plain) || /^(.+?)\s+routine\s+(?:delete karo|hata do|mita do)$/i.exec(plain);
  if (match) return { action: 'delete_routine', name: match[1].trim() };
  match = /^(?:save|create|make)\s+(?:a\s+)?routine\s+(.{1,40}?)\s*:\s*(.+)$/is.exec(text);
  if (match) { const steps = match[2].split(/\s*(?:;|\n|\s+then\s+|\s+phir\s+)\s*/i).map(step => step.trim()).filter(Boolean); if (steps.length) return { action: 'save_routine', name: match[1].trim(), steps }; }
  match = /^(?:set\s+)?(?:a\s+)?timer\s+(?:for\s+)?(\d{1,4})\s*(min(?:ute)?s?|sec(?:ond)?s?)$/i.exec(plain) || /^(\d{1,4})\s*(min(?:ute)?s?|sec(?:ond)?s?)\s+(?:ka\s+)?timer(?:\s+(?:lagao|laga do|set karo))?$/i.exec(plain);
  if (match) { const n = Number(match[1]); return /^s/i.test(match[2]) ? { action: 'set_timer', seconds: n } : { action: 'set_timer', in_minutes: n }; }
  match = /^(?:turn\s+)?wake\s*(?:word|phrase|up word)\s+(on|off|chalu|band)(?:\s+(?:karo|kar do))?$/i.exec(plain) || /^turn\s+(on|off)\s+(?:the\s+)?wake\s*(?:word|phrase)$/i.exec(plain);
  if (match) return { action: 'set_wake_word', on: /^(?:on|chalu)$/i.test(match[1]) };
  match = /^(?:email|mail)\s+(.+?)\s*:\s*(.+)$/is.exec(text) || /^(.+?)\s+(?:ko\s+)?(?:email|mail)\s+(?:karo|kar do|bhejo|bhej do)\s*:\s*(.+)$/is.exec(text);
  if (match && !/\band\b/i.test(match[1])) return { action: 'email', recipient: match[1].trim(), message: match[2].trim() };
  match = /^(?:turn\s+)?driving\s+mode\s+(on|off|chalu|band|start|stop)(?:\s+(?:karo|kar do))?$/i.exec(plain) || /^turn\s+(on|off)\s+driving\s+mode$/i.exec(plain) || /^(?:i am|i'm|main)\s+(driving)(?:\s+(?:now|kar raha hoon|kar rahi hoon|kar raha hun|kar rahi hun))?$/i.exec(plain);
  if (match) return { action: 'set_driving_mode', on: /^(?:on|chalu|start|driving)$/i.test(match[1]) };
  match = /^(?:turn\s+)?(?:call|caller)\s+(?:announce(?:ment)?s?|name)\s+(on|off|chalu|band)(?:\s+(?:karo|kar do))?$/i.exec(plain) || /^turn\s+(on|off)\s+call\s+announce(?:ment)?s?$/i.exec(plain);
  if (match) return { action: 'set_call_announce', on: /^(?:on|chalu)$/i.test(match[1]) };
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
