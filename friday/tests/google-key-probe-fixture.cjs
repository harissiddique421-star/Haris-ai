// Test-process-only Google transport fixture; never included in packaged apps.
const originalFetch = globalThis.fetch;
globalThis.fetch = async (input, init) => {
  const url = new URL(typeof input === 'string' || input instanceof URL ? String(input) : input.url);
  if (url.hostname !== 'generativelanguage.googleapis.com') return originalFetch(input, init);
  if (!url.pathname.endsWith('/models')) throw new Error('Unexpected Google request in credential route test.');
  const headers = new Headers(init?.headers || input?.headers);
  const key = headers.get('x-goog-api-key') || '';
  if (url.searchParams.has('key')) throw new Error('Credential must not be put in the URL.');
  if (!key.startsWith('AQ.fixture_')) throw new Error('Only test credentials are accepted by this fixture.');
  let code = 200, status = 'OK', message = '', reason = '';
  if (key.includes('account')) { code = 401; status = 'UNAUTHENTICATED'; reason = 'ACCOUNT_STATE_INVALID'; }
  if (key.includes('suspended')) { code = 403; status = 'PERMISSION_DENIED'; reason = 'CONSUMER_SUSPENDED'; }
  if (key.includes('quota')) { code = 429; status = 'RESOURCE_EXHAUSTED'; message = 'API key request quota reached'; }
  if (reason) message = `Consumer api_key:${key} rejected: ${reason}`;
  return new Response(JSON.stringify(code === 200 ? { models: [{ name: 'models/fixture-model' }] }
    : { error: { code, status, message, details: reason ? [{ reason }] : [] } }),
    { status: code, headers: { 'Content-Type': 'application/json' } });
};
