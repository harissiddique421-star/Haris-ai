/**
 * What each Gemini Live voice sounds like, so the voice list can say "Aoede, female, breezy" instead
 * of a bare name. The style words are the ones Google publishes for its prebuilt voices; female/male
 * is how the voice is commonly heard, not a property Google lists. Voices missing here still show,
 * under "Other".
 */
export type VoiceGroup = 'female' | 'male' | 'other';

const INFO: Record<string, [VoiceGroup, string]> = {
  Aoede: ['female', 'breezy'], Kore: ['female', 'firm'], Leda: ['female', 'youthful'], Zephyr: ['female', 'bright'],
  Laomedeia: ['female', 'upbeat'], Despina: ['female', 'smooth'], Erinome: ['female', 'clear'], Callirrhoe: ['female', 'easy-going'],
  Autonoe: ['female', 'bright'], Gacrux: ['female', 'mature'], Vindemiatrix: ['female', 'gentle'], Sulafat: ['female', 'warm'],
  Achernar: ['female', 'soft'], Pulcherrima: ['female', 'forward'],
  Puck: ['male', 'upbeat'], Charon: ['male', 'informative'], Fenrir: ['male', 'excitable'], Orus: ['male', 'firm'],
  Iapetus: ['male', 'clear'], Umbriel: ['male', 'easy-going'], Algieba: ['male', 'smooth'], Algenib: ['male', 'gravelly'],
  Rasalgethi: ['male', 'informative'], Alnilam: ['male', 'firm'], Schedar: ['male', 'even'], Achird: ['male', 'friendly'],
  Zubenelgenubi: ['male', 'casual'], Sadachbia: ['male', 'lively'], Sadaltager: ['male', 'knowledgeable'], Enceladus: ['male', 'breathy'],
};

export function voiceInfo(name: string): { group: VoiceGroup; style: string } {
  const i = INFO[name];
  return i ? { group: i[0], style: i[1] } : { group: 'other', style: '' };
}

export function voiceLabel(name: string): string {
  const { group, style } = voiceInfo(name);
  return group === 'other' ? name : `${name}, ${group}, ${style}`;
}

/** The voices split into Female, Male and Other, each keeping the order it was given in. */
export function voiceGroups(names: string[]): { group: VoiceGroup; title: string; voices: string[] }[] {
  const titles: Record<VoiceGroup, string> = { female: 'Female voices', male: 'Male voices', other: 'Other voices' };
  return (['female', 'male', 'other'] as VoiceGroup[])
    .map(group => ({ group, title: titles[group], voices: names.filter(n => voiceInfo(n).group === group) }))
    .filter(g => g.voices.length > 0);
}
