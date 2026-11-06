#!/usr/bin/env node
// FRIDAY per-phone activation keys. Only the owner, who holds the private key, can make keys.
//
//   node scripts/friday-activation.mjs init                 one time: make the owner key pair
//   node scripts/friday-activation.mjs key A1B2-C3D4-E5F6-0718 [--name "Ammi ka phone"]
//   node scripts/friday-activation.mjs public               print the public key again
//
// The private key is kept OUTSIDE the repository, in ~/.friday-activation/owner-private.pem
// (or the folder in FRIDAY_ACTIVATION_DIR). It can also be given as text in FRIDAY_ACTIVATION_PRIVATE_KEY,
// which is how the GitHub "FRIDAY activation key" workflow reads it from a repository secret.
// Never commit the private key. The public key in android/activation-public-key.txt is safe to commit.
import { createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto';
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const MESSAGE_PREFIX = 'FRIDAY-ACTIVATION-V1|';
const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
const fridayRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const PUBLIC_KEY_FILE = join(fridayRoot, 'android', 'activation-public-key.txt');

export function normalizeDeviceId(raw) {
  const hex = String(raw ?? '').toUpperCase().replace(/[^0-9A-F]/g, '');
  return hex ? hex.padStart(16, '0') : '';
}

export function base32Encode(bytes) {
  let out = '', buffer = 0, bits = 0;
  for (const byte of bytes) {
    buffer = (buffer << 8) | byte; bits += 8;
    while (bits >= 5) { bits -= 5; out += BASE32[(buffer >> bits) & 31]; }
    buffer &= (1 << bits) - 1;
  }
  if (bits > 0) out += BASE32[(buffer << (5 - bits)) & 31];
  return out;
}

export function base32Decode(text) {
  const clean = String(text).toUpperCase().replace(/[\s=-]/g, '');
  const out = []; let buffer = 0, bits = 0;
  for (const ch of clean) {
    const value = BASE32.indexOf(ch);
    if (value < 0) throw new Error('Not a base32 character.');
    buffer = (buffer << 5) | value; bits += 5;
    if (bits >= 8) { bits -= 8; out.push((buffer >> bits) & 0xff); }
    buffer &= (1 << bits) - 1;
  }
  return Buffer.from(out);
}

const group = (text, size, sep = '-') => text.match(new RegExp(`.{1,${size}}`, 'g')).join(sep);

/** The activation key for one device: the owner's P-256 signature, base32, in groups of 5. */
export function makeActivationKey(privateKey, deviceId) {
  const device = String(deviceId ?? '').toUpperCase().replace(/[\s-]/g, '');
  if (!/^[0-9A-F]{16}$/.test(device)) throw new Error(`Device ID should be 16 letters/numbers (like A1B2-C3D4-E5F6-0718); got "${deviceId}".`);
  const signature = sign('sha256', Buffer.from(MESSAGE_PREFIX + device, 'utf8'), { key: privateKey, dsaEncoding: 'ieee-p1363' });
  return group(base32Encode(signature), 5);
}

export function checkActivationKey(publicKey, deviceId, key) {
  try {
    const raw = base32Decode(key);
    return raw.length === 64 && verify('sha256', Buffer.from(MESSAGE_PREFIX + normalizeDeviceId(deviceId), 'utf8'), { key: publicKey, dsaEncoding: 'ieee-p1363' }, raw);
  } catch { return false; }
}

export function publicKeyText(privateKey) {
  return createPublicKey(privateKey).export({ type: 'spki', format: 'der' }).toString('base64');
}

export function parsePublicKey(text) {
  const body = text.replace(/-----[A-Z ]+-----/g, '').replace(/\s/g, '');
  return createPublicKey({ key: Buffer.from(body, 'base64'), format: 'der', type: 'spki' });
}

function keyDir() { return process.env.FRIDAY_ACTIVATION_DIR || join(homedir(), '.friday-activation'); }
function privateKeyPath() { return join(keyDir(), 'owner-private.pem'); }

function loadPrivateKey() {
  const fromEnv = process.env.FRIDAY_ACTIVATION_PRIVATE_KEY;
  if (fromEnv && fromEnv.trim()) return createPrivateKey(fromEnv.trim().replace(/\\n/g, '\n'));
  const file = privateKeyPath();
  if (!existsSync(file)) throw new Error(`No owner private key at ${file}. Run "node scripts/friday-activation.mjs init" first (one time only).`);
  return createPrivateKey(readFileSync(file, 'utf8'));
}

function main(argv) {
  const [command, ...rest] = argv;
  if (command === 'init') {
    const file = privateKeyPath();
    if (existsSync(file) && !rest.includes('--force')) throw new Error(`An owner key already exists at ${file}. Keep using it: a new key would make every key you already gave out stop working in new builds. (Use --force only if you really mean that.)`);
    const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
    mkdirSync(keyDir(), { recursive: true, mode: 0o700 });
    writeFileSync(file, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
    writeFileSync(PUBLIC_KEY_FILE, publicKeyText(privateKey) + '\n');
    console.log(`Owner PRIVATE key saved to: ${file}\n  Keep it secret and back it up (for example on a USB stick). Never commit it or send it to anyone.`);
    console.log(`Public key written to: ${PUBLIC_KEY_FILE}\n  This one is safe to commit. Build the APK again so it contains it.`);
    return;
  }
  if (command === 'public') { console.log(publicKeyText(loadPrivateKey())); return; }
  if (command === 'key') {
    const nameAt = rest.indexOf('--name');
    const name = nameAt >= 0 ? rest[nameAt + 1] ?? '' : '';
    const device = rest.filter((_, i) => nameAt < 0 || (i !== nameAt && i !== nameAt + 1)).join('');
    const privateKey = loadPrivateKey();
    const key = makeActivationKey(privateKey, device);
    if (existsSync(PUBLIC_KEY_FILE)) {
      const bundled = parsePublicKey(readFileSync(PUBLIC_KEY_FILE, 'utf8'));
      if (!checkActivationKey(bundled, device, key)) throw new Error(`This private key does not match ${PUBLIC_KEY_FILE}, so the app would reject the key.`);
    }
    if (!process.env.FRIDAY_ACTIVATION_PRIVATE_KEY) {
      try { appendFileSync(join(keyDir(), 'issued-keys.txt'), `${new Date().toISOString()}\t${normalizeDeviceId(device)}\t${name}\n`); } catch {}
    }
    console.log(key);
    return;
  }
  console.log('Usage:\n  node scripts/friday-activation.mjs init\n  node scripts/friday-activation.mjs key <DEVICE-ID> [--name "label"]\n  node scripts/friday-activation.mjs public');
  if (command) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(process.argv.slice(2)); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
