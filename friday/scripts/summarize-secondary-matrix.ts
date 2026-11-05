import assert from 'node:assert/strict';
import fs from 'node:fs';
const run = process.argv[2] || 'secondary-upper';
if (!/^[a-zA-Z0-9_-]+$/.test(run)) throw new Error('Invalid run name.');
const matrix = JSON.parse(fs.readFileSync(`artifacts/${run}-matrix.json`, 'utf8'));
assert.equal(matrix.stoppedReason, null);
assert.equal(matrix.cases.length, 36);
assert.equal(new Set(matrix.cases.map((entry: any) => entry.id)).size, 12);
const rows: any[] = [];
for (const entry of matrix.cases) {
  assert.equal(entry.exitCode, 0);
  const result = JSON.parse(fs.readFileSync(entry.result, 'utf8'));
  assert.equal(result.sourceCommit, matrix.sourceCommit);
  assert.equal(result.phases.length, 19);
  assert.deepEqual(result.errors, []);
  for (const flag of ['numericHealthy', 'motionMeasured', 'listenMouthClosed', 'speechStateArticulates', 'mouthReturnsToIdle', 'sitStandCompletes', 'upperBodyControls']) assert.equal(result[flag], true, `${entry.id}/${entry.preset}: ${flag}`);
  assert.ok(result.phases.every((phase: any) => phase.finite && phase.healthy));
  rows.push({ id: entry.id, preset: entry.preset, phases: result.phases.length,
    maxChestAngleDeg: Math.max(...result.phases.map((phase: any) => phase.maxDeviationDeg)),
    maxMeshDisplacementHeightRatio: Math.max(...result.phases.map((phase: any) => phase.maxMeshDisplacementHeightRatio)),
    upperBodyControls: result.upperBodyControls, sampledVertices: result.inventory.sampledVertices,
    grantDrivenBones: result.inventory.grantDrivenBones });
}
const columns = Object.keys(rows[0]);
fs.writeFileSync(`artifacts/${run}-matrix-summary.csv`, [columns.join(','), ...rows.map(row => columns.map(key => row[key]).join(','))].join('\n'));
const summary = { sourceCommit: matrix.sourceCommit, cases: rows.length, phases: rows.reduce((sum, row) => sum + row.phases, 0),
  maxChestAngleDeg: Math.max(...rows.map(row => row.maxChestAngleDeg)), allControlChecksPassed: true,
  scope: 'Numerical renderer/physics regression at 300x560; mouth animation states, no microphone or provider calls. Visual naturalness and planted feet require separate review.' };
fs.writeFileSync(`artifacts/${run}-summary.json`, JSON.stringify(summary, null, 2));
console.log(JSON.stringify(summary));
