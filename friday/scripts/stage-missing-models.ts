/** Add only absent humanoids; existing imported characters are never re-imported. */
import fs from 'node:fs/promises';
import { importCharacter } from '../character_import/importer';
import { defaultCharactersRoot } from '../character_import/paths';
const inventory = JSON.parse(await fs.readFile('artifacts/model-inventory.json', 'utf8'));
const present = new Set(inventory.staged.map((p: any) => p.sha256));
const results = [];
for (const model of inventory.models) {
  if (model.humanoidSlots?.length < 40 || present.has(model.sha256) || model.sources.some((s: string) => s.includes('assets\\characters'))) continue;
  const source = model.sources.find((s: string) => s.includes('::')) || model.sources[0];
  const [file, inner] = source.split('::');
  const result = await importCharacter({ source: file, modelFile: inner, charactersRoot: defaultCharactersRoot(), id: `v2-${model.sha256.slice(0, 12)}` });
  results.push({ name: result.profile.displayName, id: result.profile.id, directory: result.directory, warnings: result.warnings, restrictions: result.profile.source.restrictions });
  console.log(`Staged ${result.profile.displayName}: ${result.profile.id}`);
}
await fs.writeFile('artifacts/staged-models.json', JSON.stringify(results, null, 2));
