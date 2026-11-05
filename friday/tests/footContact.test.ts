import assert from 'node:assert/strict';
import test from 'node:test';
import * as THREE from 'three';
import { FootContact } from '../src/companion/FootContact';
import { createLimb } from '../src/character/editor/IkSolver';
import { CharacterSystem } from '../src/character/core/CharacterSystem';

function fixture(facing = 1, scale = 0.2) {
  const root = new THREE.Group(), mesh = new THREE.Group(); root.add(mesh); root.scale.setScalar(scale);
  root.rotation.y = facing < 0 ? Math.PI : 0;
  const hips = [new THREE.Bone(), new THREE.Bone()];
  const knees = hips.map(() => new THREE.Bone()), feet = hips.map(() => new THREE.Bone());
  hips.forEach((hip, i) => {
    hip.name = `hip${i}`; hip.position.set(i === 0 ? -0.5 : 0.5, 5, 0); mesh.add(hip);
    knees[i].name = `knee${i}`; knees[i].position.y = -2.3; hip.add(knees[i]);
    feet[i].name = `foot${i}`; feet[i].position.y = -2.3; knees[i].add(feet[i]);
  });
  root.updateMatrixWorld(true);
  const limbs = hips.map((hip, i) => createLimb(hip, knees[i], feet[i], 'leg', new THREE.Vector3(0, 0, 1)));
  const bones = [...hips, ...knees, ...feet];
  const camera = new THREE.PerspectiveCamera(35, 300 / 560, 0.1, 100);
  camera.position.set(0, 1, 3); camera.lookAt(0, 1, 0); camera.updateMatrixWorld(true);
  const pose = { userWeight: 0, restOf: () => ({ quaternion: new THREE.Quaternion() }) };
  const system: any = { root, loadedModel: { mesh, bones, boneIndexByName: new Map(bones.map((bone, i) => [bone.name, i])),
    boneInfos: bones.map(bone => ({ position: mesh.worldToLocal(bone.getWorldPosition(new THREE.Vector3())) })) },
    stage: { size: { width: 300, height: 560 }, camera, renderer: { domElement: { clientWidth: 300, clientHeight: 560 } } },
    poseBuffer: pose, poseEditor: { locomotionLimb: (side: string) => limbs[side === 'LEFT' ? 0 : 1] }, frontSign: facing, worldHeight: 10 * scale,
    worldToCanvas: CharacterSystem.prototype.worldToCanvas, canvasToWorld: CharacterSystem.prototype.canvasToWorld };
  const contact = FootContact.create(system)!;
  function primary() {
    bones.forEach(bone => bone.quaternion.identity()); hips.forEach(hip => hip.position.y = 5 - 4.6 * 0.07);
    root.updateMatrixWorld(true);
  }
  return { root, hips, knees, feet, pose, system, contact, primary };
}

for (const facing of [1, -1]) test(`planted feet follow actual desktop travel on a turned scaled rig (facing ${facing})`, () => {
  const f = fixture(facing, facing === 1 ? 0.2 : 0.12);
  f.root.rotation.y += 0.9; f.primary();
  f.contact.apply(0.2, 1, 0.1, 0.04);
  const first = f.feet.map(foot => f.system.worldToCanvas(foot.getWorldPosition(new THREE.Vector3())).x);
  let windowX = 0;
  for (let frame = 0; frame < 12; frame++) {
    windowX += 0.7; f.contact.moved(0.7, 0, 'walk'); f.primary(); f.contact.apply(0.2 + frame * 0.004, 1, 0.1, 0.04);
    f.feet.forEach((foot, i) => assert.ok(Math.abs(f.system.worldToCanvas(foot.getWorldPosition(new THREE.Vector3())).x + windowX - first[i]) < 0.02));
  }
  assert.ok(f.contact.diagnostics.feet.every(foot => foot.planted && foot.errorWorld < 1e-5));
});

test('contact never changes a user-owned limb and discards stale anchors after teleport', () => {
  const f = fixture(); f.primary(); f.pose.userWeight = 0.5;
  const rotations = [...f.hips, ...f.knees, ...f.feet].map(bone => bone.quaternion.clone());
  f.contact.apply(0.2, 1, 0.1, 0.04);
  [...f.hips, ...f.knees, ...f.feet].forEach((bone, i) => assert.ok(bone.quaternion.equals(rotations[i])));
  assert.equal(f.contact.diagnostics.active, false);
  f.pose.userWeight = 0; f.contact.apply(0.2, 1, 0.1, 0.04); assert.equal(f.contact.diagnostics.active, true);
  f.contact.moved(10000, 0, 'teleport'); assert.equal(f.contact.diagnostics.active, false);
  f.primary(); f.contact.apply(0.2, 1, 0.1, 0.04);
  assert.ok(f.contact.diagnostics.feet.every(foot => foot.errorWorld < 1e-5));
});

test('entry/exit blend changes the solved pose continuously and rejects nonfinite input', () => {
  const f = fixture(); f.primary(); f.contact.apply(0.2, 1, 0.1, 0.04);
  const full = f.knees[0].quaternion.angleTo(new THREE.Quaternion());
  f.primary(); f.contact.reset(); f.contact.apply(0.2, 0.5, 0.1, 0.04);
  const half = f.knees[0].quaternion.angleTo(new THREE.Quaternion());
  assert.ok(full > 0.1 && Math.abs(half / full - 0.5) < 0.001);
  f.primary(); f.contact.apply(0.2, 0, 0.1, 0.04); assert.ok(f.knees[0].quaternion.equals(new THREE.Quaternion()));
  f.contact.apply(NaN, 1, 0.1, 0.04); assert.equal(f.contact.diagnostics.active, false);
  assert.ok(f.knees[0].quaternion.toArray().every(Number.isFinite));
});

test('a rig without both complete leg chains uses the existing fallback', () => {
  assert.equal(FootContact.create({ poseEditor: { locomotionLimb: () => null } } as any), null);
});
