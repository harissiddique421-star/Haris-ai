/** Restrained runtime tuning. Original models and authored profiles stay unchanged. */
import { PHYSICS_MATERIALS, type PhysicsMaterialParams } from './character/secondary';
export interface UpperBodyPhysicsTuning {
  enabled: boolean;
  strength: number;
  stiffnessMultiplier: number;
  dampingMultiplier: number;
  gravityMultiplier: number;
  maxAngleDeg: number;
  /** Whole-character spring solver budget; still capped by the graphics preset. */
  quality: 'inherit' | 'low' | 'balanced' | 'high';
}

export const DEFAULT_UPPER_BODY_PHYSICS: Readonly<UpperBodyPhysicsTuning> = Object.freeze({
  enabled: true, strength: 1, stiffnessMultiplier: 1, dampingMultiplier: 1,
  gravityMultiplier: 1, maxAngleDeg: 6, quality: 'inherit',
});

export function normalizeUpperBodyPhysics(input: unknown): UpperBodyPhysicsTuning {
  const raw = input && typeof input === 'object' ? input as Record<string, unknown> : {};
  const clamp = (key: keyof UpperBodyPhysicsTuning, min: number, max: number) => typeof raw[key] === 'number' && Number.isFinite(raw[key])
    ? Math.max(min, Math.min(max, raw[key] as number)) : DEFAULT_UPPER_BODY_PHYSICS[key] as number;
  return {
    enabled: typeof raw.enabled === 'boolean' ? raw.enabled : true,
    strength: clamp('strength', 0, 1), stiffnessMultiplier: clamp('stiffnessMultiplier', 0.5, 2),
    dampingMultiplier: clamp('dampingMultiplier', 0.75, 2), gravityMultiplier: clamp('gravityMultiplier', 0, 2),
    maxAngleDeg: clamp('maxAngleDeg', 0, 6),
    quality: ['low', 'balanced', 'high'].includes(String(raw.quality)) ? raw.quality as UpperBodyPhysicsTuning['quality'] : 'inherit',
  };
}

export function normalizeUpperBodyModels(input: unknown): Record<string, UpperBodyPhysicsTuning> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return {};
  return Object.fromEntries(Object.entries(input).filter(([id]) => /^[a-z0-9_-]{1,64}$/.test(id) && !['__proto__', 'constructor', 'prototype'].includes(id))
    .slice(0, 64).map(([id, tuning]) => [id, normalizeUpperBodyPhysics(tuning)]));
}

export function restrainedUpperBodyMaterial(base: PhysicsMaterialParams, input: unknown): PhysicsMaterialParams {
  const tuning = normalizeUpperBodyPhysics(input), fallback = PHYSICS_MATERIALS.SOFT_BODY;
  const safe = (value: number, min: number, max: number, otherwise: number) => Number.isFinite(value) ? Math.max(min, Math.min(max, value)) : otherwise;
  return {
    stiffness: safe(base.stiffness * tuning.stiffnessMultiplier, 0.1, 0.8, fallback.stiffness),
    drag: safe(base.drag * tuning.dampingMultiplier, 0.3, 0.9, fallback.drag),
    gravity: safe(base.gravity * tuning.gravityMultiplier, 0, 0.6, fallback.gravity),
    inertia: safe(base.inertia, 0, 0.65, fallback.inertia),
    restPull: safe(base.restPull, 0.2, 0.8, fallback.restPull),
    maxAngleDeg: Math.min(safe(base.maxAngleDeg, 0, 6, fallback.maxAngleDeg), tuning.maxAngleDeg),
    radius: 0,
  };
}
