/**
 * PMX grant ("append parent" / 付与親) solver.
 *
 * A granted bone inherits a fraction of another bone's *animated* local
 * rotation. MMD rigs lean on this heavily and this model is no exception:
 *
 *   - 左目 / 右目 both inherit 両目 at ratio 1.0, which is the only reason
 *     the standard eye-control bone moves the eyes at all,
 *   - 左肩C inherits 左肩P at ratio -1 to cancel shoulder roll,
 *   - the arm and wrist twist bones (腕捩 / 手捩) distribute forearm twist.
 *
 * Without this the gaze system would silently do nothing and the arms would
 * deform badly whenever they are posed.
 *
 * Only the delta from the rest pose is inherited, never the rest pose itself,
 * so a granted bone at rest stays exactly at rest.
 */
import * as THREE from 'three';
import type { PmxModel } from '../loaders/pmxTypes';

interface GrantEntry {
  bone: THREE.Bone;
  source: THREE.Bone;
  sourceRest: THREE.Quaternion;
  sourceRestPosition: THREE.Vector3;
  ratio: number;
  affectRotation: boolean;
  affectPosition: boolean;
  inputRotation: THREE.Quaternion;
  inputPosition: THREE.Vector3;
  sourceFrameRotation: THREE.Quaternion;
  sourceFramePosition: THREE.Vector3;
  animatedRotation: THREE.Quaternion;
  animatedPosition: THREE.Vector3;
}

export class GrantSolver {
  private readonly entries: GrantEntry[] = [];

  private readonly _delta = new THREE.Quaternion();
  private readonly _scaled = new THREE.Quaternion();
  private readonly _identity = new THREE.Quaternion();
  private readonly _offset = new THREE.Vector3();
  private readonly changed = new Set<string>();
  private hasFrame = false;

  constructor(model: PmxModel) {
    // Solve in the model's declared deform order, then by bone index, so a
    // chain of grants resolves in one pass.
    const sorted = [...model.grants].sort((a, b) => {
      const ca = model.boneInfos[a.boneIndex]?.transformationClass ?? 0;
      const cb = model.boneInfos[b.boneIndex]?.transformationClass ?? 0;
      return ca !== cb ? ca - cb : a.boneIndex - b.boneIndex;
    });

    for (const { boneIndex, info } of sorted) {
      const bone = model.bones[boneIndex];
      const source = model.bones[info.parentIndex];
      if (!bone || !source) continue;
      if (!info.affectRotation && !info.affectPosition) continue;

      this.entries.push({
        bone,
        source,
        sourceRest: source.quaternion.clone(),
        sourceRestPosition: source.position.clone(),
        ratio: info.ratio,
        affectRotation: info.affectRotation,
        affectPosition: info.affectPosition,
        inputRotation: bone.quaternion.clone(), inputPosition: bone.position.clone(),
        sourceFrameRotation: source.quaternion.clone(), sourceFramePosition: source.position.clone(),
        animatedRotation: bone.quaternion.clone(), animatedPosition: bone.position.clone(),
      });
    }
  }

  get count(): number {
    return this.entries.length;
  }

  /**
   * Apply every grant. Run after the pose layers and bone morphs have set
   * bone transforms, but before world matrices are recomputed.
   */
  solve(): void {
    for (const entry of this.entries) {
      entry.inputRotation.copy(entry.bone.quaternion); entry.inputPosition.copy(entry.bone.position);
      entry.sourceFrameRotation.copy(entry.source.quaternion); entry.sourceFramePosition.copy(entry.source.position);
      this.apply(entry);
      entry.animatedRotation.copy(entry.bone.quaternion); entry.animatedPosition.copy(entry.bone.position);
    }
    this.hasFrame = true;
  }

  private apply(entry: GrantEntry): void {
    const ratio = entry.ratio;
    if (ratio === 0) return;

    if (entry.affectRotation) {
      // Delta = how far the source has moved from ITS rest pose.
      this._delta.copy(entry.sourceRest).invert().multiply(entry.source.quaternion);

      const magnitude = Math.abs(ratio);
      if (ratio < 0) this._delta.invert();

      if (magnitude >= 0.999) {
        entry.bone.quaternion.multiply(this._delta);
      } else {
        this._scaled.copy(this._identity).slerp(this._delta, magnitude);
        entry.bone.quaternion.multiply(this._scaled);
      }
    }

    if (entry.affectPosition) {
      this._offset.subVectors(entry.source.position, entry.sourceRestPosition);
      entry.bone.position.addScaledVector(this._offset, ratio);
    }
  }

  /**
   * Authored physics helpers may drive weighted bones outside their hierarchy.
   * Rebuild only those grants from this frame's pre-grant input. Applying all
   * grants a second time would double animation/morph contributions and drift.
   * Independently simulated targets retain their own final physical pose.
   */
  reconcileAfterPhysics(isSimulated: (boneName: string) => boolean): number {
    if (!this.hasFrame) return 0;
    this.changed.clear();
    let count = 0;
    for (const entry of this.entries) {
      if (isSimulated(entry.bone.name)) continue;
      const changedSource = this.changed.has(entry.source.name) || (isSimulated(entry.source.name) && (
        (entry.affectRotation && entry.source.quaternion.angleTo(entry.sourceFrameRotation) > 1e-7) ||
        (entry.affectPosition && entry.source.position.distanceToSquared(entry.sourceFramePosition) > 1e-14)));
      if (!changedSource) continue;
      entry.bone.quaternion.copy(entry.inputRotation); entry.bone.position.copy(entry.inputPosition);
      this.apply(entry);
      this.changed.add(entry.bone.name); count++;
    }
    return count;
  }

  dependentBoneNames(sources: Iterable<string>): string[] {
    const initial = new Set(sources), affected = new Set(initial);
    for (let pass = 0; pass < this.entries.length; pass++) {
      const before = affected.size;
      for (const entry of this.entries) if (affected.has(entry.source.name)) affected.add(entry.bone.name);
      if (affected.size === before) break;
    }
    return [...affected].filter(name => !initial.has(name));
  }

  getAnimatedTransform(name: string, rotation: THREE.Quaternion, position?: THREE.Vector3): boolean {
    const entry = this.entries.find(item => item.bone.name === name);
    if (!entry) return false;
    rotation.copy(entry.animatedRotation);
    if (position) position.copy(entry.animatedPosition);
    return true;
  }

  /** Bones written to by grants, so the pose buffer can reset them. */
  get affectedBoneNames(): string[] {
    return this.entries.map((e) => e.bone.name);
  }
}
