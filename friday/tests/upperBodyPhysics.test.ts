import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeUpperBodyModels, normalizeUpperBodyPhysics, restrainedUpperBodyMaterial } from '../shared/upperBodyPhysics';
import { PHYSICS_MATERIALS } from '../shared/character/secondary';
import { normalizeSettings, patchSettings } from '../settings/appSettings';

test('default upper-body tuning preserves the existing restrained material', () => {
  assert.deepEqual(restrainedUpperBodyMaterial(PHYSICS_MATERIALS.SOFT_BODY, undefined), PHYSICS_MATERIALS.SOFT_BODY);
  assert.equal(normalizeUpperBodyPhysics({ maxAngleDeg: 1000, strength: Infinity, dampingMultiplier: -1 }).maxAngleDeg, 6);
  assert.equal(normalizeUpperBodyPhysics({ dampingMultiplier: -1 }).dampingMultiplier, 0.75);
  assert.equal(normalizeUpperBodyPhysics({ strength: 0 }).strength, 0);
  assert.equal(restrainedUpperBodyMaterial({ ...PHYSICS_MATERIALS.SOFT_BODY, maxAngleDeg: 2 }, { maxAngleDeg: 6 }).maxAngleDeg, 2);
});

test('per-model settings remain bounded, isolated and persistent through settings patches', () => {
  const settings = normalizeSettings({ physics: { upperBodyByModel: { evelyn: { strength: 0.6 }, astra: { strength: 0.3 } } } });
  const next = patchSettings(settings, { physics: { upperBodyByModel: { evelyn: { enabled: false } } } });
  assert.equal(next.physics.upperBodyByModel.evelyn.strength, 0.6);
  assert.equal(next.physics.upperBodyByModel.evelyn.enabled, false);
  assert.equal(next.physics.upperBodyByModel.astra.strength, 0.3);
  assert.equal(settings.physics.upperBodyByModel.evelyn.enabled, true);
  assert.deepEqual(Object.keys(normalizeUpperBodyModels(JSON.parse('{"../../elsewhere":{},"__proto__":{},"evelyn":{"quality":"unlimited"}}'))), ['evelyn']);
  assert.equal(normalizeUpperBodyModels({ evelyn: { quality: 'unlimited' } }).evelyn.quality, 'inherit');
});
