import * as THREE from 'three';
import type { CharacterSystem } from '../character/core/CharacterSystem';
import { solveLimb, type LimbChain } from '../character/editor/IkSolver';

export const WALK_STANCE = 0.6;
interface Foot {
  limb: LimbChain;
  neutral: THREE.Vector3;
  rotation: THREE.Quaternion;
  anchor: THREE.Vector3;
  takeoff: THREE.Vector3;
  planted: boolean | null;
  orientation: THREE.Quaternion;
  saved: THREE.Quaternion[];
  error: number;
}

/** Holds stance feet in desktop coordinates using actual shell movement. */
export class FootContact {
  private readonly feet: Foot[];
  private active = false;
  private readonly forward = new THREE.Vector3();
  private readonly target = new THREE.Vector3();
  private readonly pole = new THREE.Vector3();
  private readonly rotation = new THREE.Quaternion();

  static create(system: CharacterSystem): FootContact | null {
    const left = system.poseEditor?.locomotionLimb('LEFT');
    const right = system.poseEditor?.locomotionLimb('RIGHT');
    return left && right && system.root ? new FootContact(system, [left, right]) : null;
  }

  private constructor(private readonly system: CharacterSystem, limbs: LimbChain[]) {
    const root = system.root!, model = system.loadedModel!;
    root.updateMatrixWorld(true);
    this.feet = limbs.map(limb => {
      const index = model.boneIndexByName.get(limb.end.name)!;
      const neutral = root.worldToLocal(model.boneInfos[index].position.clone().applyMatrix4(model.mesh.matrixWorld));
      const hierarchy: THREE.Object3D[] = [];
      for (let item: THREE.Object3D | null = limb.end; item && item !== root; item = item.parent) hierarchy.unshift(item);
      const rotation = new THREE.Quaternion();
      for (const item of hierarchy) rotation.multiply(system.poseBuffer?.restOf(item.name)?.quaternion ?? item.quaternion);
      return { limb, neutral, rotation, anchor: new THREE.Vector3(), takeoff: new THREE.Vector3(), planted: null,
        orientation: new THREE.Quaternion(), saved: [new THREE.Quaternion(), new THREE.Quaternion(), new THREE.Quaternion()], error: 0 };
    });
  }

  reset(): void {
    this.active = false;
    for (const foot of this.feet) { foot.planted = null; foot.error = 0; }
  }

  moved(dx: number, dy: number, kind: string): void {
    if (kind !== 'walk' || !Number.isFinite(dx + dy) || Math.abs(dx) > this.system.stage.size.width * 0.5) { this.reset(); return; }
    if (!this.active) return;
    for (const foot of this.feet) for (const point of [foot.anchor, foot.takeoff]) {
      const canvas = this.system.worldToCanvas(point);
      point.copy(this.system.canvasToWorld(canvas.x - dx, canvas.y - dy, point));
    }
  }

  apply(phase: number, weight: number, halfStride: number, lift: number): void {
    const root = this.system.root;
    // Edited poses keep authority, including while their weights ease out.
    if (!root || !Number.isFinite(phase + weight + halfStride + lift) || weight < 0.002 || (this.system.poseBuffer?.userWeight ?? 0) > 0.001) { this.reset(); return; }
    this.active = true;
    root.updateMatrixWorld(true);
    this.forward.set(0, 0, this.system.frontSign).transformDirection(root.matrixWorld);
    const rootRotation = root.getWorldQuaternion(this.rotation);
    for (let index = 0; index < this.feet.length; index++) {
      const foot = this.feet[index];
      const cycle = ((phase / (Math.PI * 2) + index * 0.5) % 1 + 1) % 1;
      const planted = cycle < WALK_STANCE;
      this.target.copy(foot.neutral).applyMatrix4(root.matrixWorld).addScaledVector(this.forward, halfStride);
      const desiredRotation = rootRotation.clone().multiply(foot.rotation);
      if (foot.planted === null) {
        foot.anchor.copy(this.target).addScaledVector(this.forward, -2 * halfStride * Math.min(1, cycle / WALK_STANCE));
        foot.takeoff.copy(foot.anchor); foot.orientation.copy(desiredRotation);
      } else if (planted && !foot.planted) {
        foot.anchor.copy(this.target); foot.orientation.copy(desiredRotation);
      } else if (!planted && foot.planted) foot.takeoff.copy(foot.anchor);
      if (planted) this.target.copy(foot.anchor);
      else {
        const progress = (cycle - WALK_STANCE) / (1 - WALK_STANCE);
        const smooth = progress * progress * (3 - 2 * progress);
        this.target.lerpVectors(foot.takeoff, this.target, smooth);
        this.target.y += lift * Math.sin(Math.PI * progress) ** 2;
        foot.orientation.slerp(desiredRotation, smooth);
      }
      foot.planted = planted;
      const { root: hip, mid: knee, end: ankle } = foot.limb;
      foot.saved[0].copy(hip.quaternion); foot.saved[1].copy(knee.quaternion); foot.saved[2].copy(ankle.quaternion);
      this.pole.copy(hip.getWorldPosition(this.pole)).addScaledVector(this.forward, this.system.worldHeight * 0.5);
      const solved = solveLimb(foot.limb, this.target, this.pole, foot.orientation);
      foot.error = solved.error;
      [hip, knee, ankle].forEach((bone, i) => bone.quaternion.slerp(foot.saved[i], 1 - THREE.MathUtils.clamp(weight, 0, 1)));
      hip.updateWorldMatrix(true, true);
    }
  }

  get diagnostics() {
    return { active: this.active, feet: this.feet.map(foot => ({ planted: this.active && foot.planted === true, errorWorld: foot.error })) };
  }
}
