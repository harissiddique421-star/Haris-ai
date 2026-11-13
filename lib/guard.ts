import { timingSafeEqual } from "node:crypto";
import { Ratelimit } from "@upstash/ratelimit";
import { Redis } from "@upstash/redis";

/** Constant-time comparison of a code the caller sent with the expected one. */
export function codeMatches(given: string, expected: string): boolean {
  const a = Buffer.from(given);
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}

/**
 * True when the bearer token is a live Supabase session for this project. Supabase Auth checks the
 * token on every call, so a signed-out or expired session is refused.
 */
export async function supabaseUserOk(accessToken: string): Promise<boolean> {
  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_PUBLISHABLE_KEY;
  if (!url || !key || !accessToken) return false;
  const res = await fetch(`${url}/auth/v1/user`, {
    headers: { apikey: key, Authorization: `Bearer ${accessToken}` },
    cache: "no-store",
  });
  return res.ok;
}

export function bearer(header: string | null): string {
  return header?.match(/^Bearer\s+(.+)$/i)?.[1] ?? "";
}

export function clientIp(forwardedFor: string | null): string {
  return forwardedFor?.split(",")[0]?.trim() || "unknown";
}

// Upstash Redis keeps one count across every server instance. Without it (UPSTASH_REDIS_REST_URL
// and UPSTASH_REDIS_REST_TOKEN unset) each instance counts on its own, which only slows abuse down.
const upstash = process.env.UPSTASH_REDIS_REST_URL && process.env.UPSTASH_REDIS_REST_TOKEN ? Redis.fromEnv() : null;
const limiters = new Map<string, Ratelimit>();
const local = new Map<string, { count: number; resetAt: number }>();

/** Allows `perMinute` requests per minute for this name and key (an IP or user). */
export async function allow(name: string, key: string, perMinute: number): Promise<boolean> {
  if (upstash) {
    let limiter = limiters.get(name);
    if (!limiter) {
      limiter = new Ratelimit({ redis: upstash, limiter: Ratelimit.slidingWindow(perMinute, "1 m"), prefix: `rl:${name}` });
      limiters.set(name, limiter);
    }
    try {
      return (await limiter.limit(key)).success;
    } catch (error) {
      console.error("Upstash rate limit unavailable, using the local limit", error instanceof Error ? error.message : "unknown");
    }
  }
  const id = `${name}:${key}`;
  const now = Date.now();
  const bucket = local.get(id);
  if (!bucket || bucket.resetAt <= now) {
    local.set(id, { count: 1, resetAt: now + 60_000 });
    return true;
  }
  if (bucket.count >= perMinute) return false;
  bucket.count++;
  return true;
}
