import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, loadConfig, normalizeUsage, validateGuide, validatePolicyAnalysis, validateReview, validateSourceMaterials } from '../ai-provider-adapter.mjs';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import http from 'node:http';

const allowlist = ['gov.uk'];
const materials = [{
  key: 'official-benefit-guide',
  organisation: 'Example Department',
  title: 'Official benefit guide',
  url: 'https://www.gov.uk/example-benefit',
  accessedAt: '2026-11-12T12:00:00Z',
  content: 'Verified source text for the test.',
}];
const guide = {
  slug: 'example-benefit',
  category: 'benefits',
  title: 'Example benefit',
  summary: 'A sourced example.',
  content: 'Read the [official conditions](#guide-evidence-eligibility).',
  status: 'DRAFT',
  publishedAt: null,
  updatedAt: '2026-11-12T12:00:00Z',
  sources: [{ key: 'official-benefit-guide', organisation: 'Example Department', title: 'Official benefit guide', url: 'https://www.gov.uk/example-benefit', accessedAt: '2026-11-12T12:00:00Z' }],
  evidence: [{ key: 'eligibility', statement: 'Eligibility is described by the official source.', supports: [{ sourceKey: 'official-benefit-guide', locator: 'Eligibility', excerpt: null, note: 'Source reference for the claim.' }] }],
};

test('source material must use an approved HTTPS host and stable provenance', () => {
  assert.equal(validateSourceMaterials(materials, allowlist).length, 1);
  assert.throws(() => validateSourceMaterials([{ ...materials[0], url: 'https://example.com/fake' }], allowlist), /allowlist/);
  assert.throws(() => validateSourceMaterials([{ ...materials[0], url: 'http://www.gov.uk/example-benefit' }], allowlist), /HTTPS/);
});

test('Chinese Guide output validates the fixed Guide schema and evidence references', () => {
  assert.equal(validateGuide(guide, { slug: 'example-benefit', sourceMaterials: materials }).slug, guide.slug);
  assert.throws(() => validateGuide({ ...guide, status: 'PUBLISHED' }, { sourceMaterials: materials }), /DRAFT/);
  assert.throws(() => validateGuide({ ...guide, summary: 'x', unsupported: true }, { sourceMaterials: materials }), /unsupported field/);
  assert.throws(() => validateGuide({ ...guide, sources: [{ ...guide.sources[0], url: 'https://attacker.invalid/' }] }, { sourceMaterials: materials }), /source reference/);
  assert.throws(() => validateGuide({ ...guide, content: 'No evidence link.' }, { sourceMaterials: materials }), /not referenced/);
});

test('translation validation retains exact source links and evidence structure', () => {
  const translated = { ...guide, title: 'Example benefit', summary: 'A translated example.', content: 'See [official conditions](#guide-evidence-eligibility).' };
  assert.equal(validateGuide(translated, { sourceMaterials: materials, locale: 'en', sourceGuide: guide }).slug, guide.slug);
  const badSource = { ...translated, sources: [{ ...translated.sources[0], url: 'https://example.com/' }] };
  assert.throws(() => validateGuide(badSource, { sourceMaterials: materials, locale: 'en', sourceGuide: guide }), /source reference/);
  assert.throws(() => validateGuide(translated, { sourceMaterials: materials, locale: 'zh-CN', sourceGuide: guide }), /Unsupported translation/);
});

test('Claude review schema fails closed on invalid or blocking findings and binds the requested hash', () => {
  const hash = 'a'.repeat(64);
  assert.deepEqual(validateReview({ passed: true, findings: [] }, hash), { contentHash: hash, passed: true, findings: [] });
  assert.equal(validateReview({ passed: true, findings: [{ code: 'FACT', severity: 'BLOCKER', message: 'Unsupported claim' }] }, hash).passed, false);
  assert.throws(() => validateReview({ passed: 'yes', findings: [] }, hash), /boolean passed/);
});

test('Sol policy analysis requires exact source-key citations', () => {
  const result = { keyFindings: [{ claim: 'Eligibility conditions', sourceKeys: ['official-benefit-guide'] }], effectiveDates: [], unresolvedQuestions: [] };
  assert.deepEqual(validatePolicyAnalysis(result, materials), result);
  assert.throws(() => validatePolicyAnalysis({ ...result, keyFindings: [{ claim: 'Unsupported', sourceKeys: ['invented'] }] }, materials), /supplied source keys/);
});

test('usage normalizes provider token fields and estimates cost only when rates are supplied', () => {
  assert.deepEqual(normalizeUsage('sol', 'configured-model', { prompt_tokens: 10, completion_tokens: 5 }, 123, { inputUsdPerMillion: 2, outputUsdPerMillion: 4 }), {
    provider: 'sol', model: 'configured-model', inputTokens: 10, outputTokens: 5, usageAvailable: true, latencyMs: 123, estimatedCostUsd: 0.00004,
  });
  assert.equal(normalizeUsage('terra', 'configured-model', null, 12).estimatedCostUsd, null);
});

test('live mode stays disabled by default and does not expose a provider route', async (t) => {
  assert.equal(loadConfig({}).enabled, false);
  const server = createServer({ enabled: false });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const response = await fetch(`http://127.0.0.1:${server.address().port}/v1/guide-drafts`, { method: 'POST', body: '{}' });
  assert.equal(response.status, 503);
  assert.equal((await response.json()).code, 'AI_ADAPTER_DISABLED');
});

test('provider protocol integration is mockable and records token usage without retaining content', async (t) => {
  const tempDir = await mkdtemp(path.join(tmpdir(), 'life-uk-ai-adapter-'));
  const metricsFile = path.join(tempDir, 'usage.jsonl');
  const fixtureSources = materials.map(({ content, ...source }) => source);
  let providerCalls = 0;
  const provider = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const payload = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    providerCalls++;
    let output;
    if (req.url === '/sol') {
      const user = JSON.parse(payload.messages[1].content);
      output = payload.messages[0].content.includes('POLICY_ANALYSIS_JSON')
        ? { keyFindings: [{ claim: 'Eligibility conditions', sourceKeys: ['official-benefit-guide'] }], effectiveDates: [], unresolvedQuestions: [] }
        : makeGuide(user.job.slug, fixtureSources, 'Chinese source');
    } else if (req.url === '/terra') {
      const user = JSON.parse(payload.messages[1].content);
      output = { ...user.sourceGuide, title: `Translation ${user.locale}`, summary: `Translated for ${user.locale}` };
    } else {
      output = { passed: true, findings: [] };
    }
    const body = req.url === '/claude'
      ? { content: [{ type: 'text', text: JSON.stringify(output) }], usage: { input_tokens: 31, output_tokens: 7 } }
      : { choices: [{ message: { content: JSON.stringify(output) } }], usage: { prompt_tokens: 41, completion_tokens: 9 } };
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(body));
  });
  await new Promise((resolve) => provider.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${provider.address().port}`;
  const config = {
    enabled: true, adapterToken: 'local-test-token-0123456789-0123456789', host: '127.0.0.1', port: 0,
    timeoutMs: 5000, maxRequestsPerMinute: 20, sourceAllowlist: allowlist, metricsFile,
    sol: { provider: 'sol', url: `${origin}/sol`, apiKey: 'sol-test-secret', model: 'sol-6.1-configured', rates: { inputUsdPerMillion: 1, outputUsdPerMillion: 2 } },
    terra: { provider: 'terra', url: `${origin}/terra`, apiKey: 'terra-test-secret', model: 'terra-6.1-configured', rates: { inputUsdPerMillion: 1, outputUsdPerMillion: 2 } },
    claude: { provider: 'claude', url: `${origin}/claude`, apiKey: 'claude-test-secret', model: 'claude-configured', apiVersion: 'test-version', maxTokens: 500, rates: { inputUsdPerMillion: 1, outputUsdPerMillion: 2 } },
  };
  const adapter = createServer(config);
  await new Promise((resolve) => adapter.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    await Promise.all([new Promise((resolve) => adapter.close(resolve)), new Promise((resolve) => provider.close(resolve))]);
    await rm(tempDir, { recursive: true, force: true });
  });

  const endpoint = `http://127.0.0.1:${adapter.address().port}`;
  const generatedResponse = await fetch(`${endpoint}/v1/guide-drafts`, {
    method: 'POST', headers: { authorization: `Bearer ${config.adapterToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      job: { idempotencyKey: 'test-job-v1', policyVersion: 'test-policy-v1', sourceVersion: 'test-source-v1', slug: 'example-benefit', topicCandidate: { topic: 'Example benefit', candidateId: 'candidate-1' } },
      sourceMaterials: materials,
    }),
  });
  assert.equal(generatedResponse.status, 200);
  const generated = await generatedResponse.json();
  assert.deepEqual(generated.translations.map((row) => row.locale), ['zh-TW', 'en', 'ur', 'hi']);
  assert.equal(generated.usageEvents.length, 6);
  assert.equal(generated.usageEvents.every((event) => event.usageAvailable && event.estimatedCostUsd !== null), true);

  for (const locale of ['zh-CN', ...generated.translations.map((row) => row.locale)]) {
    const guideValue = locale === 'zh-CN' ? generated.sourceGuide : generated.translations.find((row) => row.locale === locale).guide;
    const contentHash = createHash('sha256').update(JSON.stringify(guideValue)).digest('hex');
    const reviewResponse = await fetch(`${endpoint}/v1/claude/review`, {
      method: 'POST', headers: { authorization: `Bearer ${config.adapterToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ batchId: 'batch-1', locale, contentHash, guide: guideValue, sourceGuide: generated.sourceGuide, sourceMaterials: materials }),
    });
    assert.equal(reviewResponse.status, 200);
    const review = await reviewResponse.json();
    assert.equal(review.contentHash, contentHash);
    assert.equal(review.passed, true);
    assert.equal(review.reviewerIdentity, 'claude-independent');
  }
  assert.equal(providerCalls, 11);
  const usageLines = (await readFile(metricsFile, 'utf8')).trim().split('\n').map((line) => JSON.parse(line));
  assert.equal(usageLines.length, 11);
  assert.equal(usageLines.every((event) => event.inputTokens > 0 && event.outputTokens > 0 && event.latencyMs >= 0), true);
  assert.equal(JSON.stringify(usageLines).includes('test-secret'), false);
  assert.equal(JSON.stringify(usageLines).includes('Verified source text'), false);
});

function makeGuide(slug, sources, title) {
  return {
    slug, category: 'benefits', title, summary: 'A sourced example.',
    content: 'Read the [official conditions](#guide-evidence-eligibility).',
    status: 'DRAFT', publishedAt: null, updatedAt: '2026-11-12T12:00:00Z', sources,
    evidence: [{ key: 'eligibility', statement: 'Eligibility is described by the official source.', supports: [{ sourceKey: 'official-benefit-guide', locator: 'Eligibility', excerpt: null, note: 'Source reference for the claim.' }] }],
  };
}
