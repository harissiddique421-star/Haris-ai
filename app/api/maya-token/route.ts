import { GoogleGenAI } from "@google/genai";
import { NextRequest, NextResponse } from "next/server";
import { timingSafeEqual } from "node:crypto";

export const runtime = "nodejs";

// Each token opens a Gemini Live session billed to this project's key, so the route needs an
// access code (MAYA_ACCESS_CODE in Vercel) and is limited per address. The in-memory limit is
// per server instance: it slows abuse down; the access code is what keeps strangers out.
const LIMIT_PER_MINUTE = 5;
const buckets = new Map<string, { count: number; resetAt: number }>();

function codeMatches(given: string, expected: string): boolean {
  const a = Buffer.from(given);
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}

export async function POST(request: NextRequest) {
  try {
    const accessCode = process.env.MAYA_ACCESS_CODE;
    if (!accessCode) return NextResponse.json({ error: "Maya is locked: add MAYA_ACCESS_CODE in Vercel environment variables." }, { status: 503 });
    const given = request.headers.get("x-maya-access-code") ?? "";
    if (!codeMatches(given, accessCode)) return NextResponse.json({ error: "Wrong or missing access code." }, { status: 401 });

    const ip = request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
    const now = Date.now();
    const bucket = buckets.get(ip);
    if (bucket && bucket.resetAt > now && bucket.count >= LIMIT_PER_MINUTE) {
      return NextResponse.json({ error: "Too many requests. Please wait a minute." }, { status: 429 });
    }
    buckets.set(ip, !bucket || bucket.resetAt <= now ? { count: 1, resetAt: now + 60_000 } : { ...bucket, count: bucket.count + 1 });

    const apiKey = process.env.GEMINI_API_KEY;
    if (!apiKey) return NextResponse.json({ error: "GEMINI_API_KEY is missing in Vercel environment variables." }, { status: 500 });
    const ai = new GoogleGenAI({ apiKey, httpOptions: { apiVersion: "v1alpha" } });
    const model = process.env.GEMINI_LIVE_MODEL || "gemini-2.5-flash-native-audio-preview-12-2025";
    const token = await ai.authTokens.create({ config: { uses: 1, expireTime: new Date(Date.now() + 30 * 60 * 1000).toISOString(), newSessionExpireTime: new Date(Date.now() + 60 * 1000).toISOString(), liveConnectConstraints: { model, config: { responseModalities: ["AUDIO" as any] } } } });
    if (!token.name) return NextResponse.json({ error: "Google did not return an ephemeral token." }, { status: 502 });
    return NextResponse.json({ token: token.name, model });
  } catch (error) {
    console.error("Maya token creation failed:", error);
    return NextResponse.json({ error: "Could not start Maya voice session. Check Vercel logs and Live API model access." }, { status: 500 });
  }
}
