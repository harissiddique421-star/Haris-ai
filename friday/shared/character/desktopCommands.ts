import { parseCharacterCommand, type CharacterCommand } from './commands';

export type DesktopCharacterCommand = CharacterCommand | { action: 'move_left' | 'move_right' | 'stop'; side?: never };

/** Exact desktop-only intents; prose and compound device requests stay untouched. */
export function parseDesktopCharacterCommand(input: string): DesktopCharacterCommand | null {
  const common = parseCharacterCommand(input);
  if (common) return common;
  const text = input.toLowerCase().trim().replace(/^(?:hey\s+)?(?:myraa|myra|मायरा)[,\s:]+/u, '').replace(/[.!?।]+$/u, '').replace(/^please\s+/, '').replace(/\s+/g, ' ');
  const movement = /^(?:move|walk)(?: a little)? (left|right)$/u.exec(text);
  if (movement) return { action: movement[1] === 'left' ? 'move_left' : 'move_right' };
  return /^(?:stop(?: moving)?|ruk jao|रुको)$/u.test(text) ? { action: 'stop' } : null;
}
