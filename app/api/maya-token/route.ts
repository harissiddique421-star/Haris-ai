import { GoogleGenAI } from "@google/genai";
import { NextResponse } from "next/server";

export const runtime = "nodejs";

export async function POST() {
  try {
    const apiKey = process.env.GEMINI_API_KEY;
    if (!apiKey) return NextResponse.json({ error: "GEMINI_API_KEY is missing in Vercel environment variables." }, { status: 500 });
    const ai = new GoogleGenAI({ apiKey, httpOptions: { apiVersion: "v1alpha" } });
    const model = process.env.GEMINI_LIVE_MODEL || "gemini-2.5-flash-native-audio-preview-12-2025";
    const token = await ai.authTokens.create({ config: { uses: 1, expireTime: new Date(Date.now() + 30 * 60 * 1000).toISOString(), newSessionExpireTime: new Date(Date.now() + 60 * 1000).toISOString(), liveConnectConstraints: { model, config: { responseModalities: ["AUDIO"] } } } });
    if (!token.name) return NextResponse.json({ error: "Google did not return an ephemeral token." }, { status: 502 });
    return NextResponse.json({ token: token.name, model });
  } catch (error) {
    console.error("Maya token creation failed:", error);
    return NextResponse.json({ error: "Could not start Maya voice session. Check Vercel logs and Live API model access." }, { status: 500 });
  }
}
