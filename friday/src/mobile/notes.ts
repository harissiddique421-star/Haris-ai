/** Second Brain notes as the Settings page shows them (the phone keeps them in its private database). */
export type Note = { id: number; kind: string; title: string; text: string; tags?: string[]; done?: boolean };

/** Settings filter: every query word must start a word in the note or its tags. Plain word matching, not meaning. */
export function noteMatches(n: Note, query: string) {
  const words = query.toLowerCase().split(/\s+/).map(w => w.replace(/^#/, '')).filter(w => w.length > 1);
  const hay = ` ${n.text} ${(n.tags || []).join(' ')}`.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ');
  return words.every(w => hay.includes(' ' + w));
}

/** Open tasks first, then ideas, decisions, plain notes, and finished tasks last. */
export function noteGroups(notes: Note[]): [string, Note[]][] {
  const groups: [string, (n: Note) => boolean][] = [
    ['Open tasks', n => n.kind === 'task' && !n.done], ['Ideas', n => n.kind === 'idea'], ['Decisions', n => n.kind === 'decision'],
    ['Notes', n => n.kind === 'note'], ['Done', n => n.kind === 'task' && !!n.done]];
  return groups.map(([h, test]) => [h, notes.filter(test)] as [string, Note[]]).filter(([, list]) => list.length > 0);
}
