import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { generateKeyPairSync } from 'node:crypto';
// @ts-ignore plain ES module helper used by the owner tool
import { base32Decode, base32Encode, checkActivationKey, makeActivationKey, normalizeDeviceId, publicKeyText } from '../scripts/friday-activation.mjs';

const owner = generateKeyPairSync('ec', { namedCurve: 'P-256' });
const stranger = generateKeyPairSync('ec', { namedCurve: 'P-256' });

test('device IDs normalise the way the app shows them', () => {
  assert.equal(normalizeDeviceId('a1b2-c3d4 e5f6-0718'), 'A1B2C3D4E5F60718');
  assert.equal(normalizeDeviceId('5f60718'), '0000000005F60718');
  assert.equal(normalizeDeviceId(''), '');
});

test('base32 round-trips', () => {
  const bytes = Buffer.from(Array.from({ length: 64 }, (_, i) => (i * 37 + 11) & 0xff));
  assert.deepEqual(base32Decode(base32Encode(bytes)), bytes);
});

test('a key works only on its own phone and only from the owner', () => {
  const key = makeActivationKey(owner.privateKey, 'A1B2-C3D4-E5F6-0718');
  assert.ok(checkActivationKey(owner.publicKey, 'a1b2c3d4e5f60718', key));
  assert.ok(checkActivationKey(owner.publicKey, 'A1B2C3D4E5F60718', key.toLowerCase().replace(/-/g, ' ')));
  assert.ok(!checkActivationKey(owner.publicKey, 'A1B2C3D4E5F60719', key), 'other phone rejected');
  assert.ok(!checkActivationKey(stranger.publicKey, 'A1B2C3D4E5F60718', key), 'other owner rejected');
  const forged = makeActivationKey(stranger.privateKey, 'A1B2C3D4E5F60718');
  assert.ok(!checkActivationKey(owner.publicKey, 'A1B2C3D4E5F60718', forged), 'key made with another private key rejected');
  assert.throws(() => makeActivationKey(owner.privateKey, 'not-an-id'));
});

const javac = spawnSync('javac', ['-version']).status === 0;
test('the Android app accepts the same keys (Java ActivationCodec)', { skip: !javac && 'javac not installed' }, () => {
  const dir = mkdtempSync(join(tmpdir(), 'friday-activation-'));
  writeFileSync(join(dir, 'Check.java'), `
    import com.myraa.mobile.ActivationCodec;
    public class Check { public static void main(String[] a) throws Exception {
      java.security.PublicKey k = ActivationCodec.parsePublicKey(a[0]);
      System.out.print(ActivationCodec.verify(k, a[1], a[2]) + "," + ActivationCodec.verify(k, a[3], a[2]) + "," + ActivationCodec.verify(k, a[1], a[4]) + "," + ActivationCodec.groupDeviceId(ActivationCodec.normalizeDeviceId(a[1])));
    } }`);
  execFileSync('javac', ['-d', dir, 'android/app/src/main/java/com/myraa/mobile/ActivationCodec.java', join(dir, 'Check.java')]);
  const device = 'a1b2-c3d4-e5f6-0718', key = makeActivationKey(owner.privateKey, device);
  const forged = makeActivationKey(stranger.privateKey, device);
  for (let i = 0; i < 5; i++) {
    // Several keys, so signatures whose r or s needs a DER sign byte are also covered.
    const k = makeActivationKey(owner.privateKey, device);
    const out = execFileSync('java', ['-cp', dir, 'Check', publicKeyText(owner.privateKey), device, k, 'A1B2C3D4E5F60719', forged], { encoding: 'utf8' });
    assert.equal(out, 'true,false,false,A1B2-C3D4-E5F6-0718');
  }
  assert.ok(key.length > 100);
});
