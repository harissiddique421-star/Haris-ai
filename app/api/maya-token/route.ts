import { GoogleGenAI } from "@google/genai";
import { NextRequest, NextResponse } from "next/server";
import { allow, bearer, clientIp, codeMatches, supabaseUserOk } from "../../../lib/guard";

export const runtime = "nodejs";

// Each token opens a Gemini Live session billed to this project's key. The caller must be signed in
// with Supabase (SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY) or send the access code (MAYA_ACCESS_CODE),
// and is limited to 5 tokens a minute per address.
export async function POST(request: NextRequest) {
  try {
    const token = bearer(request.headers.get("authorization"));
    const accessCode = process.env.MAYA_ACCESS_CODE;
    const supabaseReady = !!(process.env.SUPABASE_URL && process.env.SUPABASE_PUBLISHABLE_KEY);
    if (!supabaseReady && !accessCode) {
      return NextResponse.json({ error: "Maya is locked: set up Supabase sign-in or MAYA_ACCESS_CODE in Vercel." }, { status: 503 });
    }
    const codeOk = !!accessCode && codeMatches(request.headers.get("x-maya-access-code") ?? "", accessCode);
    const signedIn = !codeOk && supabaseReady && !!token && (await supabaseUserOk(token));
    if (!codeOk && !signedIn) return NextResponse.json({ error: "Please sign in again." }, { status: 401 });

    if (!(await allow("maya-token", clientIp(request.headers.get("x-forwarded-for")), 5))) {
      return NextResponse.json({ error: "Too many requests. Please wait a minute." }, { status: 429 });
    }

    const apiKey = process.env.GEMINI_API_KEY;
    if (!apiKey) return NextResponse.json({ error: "GEMINI_API_KEY is missing in Vercel environment variables." }, { status: 500 });
    const ai = new GoogleGenAI({ apiKey, httpOptions: { apiVersion: "v1alpha" } });
    const model = process.env.GEMINI_LIVE_MODEL || "gemini-2.5-flash-native-audio-preview-12-2025";
    const live = await ai.authTokens.create({ config: { uses: 1, expireTime: new Date(Date.now() + 30 * 60 * 1000).toISOString(), newSessionExpireTime: new Date(Date.now() + 60 * 1000).toISOString(), liveConnectConstraints: { model, config: { responseModalities: ["AUDIO" as any] } } } });
    if (!live.name) return NextResponse.json({ error: "Google did not return an ephemeral token." }, { status: 502 });
    return NextResponse.json({ token: live.name, model });
  } catch (error) {
    console.error("Maya token creation failed:", error);
    return NextResponse.json({ error: "Could not start Maya voice session. Check Vercel logs and Live API model access." }, { status: 500 });
  }
}
