import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const path = new URL('../workflows/life-in-uk-five-locale-ai-draft.json', import.meta.url);
const workflow = JSON.parse(await readFile(path, 'utf8'));

test('real AI workflow extends the tested editorial graph and remains inactive', () => {
  assert.equal(workflow.active, false);
  assert.equal(workflow.name, 'Life in UK — Five-locale Guide batch (AI adapters, inactive)');
  const names = new Set(workflow.nodes.map((node) => node.name));
  for (const name of [
    'Manual Trigger',
    'Create idempotent Chinese draft batch',
    'Submit locale draft',
    'Store hash-bound review outcome',
    'Read batch approval state',
    'Report AI usage and stop at owner approval gate',
  ]) assert.ok(names.has(name), `missing reused node ${name}`);
  assert.ok(names.has('Sol 6.1 source + Terra 6.1 translations (local adapter)'));
  assert.ok(names.has('Claude independent review (local adapter)'));
  const byName = Object.fromEntries(workflow.nodes.map((node) => [node.name, node]));
  assert.equal(byName['Sol 6.1 source + Terra 6.1 translations (local adapter)'].settings?.retryOnFail, undefined);
  assert.equal(byName['Claude independent review (local adapter)'].settings?.retryOnFail, undefined);
  assert.equal(byName['Seed manually verified topic and sources'].parameters.jsonOutput.includes('"sourceMaterials":[]'), true);
  assert.equal(byName['Sol 6.1 source + Terra 6.1 translations (local adapter)'].parameters.url, 'http://127.0.0.1:8092/v1/guide-drafts');
  assert.equal(byName['Sol 6.1 source + Terra 6.1 translations (local adapter)'].parameters.options.timeout, 930000);
  assert.match(byName['Prepare five locale drafts'].parameters.jsCode, /sourceContentHash: j\.sourceContentHash/);
  assert.match(byName['Normalize locale review input'].parameters.jsCode, /sourceContentHash: i\.json\.sourceContentHash/);
  assert.equal(byName['Claude independent review (local adapter)'].parameters.url, 'http://127.0.0.1:8092/v1/claude/review');
  assert.equal(byName['Report AI usage and stop at owner approval gate'].parameters.jsCode.includes('publicationCalled: false'), true);
  assert.equal(workflow.nodes.some((node) => String(node.parameters?.url || '').includes('/approve') || String(node.parameters?.url || '').includes('/publish')), false);
  assert.equal(Object.keys(workflow.connections).every((name) => names.has(name)), true);
  assert.equal(workflow.settings.saveDataSuccessExecution, 'none');
  assert.equal(workflow.settings.saveDataErrorExecution, 'none');
  assert.equal(workflow.settings.saveManualExecutions, false);
  assert.equal(workflow.settings.saveExecutionProgress, false);
  for (const name of Object.keys(workflow.connections)) {
    for (const output of Object.values(workflow.connections[name])) {
      for (const group of output) for (const edge of group) assert.ok(names.has(edge.node), `dangling edge to ${edge.node}`);
    }
  }
});
