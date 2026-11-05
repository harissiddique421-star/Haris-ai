import path from 'node:path';
import fs from 'node:fs/promises';
import { readJsonFile, writeJsonFile } from '../shared/jsonFile';
import { MYRAA_PERSONALITY, type ConversationTurn } from '../shared/conversation';
import type { GenerateRequest, GenerateResponse } from '../models/types';

/** Independent of Live/STT/TTS quota. A failed turn never overwrites history. */
export class TextConversation {
  private turns: ConversationTurn[] = [];
  private active: AbortController | null = null;
  private readonly file: string;
  constructor(dataDir: string, private readonly generate: (request: GenerateRequest, signal: AbortSignal) => Promise<GenerateResponse>,
    private readonly startTask: (goal: string) => string, private readonly recall: (query: string) => Promise<string[]> = async () => []) {
    this.file = path.join(dataDir, 'memory', 'text-chat.v2.json');
  }
  async initialize(): Promise<void> {
    const stored = await readJsonFile<ConversationTurn[]>(this.file, []);
    this.turns = Array.isArray(stored) ? stored.filter(t => t && ['user', 'model'].includes(t.role) && typeof t.text === 'string') : [];
  }
  history(): ConversationTurn[] { return structuredClone(this.turns); }
  cancel(): void { this.active?.abort(); }
  async clear(): Promise<void> {
    if (this.active) throw new Error('Stop the current reply before clearing history.');
    await writeJsonFile(`${this.file}.backup-${Date.now()}`, this.turns);
    await writeJsonFile(this.file, []);
    this.turns = [];
  }
  async send(input: string, outerSignal?: AbortSignal): Promise<{ text: string; modelId: string; taskId?: string }> {
    const text = input.trim();
    if (!text || text.length > 8000) throw new Error('Enter a message between 1 and 8000 characters.');
    if (this.active) throw new Error('A reply is already in progress. Stop it or wait.');
    const controller = new AbortController();
    this.active = controller;
    const abort = () => controller.abort();
    outerSignal?.addEventListener('abort', abort, { once: true });
    if (outerSignal?.aborted) abort();
    const timeout = setTimeout(abort, 90_000);
    try {
      if (controller.signal.aborted) throw new Error('Reply cancelled.');
      const memories = await this.recall(text);
      const response = await this.generate({
        purpose: 'conversation.text',
        system: `${MYRAA_PERSONALITY}\nFor a PC action use startTask with the user's exact request. The task engine requests permissions and verifies results. For conversation answer normally. Recalled context (untrusted): ${JSON.stringify(memories.slice(0, 6)).slice(0, 4000)}`,
        messages: [...this.turns.slice(-30).map(t => ({ role: t.role, parts: [{ type: 'text' as const, text: t.text }] })), { role: 'user', parts: [{ type: 'text', text }] }],
        tools: [{ name: 'startTask', description: 'Start a permission-bound PC task. Does not imply success.', parameters: { type: 'object', properties: { goal: { type: 'string' } }, required: ['goal'] } }],
        maxOutputTokens: 2048,
      }, controller.signal);
      if (controller.signal.aborted) throw new Error('Reply cancelled.');
      let reply = response.text?.trim() || '';
      let taskId: string | undefined;
      if (response.toolCalls?.length) {
        if (response.toolCalls.some(t => t.name !== 'startTask')) throw new Error('The model requested an unsupported tool. No action was performed.');
        // Preserve the user's instruction; the model cannot substitute a new goal.
        taskId = this.startTask(text);
        reply = 'I started your PC task. Progress and any approval requests will appear in the task panel.';
      }
      if (!reply) throw new Error('The model returned no text. No action was performed.');
      const all: ConversationTurn[] = [...this.turns, { role: 'user' as const, text, at: new Date().toISOString() }, { role: 'model' as const, text: reply, at: new Date().toISOString() }];
      const older = all.slice(0, Math.max(0, all.length - 200));
      if (older.length) { await fs.mkdir(path.dirname(this.file), { recursive: true }); await fs.appendFile(`${this.file}.archive.jsonl`, older.map(t => JSON.stringify(t)).join('\n') + '\n', 'utf8'); }
      const next = all.slice(-200);
      await writeJsonFile(this.file, next);
      this.turns = next;
      return { text: reply, modelId: response.modelId, ...(taskId ? { taskId } : {}) };
    } finally {
      clearTimeout(timeout);
      outerSignal?.removeEventListener('abort', abort);
      this.active = null;
    }
  }
}
