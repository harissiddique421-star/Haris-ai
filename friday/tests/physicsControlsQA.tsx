/** Actual React controls; simulated settings transport/system, no 3D or owner data. */
import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { PhysicsPanel } from '../src/components/character/PhysicsPanel';
import type { CharacterSystem } from '../src/character/core/CharacterSystem';
import type { AppSettingsLite } from '../src/components/character/studioApi';
import { normalizeUpperBodyPhysics } from '../shared/upperBodyPhysics';
import '../src/index.css';

let fixture: AppSettingsLite = { character: { activeCharacterId: 'evelyn', eyeFollowCursor: false, headFollowCursor: false, idleVariety: 0 }, developer: { physicsDebug: false }, physics: {
  enabled: true, quality: 'balanced', secondaryMotion: 100, gravityMultiplier: 1, stiffness: 1, damping: 1, drag: 1,
  collisionQuality: 'low', wind: 0, upperBodyByModel: { astra: normalizeUpperBodyPhysics({ strength: 0.25 }) },
} };
(window as any).__physicsRequests = [];
window.fetch = async (input, options) => {
  if (String(input) !== '/api/app-settings' || options?.method !== 'POST') throw new Error('Unexpected UI-fixture request.');
  const patch = JSON.parse(String(options.body));
  (window as any).__physicsRequests.push(patch);
  fixture = { ...fixture, physics: { ...fixture.physics, ...patch.physics,
    upperBodyByModel: { ...fixture.physics.upperBodyByModel, ...patch.physics?.upperBodyByModel } } };
  return new Response(JSON.stringify(fixture), { status: 200, headers: { 'Content-Type': 'application/json' } });
};
const system = { rig: { chains: [{ id: 'chest:test', class: 'chest', material: 'SOFT_BODY', bones: ['test'], anchor: 'torso', classifiedBy: 'name', label: 'Authored fixture', enabled: true }] },
  secondaryMotion: { stats: { nodes: 1, colliders: 0, hz: 30, effectiveQuality: 'low', stepMs: 0, maxDeviationDeg: 0 } },
  setChainEnabled: () => {}, setPhysicsDebug: () => {}, addScreenMotion: () => {},
} as unknown as CharacterSystem;
function Fixture() {
  const [settings, setSettings] = useState(fixture), [id, setId] = useState('evelyn');
  return <div className="p-4">
    <div className="mb-3 flex items-center justify-between text-sm"><span>Controls fixture: {id}</span><button id="switch-test-model" onClick={() => setId(id === 'evelyn' ? 'astra' : 'evelyn')}>Switch test model</button></div>
    <PhysicsPanel characterId={id} system={system} profile={null} settings={settings} onSettings={setSettings} onProfile={() => {}} onReload={() => {}} />
  </div>;
}
createRoot(document.getElementById('root')!).render(<Fixture />);
