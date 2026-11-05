/** Read-only source-model discovery. Never imports, changes or redistributes assets. */
import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { ZipArchive } from '../character_import/zip';
import { rigFromMmdParser } from '../shared/character/rig';
import { analyzeRig } from '../shared/character/profile';
import { defaultCharactersRoot } from '../character_import/paths';

const require = createRequire(import.meta.url);
const { Parser } = require('mmd-parser');
const root = path.resolve(process.argv[2] || '../MYRAA 3d MODELS');
const output = path.resolve(process.argv[3] || 'artifacts/model-inventory.json');
const models: any[] = [];
const motions: string[] = [];
const failures: { source: string; error: string }[] = [];
const hash = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');
function inspect(bytes: Buffer, source: string) {
  const sha256 = hash(bytes);
  const duplicate = models.find(m => m.sha256 === sha256);
  if (duplicate) { duplicate.sources.push(source); return; }
  try {
    const raw = new Parser().parsePmx(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
    const rig = rigFromMmdParser(raw);
    const analysis = analyzeRig(rig);
    models.push({ sha256, name: rig.modelName, sources: [source], format: 'pmx',
      vertices: rig.vertexCount, bones: rig.bones.length, morphs: rig.morphs.length,
      humanoidSlots: Object.keys(analysis.skeleton.humanoid),
      physicsChains: analysis.physics.chains.length, colliders: analysis.physics.colliders.length,
      compatibility: analysis.items, runtime: 'not-run' });
  } catch (e) { failures.push({ source, error: String(e) }); }
}
async function walk(dir: string) {
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isSymbolicLink()) continue;
    if (entry.isDirectory()) { await walk(file); continue; }
    if (/\.(vmd|bvh|fbx|vrma|anim)$/i.test(file)) motions.push(file);
    if (/\.pmx$/i.test(file)) inspect(await fs.readFile(file), file);
    else if (/\.zip$/i.test(file)) {
      try {
        const zip = await ZipArchive.open(file);
        for (const item of zip.entries) {
          if (/\.pmx$/i.test(item.name)) inspect(zip.read(item), `${file}::${item.name}`);
          else if (/\.(vmd|bvh|fbx|vrma|anim)$/i.test(item.name)) motions.push(`${file}::${item.name}`);
          else if (/\.(vrm|glb|gltf|pmd)$/i.test(item.name)) models.push({ sources: [`${file}::${item.name}`], format: path.extname(item.name).slice(1), runtime: 'unsupported by existing PMX renderer' });
        }
      } catch (e) { failures.push({ source: file, error: String(e) }); }
    } else if (/\.(vrm|glb|gltf|pmd)$/i.test(file)) models.push({ sources: [file], format: path.extname(file).slice(1), runtime: 'unsupported by existing PMX renderer' });
  }
}
await walk(root);
await walk(path.resolve('assets/characters'));
const stagedRoot = defaultCharactersRoot();
const staged: any[] = [];
for (const dir of await fs.readdir(stagedRoot, { withFileTypes: true }).catch(() => [])) {
  if (!dir.isDirectory()) continue;
  const profilePath = path.join(stagedRoot, dir.name, 'profile.json');
  try {
    const profile = JSON.parse(await fs.readFile(profilePath, 'utf8'));
    const file = path.join(stagedRoot, dir.name, profile.model.file);
    const bytes = await fs.readFile(file);
    inspect(bytes, file);
    staged.push({ id: profile.id, name: profile.displayName, directory: path.dirname(file), sha256: hash(bytes), restrictions: profile.source?.restrictions });
  } catch (e) { failures.push({ source: profilePath, error: String(e) }); }
}
await fs.mkdir(path.dirname(output), { recursive: true });
await fs.writeFile(output, JSON.stringify({ generatedAt: new Date().toISOString(), root, stagedRoot, models, staged, motions, failures }, null, 2));
console.log(JSON.stringify({ output, uniqueModels: models.length, staged: staged.length, motions: motions.length, failures: failures.length }));
