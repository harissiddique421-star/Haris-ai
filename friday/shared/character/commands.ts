/** Exact local avatar intents; unrelated prose never triggers a device action. */
export type CharacterCommand = { action: 'wave' | 'dance' | 'sit' | 'stand' | 'come_here' | 'follow_cursor' | 'next_character'; side?: 'left' | 'right' };
export function parseCharacterCommand(input: string): CharacterCommand | null {
  const text = input.toLowerCase().trim().replace(/^(?:hey\s+)?(?:myraa|myra|मायरा)[,\s:]+/u, '').replace(/[.!?।]+$/u, '').replace(/^please\s+/, '').replace(/\s+/g, ' ');
  if (/^(wave(?: at me)?|haath hilao|hath hilao|हाथ हिलाओ)$/u.test(text)) return { action: 'wave' };
  if (/^(dance(?: for me)?|naacho|nacho|नाचो)$/u.test(text)) return { action: 'dance' };
  if (/^(stand(?: up)?|khadi ho jao|khade ho jao|खड़ी हो जाओ|खड़े हो जाओ)$/u.test(text)) return { action: 'stand' };
  if (/^(come here|idhar aao|इधर आओ)$/u.test(text)) return { action: 'come_here' };
  if (/^(look at my cursor|follow my cursor|cursor ko dekho|कर्सर को देखो)$/u.test(text)) return { action: 'follow_cursor' };
  if (/^(switch to another character|next character|change character|dusra character|दूसरा किरदार)$/u.test(text)) return { action: 'next_character' };
  if (/^(sit(?: down)?|baitho|baith jao|बैठो|बैठ जाओ)$/u.test(text)) return { action: 'sit' };
  const sit = /^sit on the (left|right) side of (?:my|the) desktop$/u.exec(text);
  return sit ? { action: 'sit', side: sit[1] as 'left' | 'right' } : null;
}
