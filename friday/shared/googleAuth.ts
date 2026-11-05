/** Safe, actionable Google errors. Never return provider text (it can echo a key). */
export function describeGoogleAuthError(value: unknown): { code: string; message: string } | null {
  const outer = value as { status?: number; message?: string; error?: unknown } | null;
  const raw = typeof value === 'string' ? value : String(outer?.message || '');
  let payload: any = outer;
  try { payload = JSON.parse(raw); } catch { /* SDK/network messages may be plain text. */ }
  const body = payload?.error || payload;
  const reason = Array.isArray(body?.details) ? body.details.find((detail: any) => typeof detail?.reason === 'string')?.reason : undefined;
  const status = Number(outer?.status || body?.code);
  // A quota or server error must never be reported as an invalid credential.
  if (status === 429 || status >= 500) return null;
  const known: Record<string, string> = {
    ACCOUNT_STATE_INVALID: 'Google reports that the service account linked to this key is disabled or deleted. Create a Gemini key linked to an active service account, or ask your Google Cloud project administrator to restore the account.',
    CONSUMER_SUSPENDED: 'Google reports that this API key’s consumer is suspended. Check the key and project status in Google AI Studio or Google Cloud. A suspended project must be resolved with Google before its keys will work.',
    API_KEY_INVALID: 'Google reports that this API key is invalid. Create a replacement in Google AI Studio and paste it only into MYRAA’s key field.',
    API_KEY_EXPIRED: 'Google reports that this API key has expired. Create a replacement in Google AI Studio.',
    API_KEY_SERVICE_BLOCKED: 'This key is not permitted to use the Generative Language API. Check its API restrictions and linked project in Google Cloud.',
    SERVICE_DISABLED: 'The Generative Language API is disabled for this project. Enable the Gemini API in the key’s Google Cloud project.',
    API_KEY_HTTP_REFERRER_BLOCKED: 'This key’s website restrictions do not match MYRAA’s local backend. Review its application restrictions for desktop use in Google Cloud.',
    API_KEY_IP_ADDRESS_BLOCKED: 'Google blocked the request because its IP address does not match this key’s restrictions. Review the allowed addresses in Google Cloud.',
  };
  const code = Object.keys(known).find(candidate => reason === candidate || raw.includes(candidate));
  if (code) return { code, message: known[code] };
  if (/reported as leaked|api.key.*(?:leaked|exposed)/i.test(raw)) return {
    code: 'API_KEY_LEAKED', message: 'Google blocked this API key as exposed. Create a replacement in Google AI Studio and disable the exposed key. Do not paste the replacement into chat.',
  };
  if ([401, 403].includes(status) || /api[_ ]?key (?:not valid|invalid)|api_key_invalid|permission_denied|unauthenticated/i.test(raw)) return {
    code: 'GOOGLE_AUTH_DENIED', message: 'Google denied authentication or access for this key. Check its project status, API restrictions and linked service-account permissions in Google AI Studio or Google Cloud.',
  };
  return null;
}
