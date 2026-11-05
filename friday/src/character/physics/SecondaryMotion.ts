/**
 * Real-time secondary motion for hair, cloth, ribbons, accessories and tails.
 *
 * Pipeline per frame (driven by CharacterSystem):
 *
 *   body animation → skeleton (pose, morphs, grants) → THIS → final pose
 *
 * Body bones are authoritative: only bones listed in the character's physics
 * chains are ever written here, so the pose editor, IK and procedural animation
 * keep full control of the body while hair and cloth react to it.
 *
 * Each simulated bone is a point mass at its tail, integrated with Verlet on a
 * fixed timestep:
 *
 *   inertia        the tail keeps its world velocity, so it lags real motion
 *                  (body turns, IK moves, falls, landings, window drags)
 *   spring         pulled back toward the bone's animated rest direction
 *   gravity/wind   world-space forces (gravity always points down)
 *   drag           velocity loss
 *   collision      pushed out of body capsules/spheres (the model's own
 *                  kinematic rigid bodies, filtered by its collision groups)
 *   length         re-projected onto the bone length (bones never stretch)
 *   limit          clamped to a material-specific maximum angle
 *   sheet          skirt / jacket / sleeve strands are linked sideways to
 *                  their neighbours at the same depth, so a skirt moves as one
 *                  connected piece of fabric (it drapes over the thighs, opens
 *                  and folds) instead of as independent sticks
 *
 * External motion (dragging the desktop-companion window) moves the
 * character's frame without moving it in the scene; it is fed in as an
 * inertial offset so hair and clothes swing exactly as if she was carried.
 */
import * as THREE from 'three';
import { normalizeUpperBodyPhysics, restrainedUpperBodyMaterial, type UpperBodyPhysicsTuning } from '../../../shared/upperBodyPhysics';
import type { ColliderProfile, PhysicsChainProfile, PhysicsMaterialParams } from '@/shared/character/secondary';
import { PHYSICS_MATERIALS } from '@/shared/character/secondary';
import type { PmxModel } from '../loaders/pmxTypes';

export type PhysicsQuality = 'low' | 'balanced' | 'high';

export interface SecondaryMotionSettings {
  enabled: boolean;
  quality: PhysicsQuality;
  /** 0..1 blend between animated pose and full physics. */
  secondaryMotion: number;
  gravityMultiplier: number;
  stiffness: number;
  damping: number;
  drag: number;
  collisionQuality: 'off' | 'low' | 'high';
  /** 0..1, off by default. */
  wind: number;
  /** Let the solver lower its own quality when frames get expensive. */
  adaptive: boolean;
}

export const DEFAULT_SECONDARY_SETTINGS: SecondaryMotionSettings = {
  enabled: true,
  quality: 'balanced',
  secondaryMotion: 1,
  gravityMultiplier: 1,
  stiffness: 1,
  damping: 1,
  drag: 1,
  collisionQuality: 'high',
  wind: 0,
  adaptive: true,
};

const QUALITY: Record<PhysicsQuality, { hz: number; maxSubSteps: number }> = {
  low: { hz: 30, maxSubSteps: 2 },
  balanced: { hz: 60, maxSubSteps: 3 },
  high: { hz: 90, maxSubSteps: 4 },
};

interface Collider {
  bone: THREE.Bone;
  shape: 'sphere' | 'capsule';
  radius: number;
  halfHeight: number;
  /** Offset and axis in the bone's local frame. */
  localOffset: THREE.Vector3;
  localAxis: THREE.Vector3;
  group: number;
  mask: number;
  /** World-space cache, refreshed once per substep. */
  center: THREE.Vector3;
  axis: THREE.Vector3;
  primary: boolean;
}

interface Node {
  bone: THREE.Bone;
  chain: string;
  material: PhysicsMaterialParams;
  /** Rest direction to the tail in the parent's frame (unit). */
  axis: THREE.Vector3;
  /** Primary animation/morph/grant result for this display frame. */
  animatedRotation: THREE.Quaternion;
  length: number;
  currentTail: THREE.Vector3;
  prevTail: THREE.Vector3;
  massFactor: number;
  authoredDrag: number;
  radius: number;
  depth: number;
  colliders: Collider[];
  pinned: boolean;
  /** Fabric class (skirt / jacket / sleeve) for sideways links, else null. */
  fabric: string | null;
}

/** A sideways cloth link between two strand tails at the same chain depth. */
interface SheetLink {
  a: Node;
  b: Node;
  rest: number;
}

const FABRIC_CLASSES = new Set(['skirt', 'jacket', 'sleeve']);

const GRAVITY_DIR = new THREE.Vector3(0, -1, 0);
const IDENTITY_ROTATION = new THREE.Quaternion();

export interface SecondaryStats {
  nodes: number;
  colliders: number;
  chains: number;
  stepMs: number;
  hz: number;
  effectiveQuality: PhysicsQuality;
  maxDeviationDeg: number;
}

export class SecondaryMotion {
  private nodes: Node[] = [];
  /** Nodes grouped by skeleton depth, parents first; solved level by level. */
  private levels: Node[][] = [];
  private links: SheetLink[] = [];
  private colliders: Collider[] = [];
  private settings: SecondaryMotionSettings = { ...DEFAULT_SECONDARY_SETTINGS };
  private effectiveQuality: PhysicsQuality = 'balanced';
  private accumulator = 0;
  private initialised = false;
  private readonly chainEnabled = new Map<string, boolean>();
  private readonly chainMaterial = new Map<string, PhysicsMaterialParams>();
  private readonly simulatedBones = new Set<string>();
  private readonly upperBodyMaterials = new Map<string, PhysicsMaterialParams>();
  private readonly softBodyChains = new Set<string>();
  private upperBodyTuning: UpperBodyPhysicsTuning = normalizeUpperBodyPhysics(undefined);
  private resetUpperBodyPending = false;

  /** Inertial frame offset (window drags), plus motion not yet consumed. */
  private readonly frameOffset = new THREE.Vector3();
  private readonly pendingOffset = new THREE.Vector3();
  private windTime = 0;
  private readonly windDir = new THREE.Vector3(1, 0, 0.35).normalize();
  private stepCostMs = 0;
  private lastMaxDeviation = 0;
  /** World units per model unit (root scale), for radii and forces. */
  private worldScale = 1;

  private readonly _center = new THREE.Vector3();
  private readonly _parentQuat = new THREE.Quaternion();
  private readonly _invParentQuat = new THREE.Quaternion();
  private readonly _restDir = new THREE.Vector3();
  private readonly _dir = new THREE.Vector3();
  private readonly _next = new THREE.Vector3();
  private readonly _delta = new THREE.Vector3();
  private readonly _localDir = new THREE.Vector3();
  private readonly _quat = new THREE.Quaternion();
  private readonly _a = new THREE.Vector3();
  private readonly _b = new THREE.Vector3();
  private readonly _scale = new THREE.Vector3();
  private readonly _wind = new THREE.Vector3();

  constructor(
    private readonly model: PmxModel,
    chains: PhysicsChainProfile[],
    colliders: ColliderProfile[],
    private readonly nodeGroups: Record<string, { group: number; mask: number }>
  ) {
    this.build(chains, colliders);
  }

  // ---- construction --------------------------------------------------------

  private bone(name: string | null | undefined): THREE.Bone | undefined {
    if (!name) return undefined;
    const index = this.model.boneIndexByName.get(name);
    return index === undefined ? undefined : this.model.bones[index];
  }

  private build(chains: PhysicsChainProfile[], colliderProfiles: ColliderProfile[]): void {
    this.model.mesh.updateMatrixWorld(true);
    this.model.mesh.getWorldScale(this._scale);
    this.worldScale = this._scale.x || 1;

    // Colliders, expressed in their bone's local frame.
    for (const profile of colliderProfiles) {
      const bone = this.bone(profile.bone);
      if (!bone) continue;
      const info = this.model.boneInfos[this.model.boneIndexByName.get(profile.bone)!];
      // PMX bones carry no rest rotation, so the model-space rest offset and
      // axis are already expressed in the bone's local frame.
      this.colliders.push({
        bone,
        shape: profile.shape,
        radius: profile.radius,
        halfHeight: profile.height / 2,
        localOffset: new THREE.Vector3(...profile.offset),
        localAxis: new THREE.Vector3(...profile.axis).normalize(),
        group: profile.group,
        mask: profile.mask,
        center: new THREE.Vector3(),
        axis: new THREE.Vector3(),
        // Torso, head and legs matter most; arms are skipped at low quality.
        primary: /頭|首|上半身|下半身|足|ひざ|head|neck|spine|chest|hip|leg|knee|thigh/i.test(info?.name ?? profile.bone),
      });
    }

    const childrenOf = new Map<number, number[]>();
    this.model.boneInfos.forEach((info) => {
      if (info.parentIndex >= 0) {
        const list = childrenOf.get(info.parentIndex) ?? [];
        list.push(info.index);
        childrenOf.set(info.parentIndex, list);
      }
    });
    const depthOf = (index: number): number => {
      let d = 0;
      for (let cursor = this.model.boneInfos[index]?.parentIndex ?? -1; cursor >= 0 && d < 512; cursor = this.model.boneInfos[cursor].parentIndex) d += 1;
      return d;
    };
    const bodyByBone = new Map<number, (typeof this.model.rigidBodies)[number]>();
    for (const body of this.model.rigidBodies) if (!bodyByBone.has(body.boneIndex) && body.type !== 'kinematic') bodyByBone.set(body.boneIndex, body);

    for (const chain of chains) {
      this.chainEnabled.set(chain.id, chain.enabled);
      const softBody = chain.class === 'chest' || chain.class === 'body';
      const baseMaterial = { ...PHYSICS_MATERIALS[softBody ? 'SOFT_BODY' : chain.material], ...(chain.overrides ?? {}) };
      const material = softBody ? restrainedUpperBodyMaterial(baseMaterial, undefined) : baseMaterial;
      if (softBody) this.softBodyChains.add(chain.id);
      if (chain.class === 'chest') this.upperBodyMaterials.set(chain.id, { ...baseMaterial });
      this.chainMaterial.set(chain.id, material);
      const members = new Set(chain.bones);
      const fabric = FABRIC_CLASSES.has(chain.class) ? chain.class : null;
      for (const name of chain.bones) {
        const index = this.model.boneIndexByName.get(name);
        if (index === undefined) continue;
        const bone = this.model.bones[index];
        const info = this.model.boneInfos[index];
        // Tail: the first child in the same chain, else any child, else the
        // rigid body's centre, else the PMX tail offset.
        const kids = childrenOf.get(index) ?? [];
        const sameChain = kids.find((k) => members.has(this.model.boneInfos[k].name));
        const tailIndex = sameChain ?? kids[0];
        const tail = new THREE.Vector3();
        const body = bodyByBone.get(index);
        if (tailIndex !== undefined) tail.subVectors(this.model.boneInfos[tailIndex].position, info.position);
        else if (body) tail.subVectors(body.position, info.position).multiplyScalar(2);
        const length = tail.length();
        if (length < 1e-4) continue;
        const massFactor = body ? THREE.MathUtils.clamp(Math.log10(body.mass + 1) / 1.7, 0.05, 1) : 0.5;
        const authoredDrag = body ? THREE.MathUtils.clamp(body.rotationDamping, 0, 0.999) : 0.5;
        const groups = this.nodeGroups[name] ?? { group: body?.groupIndex ?? 15, mask: body?.groupTarget ?? 0xffff };
        const radius = body ? Math.max(0.02, Math.min(body.size.x, 1.2)) : material.radius;
        const node: Node = {
          bone,
          chain: chain.id,
          material,
          axis: tail.clone().divideScalar(length),
          animatedRotation: bone.quaternion.clone(),
          length,
          currentTail: new THREE.Vector3(),
          prevTail: new THREE.Vector3(),
          massFactor,
          authoredDrag,
          radius,
          depth: depthOf(index),
          colliders: [],
          pinned: false,
          fabric,
        };
        // Collision filter: both sides must accept each other (Bullet rule),
        // and colliders the strand already sits inside at rest are ignored —
        // authors often overlap hair roots with the head body on purpose.
        bone.updateWorldMatrix(true, false);
        const head = new THREE.Vector3().setFromMatrixPosition(bone.matrixWorld);
        const restTail = this.restTailWorld(node, head);
        for (const collider of this.colliders) {
          const accepts = (groups.mask & (1 << collider.group)) !== 0 && (collider.mask & (1 << groups.group)) !== 0;
          if (!accepts) continue;
          this.updateCollider(collider);
          if (this.penetration(collider, restTail, radius * this.worldScale) > 0) continue;
          node.colliders.push(collider);
        }
        this.nodes.push(node);
        this.simulatedBones.add(bone.name);
      }
    }
    // Parents before children so each child sees an up-to-date parent.
    this.nodes.sort((a, b) => a.depth - b.depth);
    const byDepth = new Map<number, Node[]>();
    for (const node of this.nodes) {
      const level = byDepth.get(node.depth) ?? [];
      level.push(node);
      byDepth.set(node.depth, level);
    }
    this.levels = [...byDepth.keys()].sort((a, b) => a - b).map((d) => byDepth.get(d)!);
    this.buildSheet();
  }

  /**
   * Link each fabric bone to its nearest neighbours of the same fabric class at
   * the same skeleton depth (bones at one depth are never parent and child,
   * so these are always side-by-side strands - one analysed "skirt" chain
   * usually contains every strand of the skirt). Only genuinely adjacent
   * strands are linked (within ~2 segment lengths), so separate garments and
   * the gaps of a slit skirt stay separate.
   */
  private buildSheet(): void {
    this.links = [];
    const tails = new Map<Node, THREE.Vector3>();
    for (const node of this.nodes) {
      if (!node.fabric) continue;
      node.bone.updateWorldMatrix(true, false);
      tails.set(node, this.restTailWorld(node, new THREE.Vector3().setFromMatrixPosition(node.bone.matrixWorld)));
    }
    const seen = new Set<string>();
    const fabricNodes = [...tails.keys()];
    for (const node of fabricNodes) {
      const here = tails.get(node)!;
      const candidates = fabricNodes
        .filter((other) => other !== node && other.fabric === node.fabric && other.depth === node.depth)
        .map((other) => ({ other, d: here.distanceTo(tails.get(other)!) }))
        .filter(({ other, d }) => d < Math.max(node.length, other.length) * this.worldScale * 2.2)
        .sort((x, y) => x.d - y.d)
        .slice(0, 2);
      for (const { other, d } of candidates) {
        const key = [this.nodes.indexOf(node), this.nodes.indexOf(other)].sort((a, b) => a - b).join(':');
        if (seen.has(key) || d < 1e-5) continue;
        seen.add(key);
        this.links.push({ a: node, b: other, rest: d });
      }
    }
  }

  private capsuleCache: Array<{ a: THREE.Vector3; b: THREE.Vector3; radius: number }> = [];

  /**
   * Body colliders in their current world placement (without the inertial
   * frame offset), for the vertex cloth layer. Spheres have a == b.
   */
  worldCapsules(): Array<{ a: THREE.Vector3; b: THREE.Vector3; radius: number }> {
    const s = this.worldScale;
    if (this.capsuleCache.length !== this.colliders.length) {
      this.capsuleCache = this.colliders.map(() => ({ a: new THREE.Vector3(), b: new THREE.Vector3(), radius: 0 }));
    }
    this.colliders.forEach((collider, i) => {
      const out = this.capsuleCache[i];
      collider.bone.updateWorldMatrix(false, false);
      this._center.copy(collider.localOffset).applyMatrix4(collider.bone.matrixWorld);
      collider.bone.getWorldQuaternion(this._quat);
      this._dir.copy(collider.localAxis).applyQuaternion(this._quat);
      const h = collider.shape === 'capsule' ? collider.halfHeight * s : 0;
      out.a.copy(this._center).addScaledVector(this._dir, -h);
      out.b.copy(this._center).addScaledVector(this._dir, h);
      out.radius = collider.radius * s;
    });
    return this.capsuleCache;
  }

  /** Sideways fabric links, for diagnostics and tests. */
  get sheetLinkCount(): number {
    return this.links.length;
  }

  private restTailWorld(node: Node, head: THREE.Vector3): THREE.Vector3 {
    const parent = node.bone.parent;
    if (parent) parent.getWorldQuaternion(this._parentQuat);
    else this._parentQuat.identity();
    return head.clone().addScaledVector(this._restDir.copy(node.axis).applyQuaternion(node.animatedRotation).applyQuaternion(this._parentQuat), node.length * this.worldScale);
  }

  // ---- public controls -----------------------------------------------------

  get nodeCount(): number {
    return this.nodes.length;
  }

  get colliderCount(): number {
    return this.colliders.length;
  }

  get groupBreakdown(): Record<string, number> {
    const out: Record<string, number> = {};
    for (const n of this.nodes) out[n.chain] = (out[n.chain] ?? 0) + 1;
    return out;
  }

  get stats(): SecondaryStats {
    return {
      nodes: this.nodes.length,
      colliders: this.colliders.length,
      chains: this.chainEnabled.size,
      stepMs: +this.stepCostMs.toFixed(3),
      hz: QUALITY[this.effectiveQuality].hz,
      effectiveQuality: this.effectiveQuality,
      maxDeviationDeg: +THREE.MathUtils.radToDeg(this.lastMaxDeviation).toFixed(1),
    };
  }

  setSettings(settings: Partial<SecondaryMotionSettings>): void {
    const wasEnabled = this.settings.enabled;
    this.settings = { ...this.settings, ...settings };
    this.effectiveQuality = this.settings.quality;
    if (this.settings.enabled && !wasEnabled) this.reset();
  }

  setChainEnabled(id: string, enabled: boolean): void {
    this.chainEnabled.set(id, enabled);
  }

  private chainIsEnabled(id: string): boolean {
    return this.chainEnabled.get(id) === true && (!this.upperBodyMaterials.has(id) || this.upperBodyTuning.enabled);
  }

  setUpperBodySettings(input: unknown): void {
    const next = normalizeUpperBodyPhysics(input);
    if (JSON.stringify(next) === JSON.stringify(this.upperBodyTuning)) return;
    this.upperBodyTuning = next;
    for (const [id, base] of this.upperBodyMaterials) Object.assign(this.chainMaterial.get(id)!, restrainedUpperBodyMaterial(base, next));
    this.resetUpperBodyPending = true;
  }

  /** The pose editor pins bones the user is holding; physics leaves them alone. */
  setPinned(boneName: string, pinned: boolean): void {
    for (const node of this.nodes) if (node.bone.name === boneName) node.pinned = pinned;
  }

  isSimulated(boneName: string): boolean {
    return this.simulatedBones.has(boneName);
  }

  /**
   * The character's frame moved by `delta` world units without the scene
   * moving (desktop window drag). Consumed smoothly over the next substeps.
   */
  addFrameMotion(delta: THREE.Vector3): void {
    this.pendingOffset.add(delta);
  }

  /**
   * Teleport: snap every strand along with a discontinuous move (monitor
   * change, window snapped to a new anchor) without injecting velocity.
   */
  rebase(): void {
    this.reset();
    this.pendingOffset.set(0, 0, 0);
  }

  /** Snap every chain to its rest pose. Call after a load or a pose jump. */
  reset(): void {
    this.model.mesh.updateMatrixWorld(true);
    this.frameOffset.set(0, 0, 0);
    for (const node of this.nodes) {
      node.animatedRotation.copy(node.bone.quaternion);
      node.bone.updateWorldMatrix(true, false);
      this._center.setFromMatrixPosition(node.bone.matrixWorld);
      node.currentTail.copy(this.restTailWorld(node, this._center));
      node.prevTail.copy(node.currentTail);
    }
    this.accumulator = 0;
    this.initialised = true;
  }

  update(delta: number): void {
    if (!this.settings.enabled || this.nodes.length === 0) return;
    // CharacterSystem restores the primary pose before entering this layer.
    // Keep local animation and twist; physics adds a constrained offset to it.
    for (const node of this.nodes) node.animatedRotation.copy(node.bone.quaternion);
    if (!this.initialised) {
      this.reset();
      this.resetUpperBodyPending = false;
      return;
    }
    if (this.resetUpperBodyPending) {
      // Retune only chest tails at the primary-pose boundary. Keep unrelated
      // hair/garment history and never capture a physical rotation as rest.
      for (const node of this.nodes) {
        if (!this.upperBodyMaterials.has(node.chain)) continue;
        node.bone.updateWorldMatrix(true, false);
        this._center.setFromMatrixPosition(node.bone.matrixWorld).add(this.frameOffset);
        node.currentTail.copy(this.restTailWorld(node, this._center)); node.prevTail.copy(node.currentTail);
      }
      this.resetUpperBodyPending = false;
    }
    // A frame stall longer than this is a pause, not motion: rebase instead
    // of replaying it, which is how spring rigs explode.
    if (delta > 0.25) {
      this.rebase();
      return;
    }
    const quality = QUALITY[this.effectiveQuality];
    const step = 1 / quality.hz;
    this.accumulator = Math.min(this.accumulator + delta, step * quality.maxSubSteps);
    const steps = Math.floor(this.accumulator / step);
    if (steps === 0) {
      // Animation, morphs and grants are evaluated at display rate. Reapply
      // the last physical state even when the fixed-step solver is not due;
      // otherwise registered secondary bones pop back to rest every other
      // frame at 30 Hz physics / 60+ Hz display.
      const amplitude = THREE.MathUtils.clamp(this.settings.secondaryMotion, 0, 1);
      for (const level of this.levels) for (const node of level) this.commit(node, amplitude);
      return;
    }
    const started = performance.now();
    for (let i = 0; i < steps; i += 1) {
      // Spread external frame motion evenly across this frame's substeps.
      this._delta.copy(this.pendingOffset).multiplyScalar(1 / (steps - i));
      this.pendingOffset.sub(this._delta);
      this.frameOffset.add(this._delta);
      this.simulate(step);
      this.accumulator -= step;
    }
    // Keep the inertial offset small: shifting tails and offset together is exact.
    if (this.frameOffset.lengthSq() > 1e6) {
      for (const node of this.nodes) {
        node.currentTail.sub(this.frameOffset);
        node.prevTail.sub(this.frameOffset);
      }
      this.frameOffset.set(0, 0, 0);
    }
    const cost = performance.now() - started;
    this.stepCostMs = this.stepCostMs * 0.9 + cost * 0.1;
    this.adapt();
  }

  /** Drop to a cheaper rate when the solver alone costs too much frame time. */
  private lastQualityChange = 0;
  private fastSamples = 0;
  private adapt(): void {
    if (!this.settings.adaptive) return;
    const order: PhysicsQuality[] = ['low', 'balanced', 'high'];
    const index = order.indexOf(this.effectiveQuality);
    const now = performance.now();
    if (this.stepCostMs > 4 && index > 0 && now - this.lastQualityChange > 1500) {
      this.effectiveQuality = order[index - 1];
      this.stepCostMs = 0;
      this.lastQualityChange = now;
      this.fastSamples = 0;
    } else if (this.stepCostMs < 1 && index < order.indexOf(this.settings.quality)) {
      this.fastSamples += 1;
      // Recover slowly after sustained headroom, rather than bouncing between
      // simulation rates on alternate frames after an overload.
      if (this.fastSamples >= 240 && now - this.lastQualityChange > 8000) {
        this.effectiveQuality = order[index + 1]; this.lastQualityChange = now; this.fastSamples = 0;
      }
    } else this.fastSamples = 0;
  }

  private updateCollider(collider: Collider): void {
    collider.bone.updateWorldMatrix(false, false);
    collider.center.copy(collider.localOffset).applyMatrix4(collider.bone.matrixWorld).add(this.frameOffset);
    collider.bone.getWorldQuaternion(this._quat);
    collider.axis.copy(collider.localAxis).applyQuaternion(this._quat);
  }

  /** Penetration depth of a sphere at `point` (radius r) into a collider. */
  private penetration(collider: Collider, point: THREE.Vector3, r: number): number {
    const s = this.worldScale;
    if (collider.shape === 'sphere') {
      return collider.radius * s + r - point.distanceTo(collider.center);
    }
    const t = THREE.MathUtils.clamp(this._a.subVectors(point, collider.center).dot(collider.axis), -collider.halfHeight * s, collider.halfHeight * s);
    this._b.copy(collider.center).addScaledVector(collider.axis, t);
    return collider.radius * s + r - point.distanceTo(this._b);
  }

  private pushOut(collider: Collider, point: THREE.Vector3, r: number): void {
    const s = this.worldScale;
    let closest: THREE.Vector3;
    if (collider.shape === 'sphere') {
      closest = collider.center;
    } else {
      const t = THREE.MathUtils.clamp(this._a.subVectors(point, collider.center).dot(collider.axis), -collider.halfHeight * s, collider.halfHeight * s);
      closest = this._b.copy(collider.center).addScaledVector(collider.axis, t);
    }
    this._a.subVectors(point, closest);
    const distance = this._a.length();
    const min = collider.radius * s + r;
    if (distance >= min) return;
    if (distance < 1e-6) this._a.set(0, 0, 1);
    else this._a.divideScalar(distance);
    point.copy(closest).addScaledVector(this._a, min);
  }

  private simulate(dt: number): void {
    const settings = this.settings;
    const scale = dt * 60;
    const amplitude = THREE.MathUtils.clamp(settings.secondaryMotion, 0, 1);
    const useCollision = settings.collisionQuality !== 'off';
    const primaryOnly = settings.collisionQuality === 'low' || this.effectiveQuality === 'low';
    if (useCollision) for (const collider of this.colliders) this.updateCollider(collider);

    this.windTime += dt;
    if (settings.wind > 0) {
      const gust = 0.55 + 0.45 * Math.sin(this.windTime * 0.7) * Math.sin(this.windTime * 1.9 + 1.3);
      this._wind.copy(this.windDir).multiplyScalar(settings.wind * gust * 0.0035);
    }

    let maxDeviation = 0;
    for (const level of this.levels) {
      for (const node of level) maxDeviation = Math.max(maxDeviation, this.integrate(node, dt, scale, useCollision, primaryOnly));
      if (this.links.length) this.solveSheet(level);
      for (const node of level) this.commit(node, amplitude);
    }
    this.lastMaxDeviation = maxDeviation;
  }

  /**
   * Advance one strand's tail (inertia, spring, gravity, wind, collision,
   * length and angle limit). Leaves the bone rotation for commit(), so sheet
   * links can adjust the tails of a whole level first.
   */
  private integrate(node: Node, dt: number, scale: number, useCollision: boolean, primaryOnly: boolean): number {
    const settings = this.settings;
    const s = this.worldScale;
    void dt;
    {
      if (!this.chainIsEnabled(node.chain)) return 0;
      const bone = node.bone;
      const parent = bone.parent;
      bone.updateWorldMatrix(false, false);
      this._center.setFromMatrixPosition(bone.matrixWorld).add(this.frameOffset);
      if (parent) parent.getWorldQuaternion(this._parentQuat);
      else this._parentQuat.identity();
      this._restDir.copy(node.axis).applyQuaternion(node.animatedRotation).applyQuaternion(this._parentQuat).normalize();
      if (node.pinned) {
        // A user-held bone is authoritative, including its own rotation.
        bone.getWorldQuaternion(this._quat);
        this._restDir.copy(node.axis).applyQuaternion(this._quat).normalize();
        node.currentTail.copy(this._center).addScaledVector(this._restDir, node.length * s);
        node.prevTail.copy(node.currentTail);
        return 0;
      }
      const m = node.material;
      // Lighter tips are springier; heavier roots hold their shape.
      const stiffness = m.stiffness * settings.stiffness * (1.35 - node.massFactor * 0.5);
      const drag = THREE.MathUtils.clamp((m.drag + node.authoredDrag * 0.12) * settings.drag * settings.damping, 0, 0.95);
      const gravity = m.gravity * settings.gravityMultiplier * 0.0016 * (0.5 + node.massFactor);
      const length = node.length * s;

      // Verlet: inertia + spring + gravity (+ wind).
      this._delta.subVectors(node.currentTail, node.prevTail).multiplyScalar((1 - drag) * m.inertia);
      this._next
        .copy(node.currentTail)
        .add(this._delta)
        .addScaledVector(this._restDir, stiffness * length * scale)
        .addScaledVector(GRAVITY_DIR, gravity * length * scale * 9.8);
      if (settings.wind > 0 && !this.softBodyChains.has(node.chain)) this._next.addScaledVector(this._wind, length * scale * 9.8 * (1.2 - node.massFactor * 0.5));

      // Body collision, then re-project to the bone length.
      if (useCollision) {
        const r = node.radius * s;
        for (const collider of node.colliders) {
          if (primaryOnly && !collider.primary) continue;
          this.pushOut(collider, this._next, r);
        }
      }
      this._dir.subVectors(this._next, this._center);
      const len = this._dir.length();
      if (len < 1e-6 || !Number.isFinite(len)) this._dir.copy(this._restDir);
      else this._dir.divideScalar(len);

      if (m.restPull > 0) this._dir.lerp(this._restDir, THREE.MathUtils.clamp(m.restPull * scale, 0, 1)).normalize();

      // Material angle limit around the animated direction.
      const maxAngle = THREE.MathUtils.degToRad(m.maxAngleDeg);
      const angle = Math.acos(THREE.MathUtils.clamp(this._dir.dot(this._restDir), -1, 1));
      if (angle > maxAngle && angle > 1e-6) {
        const t = maxAngle / angle;
        const sinA = Math.sin(angle);
        this._dir
          .multiplyScalar(Math.sin(t * angle) / sinA)
          .addScaledVector(this._restDir, Math.sin((1 - t) * angle) / sinA)
          .normalize();
      }
      this._next.copy(this._center).addScaledVector(this._dir, length);
      node.prevTail.copy(node.currentTail);
      node.currentTail.copy(this._next);
      return Math.min(angle, maxAngle);
    }
  }

  /**
   * Cloth links for one level: strongly resist stretching apart (fabric does
   * not stretch much), only weakly resist pushing together (fabric bunches
   * and folds), then put each tail back on its bone length.
   */
  private solveSheet(level: Node[]): void {
    const inLevel = new Set(level);
    for (let iteration = 0; iteration < 2; iteration++) {
      for (const link of this.links) {
        if (!inLevel.has(link.a) || !inLevel.has(link.b)) continue;
        if (!this.chainEnabled.get(link.a.chain) || !this.chainEnabled.get(link.b.chain)) continue;
        this._a.subVectors(link.b.currentTail, link.a.currentTail);
        const d = this._a.length();
        if (d < 1e-6) continue;
        const stretch = d / link.rest;
        let k = 0;
        if (stretch > 1.04) k = 0.5;
        else if (stretch < 0.55) k = 0.15;
        if (k === 0) continue;
        const target = stretch > 1 ? link.rest * 1.04 : link.rest * 0.55;
        const correction = ((d - target) / d) * k;
        const wa = link.a.pinned ? 0 : link.b.pinned ? 1 : 0.5;
        const wb = link.b.pinned ? 0 : link.a.pinned ? 1 : 0.5;
        link.a.currentTail.addScaledVector(this._a, correction * wa);
        link.b.currentTail.addScaledVector(this._a, -correction * wb);
      }
    }
    // Back onto bone length around each strand's (already final) head.
    for (const node of level) {
      if (!node.fabric || node.pinned) continue;
      node.bone.updateWorldMatrix(false, false);
      this._center.setFromMatrixPosition(node.bone.matrixWorld).add(this.frameOffset);
      this._dir.subVectors(node.currentTail, this._center);
      const len = this._dir.length();
      if (len < 1e-6 || !Number.isFinite(len)) continue;
      node.currentTail.copy(this._center).addScaledVector(this._dir.divideScalar(len), node.length * this.worldScale);
    }
  }

  /** Turn a node's solved tail into its bone rotation. */
  private commit(node: Node, amplitude: number): void {
    if (!this.chainIsEnabled(node.chain) || node.pinned) return;
    if (this.upperBodyMaterials.has(node.chain)) amplitude *= this.upperBodyTuning.strength;
    const bone = node.bone;
    const parent = bone.parent;
    bone.updateWorldMatrix(false, false);
    this._center.setFromMatrixPosition(bone.matrixWorld).add(this.frameOffset);
    if (parent) parent.getWorldQuaternion(this._parentQuat);
    else this._parentQuat.identity();
    this._dir.subVectors(node.currentTail, this._center);
    if (this._dir.lengthSq() < 1e-12) return;
    this._dir.normalize();
    this._invParentQuat.copy(this._parentQuat).invert();
    this._localDir.copy(this._dir).applyQuaternion(this._invParentQuat).normalize();
    this._restDir.copy(node.axis).applyQuaternion(node.animatedRotation).normalize();
    this._quat.setFromUnitVectors(this._restDir, this._localDir);
    // A parent/head can move between simulation ticks. Enforce the same
    // angular constraint on these display-only poses without altering Verlet
    // history or injecting extra velocity into the next solver step.
    const maxAngle = THREE.MathUtils.degToRad(node.material.maxAngleDeg);
    const angle = this._quat.angleTo(IDENTITY_ROTATION);
    if (angle > maxAngle && angle > 1e-6) this._quat.slerp(IDENTITY_ROTATION, 1 - maxAngle / angle);
    this._quat.multiply(node.animatedRotation);
    // Amplitude blends the animated pose and full physics.
    bone.quaternion.copy(node.animatedRotation).slerp(this._quat, amplitude);
    bone.updateWorldMatrix(false, true);
  }

  // ---- diagnostics -----------------------------------------------------------

  /** Mean tail displacement from rest, in model units (used by tests). */
  getAnimatedRotation(boneName: string, target: THREE.Quaternion): boolean {
    const node = this.nodes.find(item => item.bone.name === boneName);
    if (!node) return false;
    target.copy(node.animatedRotation); return true;
  }

  maxAngularDeviation(filter?: (chainId: string) => boolean): number {
    let max = 0;
    for (const node of this.nodes) {
      if (filter && !filter(node.chain)) continue;
      max = Math.max(max, THREE.MathUtils.radToDeg(node.bone.quaternion.angleTo(node.animatedRotation)));
    }
    return max;
  }

  measureDisplacement(filter?: (chainId: string) => boolean): number {
    let sum = 0;
    let count = 0;
    for (const node of this.nodes) {
      if (filter && !filter(node.chain)) continue;
      node.bone.updateWorldMatrix(true, false);
      this._center.setFromMatrixPosition(node.bone.matrixWorld).add(this.frameOffset);
      const rest = this.restTailWorld(node, this._center);
      sum += rest.distanceTo(node.currentTail) / this.worldScale;
      count += 1;
    }
    return count ? sum / count : 0;
  }

  /** All tails finite (no explosion). */
  get healthy(): boolean {
    return this.nodes.every((n) => Number.isFinite(n.currentTail.x) && Number.isFinite(n.currentTail.y) && Number.isFinite(n.currentTail.z));
  }

  /** Line segments for strands and collider centres, for the debug overlay. */
  debugGeometry(): { strands: number[]; colliders: Array<{ center: number[]; radius: number; axis: number[]; halfHeight: number }> } {
    const strands: number[] = [];
    for (const node of this.nodes) {
      this._center.setFromMatrixPosition(node.bone.matrixWorld);
      this._a.copy(node.currentTail).sub(this.frameOffset);
      strands.push(this._center.x, this._center.y, this._center.z, this._a.x, this._a.y, this._a.z);
    }
    return {
      strands,
      colliders: this.colliders.map((c) => ({
        center: [c.center.x - this.frameOffset.x, c.center.y - this.frameOffset.y, c.center.z - this.frameOffset.z],
        radius: c.radius * this.worldScale,
        axis: [c.axis.x, c.axis.y, c.axis.z],
        halfHeight: c.halfHeight * this.worldScale,
      })),
    };
  }

  dispose(): void {
    this.nodes = [];
    this.colliders = [];
    this.simulatedBones.clear();
  }
}
