import assert from 'node:assert/strict';
import test from 'node:test';
import * as THREE from 'three';
import { CharacterSystem } from '../src/character/core/CharacterSystem';

test('desktop floor contact uses projected shoe soles at their actual depth', () => {
  const camera = new THREE.PerspectiveCamera(35, 300 / 560, 0.1, 100);
  camera.position.set(0, 1, 3); camera.lookAt(0, 0.9, 0); camera.updateMatrixWorld(true);
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.Float32BufferAttribute([-0.1, 0, 0.4, 0.1, 0, -0.4], 3));
  const mesh = new THREE.Mesh(geometry); mesh.updateMatrixWorld(true);
  const system = Object.create(CharacterSystem.prototype) as any;
  system.stage = { camera, renderer: { domElement: { clientWidth: 300, clientHeight: 560 } } };
  system.model = { mesh };
  system.soleVertices = { LEFT: [0], RIGHT: [1] };
  system.footSoleY = () => 0;
  system.slotWorld = () => new THREE.Vector3(0, 0.9, 0);
  const projected = (z: number) => system.worldToCanvas(new THREE.Vector3(0, 0, z)).y;
  const expected = Math.max(projected(0.4), projected(-0.4));
  assert.ok(Math.abs(expected - projected(0)) > 10, 'perspective depth makes the old hips-plane estimate visibly wrong');
  assert.ok(Math.abs(system.floorCanvasY() - expected) < 1e-6);
  geometry.dispose();
});

test('alpha hit-testing includes the top pixel and maps fractional DPI inside the framebuffer', () => {
  const reads: number[][] = [];
  const system = Object.create(CharacterSystem.prototype) as any;
  system.stage = { render: () => {}, renderer: {
    domElement: { clientHeight: 560, width: 450, height: 840 }, getPixelRatio: () => 1.5,
    getContext: () => ({ RGBA: 1, UNSIGNED_BYTE: 2, readPixels: (x: number, y: number, _w: number, _h: number, _f: number, _t: number, data: Uint8Array) => { reads.push([x, y]); data[3] = 255; } }),
  } };
  assert.equal(system.alphaAt(0, 0), 255);
  assert.deepEqual(reads[0], [0, 839]);
  assert.equal(system.alphaAt(299, 559), 255);
  assert.deepEqual(reads[1], [448, 1]);
  assert.equal(system.alphaAt(300, 560), 0);
  assert.equal(reads.length, 2);
});
