import http from 'node:http';

const HOST = '127.0.0.1';
const PORT = 8091;
const locales = {
  'zh-CN': {
    title: '五种语言 Guide 批次演示（合成内容）',
    summary: '仅用于本地自动化验收，不是事实指引。',
    content: '# 合成内容演示\n\n这是合成文本，不可用于申请、付款或作出决定。[查看依据](#guide-evidence-example-application) [补充依据](#guide-evidence-example-condition)\n',
    statements: ['这是合成主张，仅用于本地测试。', '第二项合成主张。'],
  },
  'zh-TW': {
    title: '五種語言 Guide 批次示範（合成內容）',
    summary: '僅供本機自動化驗收，不是事實指引。',
    content: '# 合成內容示範\n\n這是合成文字，不可用於申請、付款或作出決定。[查看依據](#guide-evidence-example-application) [補充依據](#guide-evidence-example-condition)\n',
    statements: ['這是合成主張，僅供本機測試。', '第二項合成主張。'],
  },
  en: {
    title: 'Five-locale Guide batch demo (synthetic content)',
    summary: 'For local automation verification only; not factual guidance.',
    content: '# Synthetic content demo\n\nThis is synthetic text. Do not use it to apply, pay, or make decisions.[View evidence](#guide-evidence-example-application) [Additional evidence](#guide-evidence-example-condition)\n',
    statements: ['Synthetic claim for local testing only.', 'Second synthetic claim.'],
  },
  ur: {
    title: 'پانچ زبانوں کے گائیڈ بیچ کا مظاہرہ (فرضی مواد)',
    summary: 'صرف مقامی خودکار جانچ کے لیے؛ یہ حقائق پر مبنی رہنمائی نہیں ہے۔',
    content: '# فرضی مواد کا مظاہرہ\n\nیہ فرضی متن ہے۔ اسے درخواست دینے، ادائیگی کرنے یا فیصلے کرنے کے لیے استعمال نہ کریں۔[ثبوت دیکھیں](#guide-evidence-example-application) [مزید ثبوت](#guide-evidence-example-condition)\n',
    statements: ['صرف مقامی جانچ کے لیے فرضی دعویٰ۔', 'دوسرا فرضی دعویٰ۔'],
  },
  hi: {
    title: 'पाँच भाषा Guide बैच डेमो (काल्पनिक सामग्री)',
    summary: 'केवल स्थानीय स्वचालन जाँच के लिए; यह तथ्यात्मक मार्गदर्शन नहीं है।',
    content: '# काल्पनिक सामग्री डेमो\n\nयह काल्पनिक पाठ है। आवेदन, भुगतान या निर्णय के लिए इसका उपयोग न करें।[साक्ष्य देखें](#guide-evidence-example-application) [अतिरिक्त साक्ष्य](#guide-evidence-example-condition)\n',
    statements: ['केवल स्थानीय परीक्षण के लिए काल्पनिक दावा।', 'दूसरा काल्पनिक दावा।'],
  },
};

function json(res, status, value) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(value));
}

async function body(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 1_000_000) throw new Error('request too large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

function guide(locale) {
  const text = locales[locale];
  if (!text) throw new Error('unsupported locale');
  return {
    slug: 'n8n-mock-five-locale-guide',
    category: 'example-category',
    title: text.title,
    summary: text.summary,
    content: text.content,
    status: 'DRAFT',
    publishedAt: null,
    updatedAt: '2026-11-11T00:00:00Z',
    sources: [
      { key: 'mock-official-a', organisation: 'Mock adapter', title: 'Synthetic source A', url: 'https://source-a.example.invalid/mock', accessedAt: '2026-11-11T00:00:00Z' },
      { key: 'mock-official-b', organisation: 'Mock adapter', title: 'Synthetic source B', url: 'https://source-b.example.invalid/mock', accessedAt: '2026-11-11T00:00:00Z' },
    ],
    evidence: [
      { key: 'example-application', statement: text.statements[0], supports: [
        { sourceKey: 'mock-official-a', locator: 'Synthetic section A', excerpt: null, note: 'Mock evidence only.' },
        { sourceKey: 'mock-official-b', locator: null, excerpt: 'Synthetic quote only.', note: 'Mock evidence only.' },
      ] },
      { key: 'example-condition', statement: text.statements[1], supports: [
        { sourceKey: 'mock-official-a', locator: 'Synthetic section B', excerpt: null, note: 'Mock evidence only.' },
      ] },
    ],
  };
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/healthz') return json(res, 200, { status: 'ok', adapter: 'local-mock-only' });
    if (req.method !== 'POST') return json(res, 405, { error: 'method not allowed' });
    const input = await body(req);
    if (req.url === '/mock/sol/draft-and-translations') {
      const sourceGuide = guide('zh-CN');
      const translations = ['zh-TW', 'en', 'ur', 'hi'].map((locale) => ({ locale, guide: guide(locale) }));
      return json(res, 200, { ...input, sourceGuide, translations, adapterLabel: 'MOCK_SOL_NOT_LIVE' });
    }
    if (req.url === '/mock/claude/review') {
      return json(res, 200, {
        batchId: input.batchId,
        locale: input.locale,
        contentHash: input.contentHash,
        reviewer: 'mock-adapter',
        passed: true,
        findings: [{ code: 'MOCK_REVIEW_ONLY', message: 'No factual or translation review was performed.' }],
      });
    }
    return json(res, 404, { error: 'fixed mock route not found' });
  } catch (error) {
    return json(res, 400, { error: error.message });
  }
});

server.listen(PORT, HOST, () => process.stdout.write(`Local mock adapters listening on http://${HOST}:${PORT}\n`));
