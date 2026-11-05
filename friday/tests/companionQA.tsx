/** Actual CompanionApp and real model; deterministic settings, no agent/provider requests. */
import React from 'react';
import { createRoot } from 'react-dom/client';
import CompanionApp from '../src/companion/CompanionApp';
import '../src/index.css';

const nativeFetch = window.fetch.bind(window);
const settings = { character: { activeCharacterId: 'evelyn', eyeFollowCursor: false },
  companion: { enabled: true, mode: 'interactive', standOut: 0 },
  graphics: { quality: 'low', autoStep: 0 },
  physics: { enabled: true, quality: 'low', secondaryMotion: 100, wind: 0 },
  developer: { physicsDebug: false } };
window.fetch = async (input, options) => {
  const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, location.href);
  if (url.pathname === '/api/app-settings') return new Response(JSON.stringify(settings), { status: 200, headers: { 'Content-Type': 'application/json' } });
  if (url.pathname.startsWith('/api/')) throw new Error(`Unexpected test API request: ${url.pathname}`);
  return nativeFetch(input, options);
};
window.addEventListener('myraa:character-diagnostics', event => {
  (window as any).__companionDiagnostics = (event as CustomEvent).detail;
});
createRoot(document.getElementById('root')!).render(<CompanionApp />);
