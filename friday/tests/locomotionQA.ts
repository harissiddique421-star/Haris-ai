import * as THREE from 'three';
import { CompanionActor } from '../src/companion/CompanionActor';
import type { CharacterSystem } from '../src/character/core/CharacterSystem';

/** Real skeleton + production actor; virtual CSS-window travel, no OS input. */
export async function measureLocomotion(system: CharacterSystem) {
  system.stop(); system.setIdleBehaviours(false); system.stopBehaviour();
  system.setFrameInput({ activity: 'idle', emotion: 'idle' });
  system.setOwnership('AI'); system.stepFrames(120);
  system.frameCompanion({ top: 0.16, width: 0.34, floorPx: 8 });
  const actor = new CompanionActor(system, { contact: () => {}, metrics: () => {}, done: () => {} });
  const height = system.worldHeight;
  const results: any[] = [];
  try {
    for (const scenario of [{ dir: 1 as const, sneak: false }, { dir: -1 as const, sneak: false }, { dir: 1 as const, sneak: true }]) {
      actor.handle({ act: 'stop' }); system.setIdleBehaviours(false); system.stepFrames(160);
      actor.handle({ act: 'walk', ...scenario });
      let windowX = 0, speed = 0, finite = true, maxSoleError = 0, maxStep = 0, maxSolveError = 0, maxStepMs = 0;
      const planted = [false, false];
      const first = [0, 0], previous = [null, null] as Array<THREE.Vector3 | null>;
      const drift: number[] = [];
      let activeDrift = [0, 0], samples = 0, captures: string[] = [];
      const requestedSpeed = actor.metrics().walkSpeed;
      for (let frame = 0; frame < 420; frame++) {
        speed += (requestedSpeed - speed) * (1 - Math.exp(-6 / 60));
        const dx = scenario.dir * speed / 60;
        windowX += dx; actor.onMoved(dx, 0, 'walk'); system.addScreenMotion(dx, 0);
        const stepStarted = performance.now(); system.stepFrames(1);
        maxStepMs = Math.max(maxStepMs, performance.now() - stepStarted);
        const phase = (actor as any).phase;
        const contacts = (actor as any).locomotionDiagnostics?.feet;
        if (frame >= 120 && contacts) maxSolveError = Math.max(maxSolveError, ...contacts.filter((foot: any) => foot.planted).map((foot: any) => foot.errorWorld / height));
        const model = system.loadedModel!;
        if (model.bones.some(bone => ![...bone.position, ...bone.quaternion].every(Number.isFinite))) finite = false;
        if (frame >= 120) for (let side = 0; side < 2; side++) {
          const slot = side === 0 ? 'LEFT_FOOT' : 'RIGHT_FOOT';
          const point = system.slotWorld(slot); if (!point) continue;
          const x = system.worldToCanvas(point).x + windowX;
          const contact = contacts ? contacts[side]?.planted : Math.cos(phase + side * Math.PI) < -0.15;
          if (contact && !planted[side]) { first[side] = x; activeDrift[side] = 0; }
          if (contact) { activeDrift[side] = Math.max(activeDrift[side], Math.abs(x - first[side])); samples++; }
          if (!contact && planted[side]) drift.push(activeDrift[side]);
          planted[side] = contact;
          if (previous[side]) maxStep = Math.max(maxStep, point.distanceTo(previous[side]!) / height);
          previous[side] = point.clone();
          const soles = system.solePoints();
          if (soles.length) maxSoleError = Math.max(maxSoleError, Math.abs(Math.min(...soles.map(p => p.y)) - system.floorY!) / height);
        }
        if ([180, 230, 280, 330].includes(frame)) captures.push(system.stage.renderer.domElement.toDataURL('image/png'));
        if (frame % 20 === 0) await new Promise(resolve => setTimeout(resolve, 16));
      }
      drift.push(...activeDrift);
      actor.handle({ act: 'stop' }); system.setIdleBehaviours(false); system.stepFrames(180);
      results.push({ ...scenario, finite, stanceSamples: samples, stanceWindows: drift.length,
        maximumPlantDriftCssPx: Math.max(0, ...drift), meanPlantDriftCssPx: drift.reduce((sum, value) => sum + value, 0) / Math.max(1, drift.length),
        maximumGroundErrorHeightRatio: maxSoleError, maximumFootStepHeightRatio: maxStep,
        maximumContactSolveErrorHeightRatio: maxSolveError, maximumStepAndRenderMs: maxStepMs,
        stopSoleErrorHeightRatio: Math.abs((system.lowestSoleY() ?? 0) - system.floorY!) / height,
        diagnostics: (actor as any).locomotionDiagnostics ?? null, captures });
    }
  } finally { actor.dispose(); }
  return { scope: 'Actual character/actor at 300x560 with virtual shell movement at 60Hz; no native walking or audio.', results };
}
