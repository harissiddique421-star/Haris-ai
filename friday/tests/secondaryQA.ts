import * as THREE from 'three';
import { CompanionActor } from '../src/companion/CompanionActor';
import type { CharacterSystem } from '../src/character/core/CharacterSystem';
import { PHYSICS_MATERIALS } from '../shared/character/secondary';
import type { MorphMap } from '../src/character/config/types';

export class SecondarySceneQA {
  private readonly actor: CompanionActor;
  private readonly chestIds: Set<string>;
  private readonly chestBones: THREE.Bone[];
  private readonly affected: number[];
  private readonly saved: THREE.Quaternion[];
  private readonly savedPositions: THREE.Vector3[];
  private readonly actual: Float32Array;
  private readonly point = new THREE.Vector3();
  private walkSpeed = 0;
  private direction = 0;
  private readonly visemeIndices: number[];
  readonly inventory: Record<string, unknown>;

  constructor(private readonly system: CharacterSystem, level: 'low' | 'balanced' | 'high', morphs: MorphMap) {
    const model = system.loadedModel!;
    const chest = system.rig!.chains.filter(chain => chain.class === 'chest');
    const declared = [...new Set(chest.flatMap(chain => chain.bones))];
    const simulated = declared.filter(name => system.secondaryMotion!.isSimulated(name));
    this.chestIds = new Set(chest.map(chain => chain.id));
    const granted = system.grantSolver!.dependentBoneNames(simulated).filter(name => !system.secondaryMotion!.isSimulated(name));
    this.chestBones = [...simulated, ...granted].map(name => model.bones[model.boneIndexByName.get(name)!]);
    const affectedBones = new Set<number>();
    for (const bone of this.chestBones) bone.traverse(child => {
      const index = model.boneIndexByName.get(child.name); if (index !== undefined) affectedBones.add(index);
    });
    const indices = model.mesh.geometry.getAttribute('skinIndex');
    const weights = model.mesh.geometry.getAttribute('skinWeight');
    const influenced: number[] = [];
    for (let vertex = 0; vertex < indices.count; vertex++) {
      for (let slot = 0; slot < 4; slot++) if (affectedBones.has(indices.getComponent(vertex, slot)) && weights.getComponent(vertex, slot) > 0.001) {
        influenced.push(vertex); break;
      }
    }
    const stride = Math.max(1, Math.ceil(influenced.length / 384));
    this.affected = influenced.filter((_, index) => index % stride === 0);
    this.saved = this.chestBones.map(() => new THREE.Quaternion());
    this.savedPositions = this.chestBones.map(() => new THREE.Vector3());
    this.actual = new Float32Array(this.affected.length * 3);
    this.visemeIndices = Object.entries(morphs).filter(([key]) => key.startsWith('viseme')).map(([, name]) => model.vertexMorphs.get(name || '')?.morphTargetIndex).filter((index): index is number => index !== undefined);
    this.inventory = { declaredChestGroups: chest.length, declaredChestBones: declared.length,
      simulatedChestBones: simulated.length, skippedBones: declared.filter(name => !simulated.includes(name)),
      grantDrivenBones: granted.length,
      viewport: { ...system.stage.size },
      weightedVertices: influenced.length, sampledVertices: this.affected.length, preset: level,
      configuredMaxAngleDeg: Math.max(0, ...chest.map(chain => chain.overrides?.maxAngleDeg ?? PHYSICS_MATERIALS[chain.material].maxAngleDeg)),
      mappedVertexVisemes: this.visemeIndices.length,
      comparison: 'Current skinned mesh versus captured primary animated rotations, with secondary offsets removed at the same frame; original assets unchanged.' };
    system.stop(); system.setIdleBehaviours(false); system.stopBehaviour();
    system.setFrameInput({ activity: 'idle', emotion: 'idle' });
    system.setPhysicsSettings({ quality: level, adaptive: false, wind: 0 });
    system.frameCompanion({ top: 0.16, width: 0.34, floorPx: 8 });
    this.actor = new CompanionActor(system, { contact: () => {}, metrics: metrics => { this.walkSpeed = metrics.walkSpeed; }, done: () => {} });
    this.actor.ready();
  }

  private captureFrames() {
    const stage = this.system.stage, camera = stage.camera;
    const width = stage.size.width, height = stage.size.height;
    stage.render();
    const full = stage.renderer.domElement.toDataURL('image/png');
    const chest = this.system.boneMap.upperBody2 || this.system.boneMap.upperBody;
    const model = this.system.loadedModel!;
    const index = model.boneIndexByName.get(chest);
    if (index === undefined) return { full, detail: '' };
    const center = model.bones[index].getWorldPosition(this.point).project(camera);
    const side = Math.min(width, height) * 0.42;
    const x = Math.max(0, Math.min(width - side, (center.x * 0.5 + 0.5) * width - side / 2));
    const y = Math.max(0, Math.min(height - side, (-center.y * 0.5 + 0.5) * height - side * 0.38));
    let detail = '';
    try {
      camera.setViewOffset(width, height, x, y, side, side);
      stage.renderer.setSize(512, 512, false); stage.render();
      detail = stage.renderer.domElement.toDataURL('image/png');
    } finally {
      camera.clearViewOffset(); stage.renderer.setSize(width, height, false); stage.render();
    }
    return { full, detail };
  }

  private meshDifference(): number {
    const model = this.system.loadedModel!;
    const mesh = model.mesh;
    mesh.updateMatrixWorld(true); model.skeleton.update();
    for (let i = 0; i < this.affected.length; i++) {
      mesh.getVertexPosition(this.affected[i], this.point); mesh.localToWorld(this.point);
      this.point.toArray(this.actual, i * 3);
    }
    this.chestBones.forEach((bone, index) => {
      this.saved[index].copy(bone.quaternion); this.savedPositions[index].copy(bone.position);
      if (!this.system.secondaryMotion!.getAnimatedRotation(bone.name, bone.quaternion)) this.system.grantSolver!.getAnimatedTransform(bone.name, bone.quaternion, bone.position);
    });
    let max = 0;
    try {
      mesh.updateMatrixWorld(true); model.skeleton.update();
      for (let i = 0; i < this.affected.length; i++) {
        mesh.getVertexPosition(this.affected[i], this.point); mesh.localToWorld(this.point);
        max = Math.max(max, Math.hypot(this.point.x - this.actual[i * 3], this.point.y - this.actual[i * 3 + 1], this.point.z - this.actual[i * 3 + 2]));
      }
    } finally {
      this.chestBones.forEach((bone, index) => { bone.quaternion.copy(this.saved[index]); bone.position.copy(this.savedPositions[index]); });
      mesh.updateMatrixWorld(true); model.skeleton.update();
    }
    return max / this.system.worldHeight;
  }

  async run(phase: string) {
    const system = this.system;
    if (phase === 'upperOff') system.setUpperBodyPhysics({ enabled: false });
    if (phase === 'upperZero') system.setUpperBodyPhysics({ strength: 0 });
    if (phase === 'upperLimited') system.setUpperBodyPhysics({ maxAngleDeg: 2, dampingMultiplier: 1.5 });
    if (phase === 'upperReset') system.setUpperBodyPhysics(undefined);
    if (['upperOff', 'upperZero', 'upperLimited'].includes(phase)) { this.actor.handle({ act: 'walk', dir: 1 }); this.direction = 1; }
    if (phase === 'upperReset') { this.actor.handle({ act: 'stop' }); this.actor.handle({ act: 'stand' }); this.direction = 0; }
    system.setFrameInput({ activity: phase === 'listen' ? 'listening' : phase === 'speak' ? 'talking' : 'idle', emotion: 'idle' });
    if (phase === 'rest') { this.actor.handle({ act: 'stand' }); this.direction = 0; }
    if (phase === 'walk') { this.actor.handle({ act: 'walk', dir: 1 }); this.direction = 1; }
    if (phase === 'turn') { this.actor.handle({ act: 'walk', dir: -1 }); this.direction = -1; }
    if (phase === 'stop' || phase === 'settle') { this.actor.handle({ act: 'stop' }); this.direction = 0; }
    if (phase === 'settle') system.stopBehaviour();
    if (phase === 'sit') { this.actor.handle({ act: 'sit', style: 'handsOnKnees', side: 1 }); this.direction = 0; }
    if (phase === 'stand') { this.actor.handle({ act: 'stand' }); this.direction = 0; }
    if (phase === 'dance') { this.actor.handle({ act: 'perform', name: 'dance' }); this.direction = 0; }
    if (phase === 'wave') { this.actor.handle({ act: 'perform', name: 'wave' }); this.direction = 0; }
    if (['listen', 'speak', 'idle', 'teleport', 'longStall'].includes(phase)) { this.actor.handle({ act: 'stop' }); system.stopBehaviour(); this.direction = 0; }
    if (phase === 'teleport') { this.actor.onMoved(10_000, 10_000, 'teleport'); system.addScreenMotion(10_000, 10_000); system.rebasePhysics(); }
    if (phase === 'longStall') system.stepFrames(1, 2);
    system.setIdleBehaviours(false);
    const frames = phase === 'settle' ? 300 : 180;
    let maxDeviation = 0, peakDisplacement = 0, meshDelta = 0, finite = true, capture = '', detailCapture = '', peakViseme = 0, finalViseme = 0;
    for (let frame = 0; frame < frames; frame++) {
      if (phase === 'rapid') {
        if (frame === 0) this.actor.handle({ act: 'perform', name: 'wave' });
        if (frame === 40) this.actor.handle({ act: 'sit', style: 'handsOnKnees', side: 1 });
        if (frame === 80) this.actor.handle({ act: 'stand' });
        if (frame === 120) this.actor.handle({ act: 'perform', name: 'dance' });
        system.setIdleBehaviours(false);
      }
      const dx = this.walkSpeed * this.direction / 60;
      if (this.direction) this.actor.onMoved(dx, 0, 'walk');
      system.addScreenMotion(dx, 0);
      system.stepFrames(1);
      for (const bone of system.loadedModel!.bones) if (![...bone.position, ...bone.quaternion].every(Number.isFinite)) finite = false;
      maxDeviation = Math.max(maxDeviation, system.secondaryMotion!.maxAngularDeviation(id => this.chestIds.has(id)));
      peakDisplacement = Math.max(peakDisplacement, system.secondaryMotion!.measureDisplacement(id => this.chestIds.has(id)));
      finalViseme = Math.max(0, ...this.visemeIndices.map(index => system.loadedModel!.mesh.morphTargetInfluences?.[index] || 0));
      peakViseme = Math.max(peakViseme, finalViseme);
      if (frame % 15 === 0) {
        const difference = this.meshDifference();
        if (!capture || difference > meshDelta) {
          meshDelta = Math.max(meshDelta, difference); const images = this.captureFrames(); capture = images.full; detailCapture = images.detail;
        }
        // Yield between small batches rather than monopolizing a display thread.
        await new Promise(resolve => setTimeout(resolve, 16));
      }
    }
    const finalImages = this.captureFrames();
    return { phase, finite, healthy: system.secondaryMotion!.healthy, maxDeviationDeg: maxDeviation,
      peakDisplacementModelUnits: peakDisplacement, finalDisplacementModelUnits: system.secondaryMotion!.measureDisplacement(id => this.chestIds.has(id)),
      maxMeshDisplacementHeightRatio: meshDelta, peakVisemeWeight: peakViseme, finalVisemeWeight: finalViseme,
      physics: system.secondaryMotion!.stats, seat: system.seat,
      upperBodyTuning: system.diagnostics.upperBodyPhysics,
      captureScope: 'Peak sampled secondary mesh displacement; final pose captured separately.',
      capture, detailCapture, finalCapture: finalImages.full };
  }

  dispose() { this.actor.dispose(); }
}
