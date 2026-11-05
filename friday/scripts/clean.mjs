import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
for (const name of ['dist', 'server.js']) {
  const target = path.resolve(projectRoot, name);
  if (path.dirname(target) !== projectRoot || !['dist', 'server.js'].includes(path.basename(target))) throw new Error('Unsafe clean target.');
  await fs.rm(target, { recursive: true, force: true });
}
console.log('Removed generated desktop build outputs. User data and model assets were preserved.');
