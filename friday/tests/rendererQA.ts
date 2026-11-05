import { CharacterSystem } from '../src/character/core/CharacterSystem';
import { evelynConfig } from '../src/character/config/characters/evelyn';
import { configFromProfile } from '../src/character/config/fromProfile';
import { qualityProfile, withQuality } from '../src/character/core/quality';
import { runCharacterTests } from '../src/character/testing/CharacterTests';
import * as THREE from 'three';
import { SecondarySceneQA } from './secondaryQA';
import { measureLocomotion } from './locomotionQA';
const wait = (ms: number) => new Promise(r => setTimeout(r, ms));
const status = (text: string) => { document.getElementById('status')!.textContent = text; console.log(`QA: ${text}`); };
let current: CharacterSystem | null = null;
let secondaryScene: SecondarySceneQA | null = null;
async function load(config: any, profile: any, level: any) {
  secondaryScene?.dispose(); secondaryScene = null;
  current?.dispose();
  const old = document.getElementById('avatar')!;
  const canvas = document.createElement('canvas'); canvas.id = 'avatar'; old.replaceWith(canvas);
  const quality = qualityProfile(level);
  const system = new CharacterSystem({ canvas, config: withQuality(config, quality), profile, quality });
  current = system; system.resize(960, 800); await system.load();
  const until = performance.now() + 20_000;
  while (performance.now() < until) {
    const mats = system.loadedModel!.mesh.material;
    const pending = (Array.isArray(mats) ? mats : [mats]).some((m: any) => m.map && !(m.map.image?.complete || m.map.image?.width));
    if (!pending) break; await wait(100);
  }
  system.stepFrames(10); return system;
}
(window as any).startSecondaryQA = async (id = 'evelyn', level: 'low' | 'balanced' | 'high' = 'balanced') => {
  const profiles = await fetch('/qa/catalogue').then(response => response.json());
  const profile = profiles.find((entry: any) => entry.id === id);
  if (id !== 'evelyn' && !profile) throw new Error(`Unknown character ${id}`);
  const config = id === 'evelyn' ? evelynConfig : configFromProfile(profile);
  const system = await load(config, profile, level);
  system.stage.renderer.domElement.style.width = '300px';
  system.stage.renderer.domElement.style.height = '560px';
  system.resize(300, 560);
  secondaryScene = new SecondarySceneQA(system, level, config.morphs); return secondaryScene.inventory;
};
(window as any).runSecondaryPhase = (phase: string) => secondaryScene!.run(phase);
(window as any).runLocomotionQA = async (id = 'evelyn') => {
  const profiles = await fetch('/qa/catalogue').then(response => response.json());
  const profile = profiles.find((entry: any) => entry.id === id);
  if (id !== 'evelyn' && !profile) throw new Error('Unknown test character.');
  const config = id === 'evelyn' ? evelynConfig : configFromProfile(profile);
  const system = await load(config, profile, 'balanced');
  system.stage.renderer.domElement.style.width = '300px';
  system.stage.renderer.domElement.style.height = '560px';
  system.resize(300, 560);
  return { id, ...(await measureLocomotion(system)) };
};
(window as any).runAvatarQA = async (onlyId?: string) => {
  const catalogue = await fetch('/qa/catalogue').then(r => r.json());
  const results: any[] = [];
  const entries = [{ id: 'evelyn', config: evelynConfig, profile: undefined }, ...catalogue.map((profile: any) => ({ id: profile.id, config: configFromProfile(profile), profile }))];
  for (const entry of entries.filter(entry => !onlyId || entry.id === onlyId)) {
    status(`Testing ${entry.id}`);
    try {
      const system = await load(entry.config, entry.profile, 'balanced');
      const checks = await runCharacterTests(system, name => status(`${entry.id}: ${name}`));
      system.stop(); system.setOwnership('AI'); system.stand();
      // Establish the neutral starting pose: random stretches from the preceding
      // idle test must not be mistaken for the wave raising a straight arm.
      system.setFrameInput({ activity: 'idle', emotion: 'happy' }); system.stopBehaviour(); system.stepFrames(180);
      // Exercise the real wave with happy expression (this used to cancel it).
      system.playBehaviour('wave');
      const positions: number[] = []; let finite = true;
      const model = system.loadedModel!;
      const wrist = model.bones[model.boneIndexByName.get(system.boneMap.wristL)!];
      const arm = model.bones[model.boneIndexByName.get(system.boneMap.armL)!];
      const elbow = model.bones[model.boneIndexByName.get(system.boneMap.elbowL)!];
      const height = new THREE.Box3().setFromObject(model.mesh).getSize(new THREE.Vector3()).y;
      let previous: THREE.Vector3 | null = null, maxStep = 0, straightHorizontalFrames = 0;
      const horizontalSamples: any[] = [];
      for (let i = 0; i < 220; i++) {
        system.stepFrames(1);
        if (wrist) {
          const hand = wrist.getWorldPosition(new THREE.Vector3()); positions.push(hand.y);
          if (previous) maxStep = Math.max(maxStep, hand.distanceTo(previous)); previous = hand;
          if (arm && elbow) {
            const joint = elbow.getWorldPosition(new THREE.Vector3());
            const upper = joint.clone().sub(arm.getWorldPosition(new THREE.Vector3()));
            const lower = hand.clone().sub(joint);
            if (Math.abs(upper.y) / upper.length() < 0.2 && upper.angleTo(lower) < THREE.MathUtils.degToRad(20)) { straightHorizontalFrames++; horizontalSamples.push({ frame:i, bendDeg:THREE.MathUtils.radToDeg(upper.angleTo(lower)), verticalRatio:upper.y/upper.length() }); }
          }
        }
        if (model.bones.some(b => ![...b.position, ...b.quaternion].every(Number.isFinite))) finite = false;
      }
      results.push({ id: entry.id, checks, wave: { finite, handTravel: Math.max(...positions) - Math.min(...positions), maxStepHeightRatio: maxStep / height, straightHorizontalFrames, horizontalSamples }, diagnostics: system.diagnostics });
    } catch (error) { results.push({ id: entry.id, error: String(error) }); }
  }
  const presets: any[] = [];
  for (const level of onlyId ? [] : ['low', 'balanced', 'high']) {
    status(`Measuring ${level}`);
    const system = await load(evelynConfig, undefined, level);
    system.start(); await wait(5000); system.stop();
    presets.push({ level, ...system.diagnostics });
  }
  status('Complete'); return { results, presets, gpu: (() => { const gl = current!.stage.renderer.getContext(); const ext = gl.getExtension('WEBGL_debug_renderer_info'); return ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER); })() };
};
(window as any).runPresetBenchmark = async () => {
  const presets: any[] = [];
  for (const level of ['low', 'balanced', 'high']) {
    const system = await load(evelynConfig, undefined, level);
    system.start(); await wait(8000); system.stop();
    presets.push({ level, ...system.diagnostics });
  }
  return { mode: 'Electron offscreen GPU rendering, 960x800, 60Hz compositor', presets };
};
