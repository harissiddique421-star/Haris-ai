/** Protocol-only child fixture. No native desktop tools exist in this process. */
const http = require('node:http');
const token = process.env.MYRAA_AGENT_SESSION_TOKEN;
delete process.env.MYRAA_AGENT_SESSION_TOKEN;
let healthRequests = 0;
const server = http.createServer((request, response) => {
  response.setHeader('Content-Type', 'application/json');
  if (!token || request.headers.authorization !== `Bearer ${token}`) { response.writeHead(401); response.end('{"error":"Unauthenticated"}'); return; }
  if (request.url === '/health') {
    healthRequests++;
    if(process.argv.includes('--health-fail-once') && healthRequests===2){response.writeHead(503);response.end('{"error":"Fixture busy"}');return}
    response.end(JSON.stringify({ protocol: 2, authenticated: true, tool_count: 1, tools: ['fixture_only'] })); return;
  }
  response.end(JSON.stringify({ ok: true, result: 'Fixture response; no PC action.' }));
});
const delay = Number(process.argv.find(value => value.startsWith('--delay='))?.split('=')[1]) || 0;
setTimeout(() => server.listen(Number(process.env.MYRAA_AGENT_PORT), '127.0.0.1'), delay);
process.on('SIGTERM', () => server.close(() => process.exit(0)));
