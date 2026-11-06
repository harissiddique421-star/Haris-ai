/**
 * FRIDAY Pattern grid: a 3x3 pattern pad drawn on a canvas. It only collects the dots the user
 * connected ("0" to "8") and hands them to the phone, which checks them against a Keystore-protected
 * hash. Nothing about the pattern is kept here after the dialog closes.
 */
export interface PatternPrompt {
  title: string;
  message?: string;
  /** Extra buttons under the grid, e.g. "Use fingerprint". */
  actions?: { label: string; run: () => void }[];
  haptic?: () => void;
}

let open: { close: (value: string | null) => void; setMessage: (text: string, tone?: 'error' | 'ok') => void } | null = null;

/** Shows the grid and resolves with the drawn dots, or null when cancelled. */
export function drawPattern(prompt: PatternPrompt): Promise<string | null> {
  open?.close(null);
  return new Promise(resolve => {
    const dialog = document.createElement('dialog');
    dialog.className = 'pattern-dialog';
    dialog.innerHTML = `<div class="pattern-box"><h2></h2><p class="pattern-msg" role="status" aria-live="polite"></p><canvas width="300" height="300" aria-label="Pattern grid: drag across the dots"></canvas><div class="pattern-actions"></div><button type="button" class="pattern-cancel">Cancel</button></div>`;
    dialog.querySelector('h2')!.textContent = prompt.title;
    const msg = dialog.querySelector<HTMLElement>('.pattern-msg')!;
    msg.textContent = prompt.message || '';
    const actions = dialog.querySelector<HTMLElement>('.pattern-actions')!;
    for (const action of prompt.actions || []) { const b = document.createElement('button'); b.type = 'button'; b.textContent = action.label; b.onclick = action.run; actions.append(b); }
    const canvas = dialog.querySelector('canvas')!;
    const ctx = canvas.getContext('2d')!;
    const ratio = Math.min(3, window.devicePixelRatio || 1);
    canvas.width = 300 * ratio; canvas.height = 300 * ratio; ctx.scale(ratio, ratio);
    const dots = Array.from({ length: 9 }, (_, i) => ({ x: 50 + (i % 3) * 100, y: 50 + Math.floor(i / 3) * 100 }));
    let path: number[] = [], pointer: { x: number; y: number } | null = null, drawing = false, tone: 'idle' | 'ok' | 'error' = 'idle';
    const accent = () => tone === 'error' ? '#ff5470' : tone === 'ok' ? '#3ee08f' : '#38d6ff';
    function paint() {
      ctx.clearRect(0, 0, 300, 300);
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      if (path.length) {
        ctx.strokeStyle = accent(); ctx.lineWidth = 6; ctx.shadowColor = accent(); ctx.shadowBlur = 14;
        ctx.beginPath(); ctx.moveTo(dots[path[0]].x, dots[path[0]].y);
        for (const i of path.slice(1)) ctx.lineTo(dots[i].x, dots[i].y);
        if (pointer && drawing) ctx.lineTo(pointer.x, pointer.y);
        ctx.stroke(); ctx.shadowBlur = 0;
      }
      dots.forEach((d, i) => {
        const on = path.includes(i);
        ctx.beginPath(); ctx.arc(d.x, d.y, on ? 14 : 9, 0, Math.PI * 2);
        ctx.fillStyle = on ? accent() : 'rgba(180,220,255,.55)';
        if (on) { ctx.shadowColor = accent(); ctx.shadowBlur = 18; }
        ctx.fill(); ctx.shadowBlur = 0;
        if (on) { ctx.beginPath(); ctx.arc(d.x, d.y, 24, 0, Math.PI * 2); ctx.strokeStyle = accent(); ctx.lineWidth = 2; ctx.globalAlpha = .45; ctx.stroke(); ctx.globalAlpha = 1; }
      });
    }
    function point(event: PointerEvent) { const box = canvas.getBoundingClientRect(); return { x: (event.clientX - box.left) / box.width * 300, y: (event.clientY - box.top) / box.height * 300 }; }
    function hit(p: { x: number; y: number }) {
      dots.forEach((d, i) => {
        if (path.includes(i) || Math.hypot(d.x - p.x, d.y - p.y) > 34) return;
        // Passing straight over a middle dot connects it too, like Android's own pattern pad.
        const last = path[path.length - 1];
        if (last !== undefined) { const mid = (last + i) / 2; if (Number.isInteger(mid) && Math.abs((last % 3) - (i % 3)) !== 1 && Math.abs(Math.floor(last / 3) - Math.floor(i / 3)) !== 1 && !path.includes(mid)) path.push(mid); }
        path.push(i); prompt.haptic?.();
      });
    }
    canvas.onpointerdown = event => { canvas.setPointerCapture(event.pointerId); tone = 'idle'; path = []; drawing = true; pointer = point(event); hit(pointer); paint(); };
    canvas.onpointermove = event => { if (!drawing) return; pointer = point(event); hit(pointer); paint(); };
    canvas.onpointerup = () => {
      if (!drawing) return; drawing = false; pointer = null; paint();
      if (!path.length) return;
      const value = path.map(String).join('');
      finish(value);
    };
    function finish(value: string | null) {
      if (!open) return;
      open = null; path = [];
      dialog.close(); dialog.remove(); resolve(value);
    }
    dialog.querySelector<HTMLButtonElement>('.pattern-cancel')!.onclick = () => finish(null);
    dialog.addEventListener('cancel', event => { event.preventDefault(); finish(null); });
    open = {
      close: finish,
      setMessage: (text, t) => { msg.textContent = text; tone = t || 'idle'; msg.dataset.tone = tone; paint(); },
    };
    document.body.append(dialog); dialog.showModal(); paint();
  });
}

/** Updates the message of the grid that is open (used while the phone checks the pattern). */
export function patternMessage(text: string, tone?: 'error' | 'ok') { open?.setMessage(text, tone); }
/** Closes the open grid, if any. */
export function closePattern() { open?.close(null); }
