import assert from 'node:assert/strict';
import test from 'node:test';
import * as THREE from 'three';
import { SecondaryMotion } from '../src/character/physics/SecondaryMotion';
import type { PmxModel } from '../src/character/loaders/pmxTypes';

/** Mathematical chain fixture, not a character model or replacement asset. */
function chain() {
  const mesh = new THREE.Group();
  const anchor = new THREE.Bone(); anchor.name = 'torso';
  const soft = new THREE.Bone(); soft.name = 'secondary';
  const tip = new THREE.Bone(); tip.name = 'tip'; tip.position.y = 1;
  mesh.add(anchor); anchor.add(soft); soft.add(tip); mesh.updateMatrixWorld(true);
  const bones = [anchor, soft, tip];
  const model = { mesh, bones, rigidBodies: [], boneIndexByName: new Map(bones.map((bone, index) => [bone.name, index])),
    boneInfos: bones.map((bone, index) => ({ index, name: bone.name, parentIndex: index - 1,
      position: index === 2 ? new THREE.Vector3(0, 1, 0) : new THREE.Vector3(), flag: 0, transformationClass: 0 })) } as unknown as PmxModel;
  const physics = new SecondaryMotion(model, [{ id: 'chest:test', label: 'Test', class: 'chest', material: 'SOFT_BODY',
    bones: ['secondary'], anchor: 'torso', source: 'rigid-bodies', classifiedBy: 'name', enabled: true }], [], {});
  physics.setSettings({ quality: 'low', adaptive: false, gravityMultiplier: 0 });
  physics.reset();
  return { mesh, anchor, soft, physics };
}

test('30 Hz secondary pose survives display frames without a physics substep', () => {
  const { mesh, anchor, soft, physics } = chain();
  anchor.position.x = 0.15; mesh.updateMatrixWorld(true);
  physics.update(1 / 30);
  const simulated = soft.quaternion.clone();
  assert.ok(simulated.angleTo(new THREE.Quaternion()) > 0.01, 'chain responds to actual anchor acceleration');
  // Animation/grants restore their pose at every display frame.
  soft.quaternion.identity(); mesh.updateMatrixWorld(true);
  physics.update(1 / 120);
  assert.ok(soft.quaternion.angleTo(simulated) < 0.001, 'a non-step frame must retain the physical pose');
});

test('display-only secondary poses retain angular limits after abrupt valid movement', () => {
  const { mesh, anchor, soft, physics } = chain();
  physics.update(1 / 30);
  anchor.position.x = 10; soft.quaternion.identity(); mesh.updateMatrixWorld(true);
  physics.update(1 / 120);
  assert.ok(soft.quaternion.angleTo(new THREE.Quaternion()) <= THREE.MathUtils.degToRad(6) + 1e-6);
  assert.ok([...soft.quaternion].every(Number.isFinite));
});

test('pinned secondary bones preserve the user pose during solver and display-only frames', () => {
  const { mesh, soft, physics } = chain();
  physics.setPinned(soft.name, true);
  const held = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, 1), 0.5);
  for (const delta of [1 / 30, 1 / 120]) {
    soft.quaternion.copy(held); mesh.updateMatrixWorld(true); physics.update(delta);
    assert.ok(soft.quaternion.angleTo(held) < 1e-6);
  }
});

test('secondary response settles without display-frame resets or long-stall explosions', () => {
  const { mesh, anchor, soft, physics } = chain();
  anchor.position.x = 0.15; mesh.updateMatrixWorld(true); physics.update(1 / 30);
  for (let i = 0; i < 480; i++) {
    soft.quaternion.identity(); mesh.updateMatrixWorld(true); physics.update(1 / 120);
  }
  assert.ok(soft.quaternion.angleTo(new THREE.Quaternion()) < 0.002);
  physics.update(2);
  assert.equal(physics.healthy, true);
});

test('secondary physics preserves the primary animated rotation and twist', () => {
  for (const axis of [new THREE.Vector3(0, 0, 1), new THREE.Vector3(0, 1, 0)]) {
    const { mesh, soft, physics } = chain();
    const animated = new THREE.Quaternion().setFromAxisAngle(axis, 0.4);
    soft.quaternion.copy(animated); mesh.updateMatrixWorld(true); physics.reset();
    for (let frame = 0; frame < 6; frame++) {
      soft.quaternion.copy(animated); mesh.updateMatrixWorld(true); physics.update(1 / 30);
      assert.ok(soft.quaternion.angleTo(animated) < 0.002, 'physics is an offset from the animated pose, including its twist');
    }
  }
});

test('upper-body off and zero strength preserve animation while re-enabling remains bounded', () => {
  for (const tuning of [{ enabled: false }, { strength: 0 }]) {
    const { mesh, anchor, soft, physics } = chain();
    physics.setUpperBodySettings(tuning); anchor.position.x = 0.2;
    for (let i = 0; i < 12; i++) { soft.quaternion.identity(); mesh.updateMatrixWorld(true); physics.update(1 / 60); }
    assert.ok(soft.quaternion.angleTo(new THREE.Quaternion()) < 1e-6);
    physics.setUpperBodySettings({ enabled: true, strength: 1, maxAngleDeg: 2 });
    for (let i = 0; i < 12; i++) {
      anchor.position.x += 0.03; soft.quaternion.identity(); mesh.updateMatrixWorld(true); physics.update(1 / 60);
      assert.ok(soft.quaternion.angleTo(new THREE.Quaternion()) <= THREE.MathUtils.degToRad(2) + 1e-6);
    }
    assert.equal(physics.healthy, true);
  }
});

test('wind does not generate arbitrary soft-body motion on a stationary rig', () => {
  const { mesh, soft, physics } = chain();
  physics.setSettings({ wind: 1 });
  for (let i = 0; i < 180; i++) { soft.quaternion.identity(); mesh.updateMatrixWorld(true); physics.update(1 / 60); }
  assert.ok(soft.quaternion.angleTo(new THREE.Quaternion()) < 1e-6);
});
