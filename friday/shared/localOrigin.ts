/** The HTTP API and WebSockets belong to this app's exact loopback port. */
export function isLocalAppOrigin(origin: string | undefined, port: number): boolean {
  if (!origin) return true; // native main process and local CLI integrations
  try {
    const url = new URL(origin);
    return url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname)
      && Number(url.port || 80) === port && url.origin === origin;
  } catch { return false; }
}
export function isLocalAppHost(host: string | undefined, port: number): boolean {
  if (!host) return false;
  return isLocalAppOrigin(`http://${host}`, port);
}
