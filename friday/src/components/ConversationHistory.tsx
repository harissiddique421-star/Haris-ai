import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import type { ConversationTurn } from '../../shared/conversation';
export function ConversationHistory({ onClose }: { onClose: () => void }) {
  const [turns, setTurns] = useState<ConversationTurn[]>([]);
  const [error, setError] = useState('');
  useEffect(() => { let cancelled = false; void fetch('/api/chat/history').then(async r => { if (!r.ok) throw new Error('Could not load conversation history.'); const data = await r.json(); if (!cancelled) setTurns(data); }).catch(e => { if (!cancelled) setError(e.message); }); return () => { cancelled = true; }; }, []);
  return <div className="fixed inset-0 z-[90] flex items-center justify-center bg-black/70 p-6 backdrop-blur-sm">
    <section role="dialog" aria-modal="true" aria-label="Text conversation history" className="flex max-h-[80vh] w-full max-w-2xl flex-col rounded-2xl border border-white/10 bg-[#10151c] p-6 text-slate-200">
      <header className="flex items-center justify-between"><h2 className="text-lg">Text conversation history</h2><button type="button" aria-label="Close conversation history" onClick={onClose}><X size={18}/></button></header>
      <p className="mt-2 text-xs text-slate-500">Saved locally. Voice conversations and durable memories have their own controls.</p>
      <div className="mt-5 space-y-5 overflow-y-auto text-sm leading-relaxed">{error && <p role="alert">{error}</p>}{!turns.length && !error && <p className="text-slate-500">Your text conversations will appear here.</p>}{turns.map((turn, i) => <article key={`${turn.at}:${i}`}><p className="mb-1 text-[10px] uppercase tracking-widest text-slate-500">{turn.role === 'user' ? 'You' : 'MYRAA'}</p><p className="whitespace-pre-wrap break-words">{turn.text}</p></article>)}</div>
    </section>
  </div>;
}
