import assert from 'node:assert/strict';
import test from 'node:test';
import * as THREE from 'three';
import { GrantSolver } from '../src/character/animation/GrantSolver';
import type { PmxModel } from '../src/character/loaders/pmxTypes';

function fixture() {
  const bones = ['driver', 'weighted', 'follower', 'unrelated'].map(name => { const bone = new THREE.Bone(); bone.name = name; return bone; });
  const info = (parentIndex: number, ratio: number) => ({ parentIndex, ratio, affectRotation: true, affectPosition: true, isLocal: false });
  const model = { bones, boneInfos: bones.map(() => ({ transformationClass: 0 })),
    grants: [{ boneIndex: 1, info: info(0, 0.6) }, { boneIndex: 2, info: info(1, 0.5) }] } as unknown as PmxModel;
  return { bones, solver: new GrantSolver(model) };
}
const rotation = (axis: 'x' | 'z', angle: number) => new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(axis === 'x' ? 1 : 0, 0, axis === 'z' ? 1 : 0), angle);

test('authored weighted grants follow physical helper bones without double-applying animation', () => {
  const { bones, solver } = fixture();
  const primary = rotation('z', 0.2), input = rotation('x', 0.1);
  bones[0].quaternion.copy(primary); bones[0].position.y = 0.1;
  bones[1].quaternion.copy(input); bones[1].position.x = 2;
  solver.solve();
  const animated = bones[1].quaternion.clone();
  const physical = rotation('x', 0.05).multiply(primary);
  bones[0].quaternion.copy(physical); bones[0].position.y = 0.15;
  solver.reconcileAfterPhysics(name => name === 'driver');
  const expected = input.clone().multiply(new THREE.Quaternion().slerp(physical, 0.6));
  assert.ok(bones[1].quaternion.angleTo(expected) < 1e-6);
  assert.ok(Math.abs(bones[1].position.y - 0.09) < 1e-6);
  assert.equal(bones[1].position.x, 2);
  assert.ok(animated.angleTo(expected) > 0.01);
});

test('post-physics grants are idempotent and propagate through authored grant chains', () => {
  const { bones, solver } = fixture(); solver.solve();
  bones[0].quaternion.copy(rotation('z', 0.08));
  for (let frame = 0; frame < 5; frame++) {
    solver.reconcileAfterPhysics(name => name === 'driver');
    assert.ok(bones[1].quaternion.angleTo(rotation('z', 0.048)) < 1e-6);
    assert.ok(bones[2].quaternion.angleTo(rotation('z', 0.024)) < 1e-6);
  }
});

test('post-physics propagation preserves separately simulated targets and unrelated poses', () => {
  const { bones, solver } = fixture(); solver.solve();
  const ownPhysics = rotation('x', 0.4), unrelated = rotation('z', 0.3);
  bones[0].quaternion.copy(rotation('z', 0.08)); bones[1].quaternion.copy(ownPhysics); bones[3].quaternion.copy(unrelated);
  solver.reconcileAfterPhysics(name => name === 'driver' || name === 'weighted');
  assert.ok(bones[1].quaternion.angleTo(ownPhysics) < 1e-6);
  assert.ok(bones[3].quaternion.angleTo(unrelated) < 1e-6);
});

test('grant diagnostics retain the primary pose and identify indirect deformation bones', () => {
  const { bones, solver } = fixture(); bones[0].quaternion.copy(rotation('z', 0.1)); solver.solve();
  const animated = bones[1].quaternion.clone();
  bones[0].quaternion.copy(rotation('z', 0.15)); solver.reconcileAfterPhysics(name => name === 'driver');
  const captured = new THREE.Quaternion();
  assert.equal(solver.getAnimatedTransform('weighted', captured), true);
  assert.ok(captured.angleTo(animated) < 1e-6);
  assert.deepEqual(solver.dependentBoneNames(['driver']), ['weighted', 'follower']);
  assert.equal(solver.getAnimatedTransform('missing', captured), false);
});
