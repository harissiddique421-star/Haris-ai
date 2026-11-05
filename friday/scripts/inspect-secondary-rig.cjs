/** Read-only PMX weight/grant inspection, using the existing model parser. */
const fs = require('node:fs'), path = require('node:path');
const { Parser } = require('mmd-parser');
const id = process.argv[2] || 'azur-promilia-199001-p1';
if (!/^[a-z0-9_-]{1,64}$/.test(id)) throw new Error('Invalid model identifier.');
const root = path.join(process.env.APPDATA, 'MYRAA', 'characters', id);
const profile = JSON.parse(fs.readFileSync(path.join(root, 'profile.json'), 'utf8'));
const modelPath = path.resolve(root, profile.model.file);
if (!modelPath.startsWith(path.resolve(root) + path.sep)) throw new Error('Model path escaped its profile.');
const bytes = fs.readFileSync(modelPath);
const pmx = new Parser().parsePmx(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
const counts = new Map();
for (const vertex of pmx.vertices) for (let slot = 0; slot < vertex.skinIndices.length; slot++) {
  if ((vertex.skinWeights[slot] || 0) > 0.001) counts.set(vertex.skinIndices[slot], (counts.get(vertex.skinIndices[slot]) || 0) + 1);
}
const selected = new Set();
pmx.bones.forEach((bone, index) => { if (/胸|breast|bust/i.test(bone.name + ' ' + bone.englishName)) selected.add(index); });
for (const index of [...selected]) for (let child = 0; child < pmx.bones.length; child++) {
  if (pmx.bones[child].grant?.parentIndex === index) selected.add(child);
}
const result = { id, scope: 'Read-only authored PMX metadata and skin weights; no runtime changes.',
  bones: [...selected].map(index => { const bone = pmx.bones[index]; return {
    index, name: bone.name, parent: pmx.bones[bone.parentIndex]?.name || null, flag: bone.flag, afterPhysics: Boolean(bone.flag & 0x1000),
    position: bone.position, weightedVertices: counts.get(index) || 0,
    grant: bone.grant ? { ...bone.grant, parent: pmx.bones[bone.grant.parentIndex]?.name || null } : null,
    rigidBodies: pmx.rigidBodies.filter(body => body.boneIndex === index).map(body => ({ name: body.name, type: body.type })),
  }; }),
  chestMorphs: pmx.morphs.filter(morph => /胸|breast|bust/i.test(morph.name + ' ' + morph.englishName)).map(morph => ({ name: morph.name, type: morph.type, elements: morph.elements?.length || 0 })),
};
fs.mkdirSync(path.resolve('artifacts/secondary'), { recursive: true });
fs.writeFileSync(path.resolve('artifacts/secondary', `${id}-rig.json`), JSON.stringify(result, null, 2));
console.log(JSON.stringify(result, null, 2));
