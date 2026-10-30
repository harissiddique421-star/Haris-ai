import { GoogleGenAI } from "@google/genai";
import { NextRequest, NextResponse } from "next/server";

export const runtime = "nodejs";

const SYSTEM_INSTRUCTION = `
You are Haris AI, Haris Siddique's personal Android voice assistant.
Speak warmly and naturally, with a feminine conversational style, in the language the user uses.
Understand Roman Urdu, Urdu, Hindi, and English. Prefer concise spoken answers.
You are an AI assistant, not a human. Never claim an Android action was completed unless the Android app confirms it.
You cannot directly control the phone from this endpoint. For calls, messages, payments, deletion, or other sensitive actions, ask the user to confirm in the Android app.
Never ask for passwords, API keys, or OTPs.
`;

export async function POST(request: NextRequest) {
  try {
    const apiKey = process.env.GEMINI_API_KEY;
    if (!apiKey) {
      return NextResponse.json({ error: "Gemini is not configured on the server." }, { status: 503 });
    }

    const body: unknown = await request.json();
    if (!body || typeof body !== "object" || !("text" in body) || typeof body.text !== "string") {
      return NextResponse.json({ error: "A text field is required." }, { status: 400 });
    }

    const text = body.text.trim();
    if (!text || text.length > 1500) {
      return NextResponse.json({ error: "Message must contain 1–1500 characters." }, { status: 400 });
    }

    const ai = new GoogleGenAI({ apiKey });
    const response = await ai.models.generateContent({
      model: process.env.GEMINI_CHAT_MODEL || "gemini-2.5-flash",
      contents: text,
      config: {
        systemInstruction: SYSTEM_INSTRUCTION,
        maxOutputTokens: 300,
      },
    });

    const reply = response.text?.trim();
    if (!reply) {
      return NextResponse.json({ error: "Gemini returned an empty response." }, { status: 502 });
    }
    return NextResponse.json({ reply });
  } catch (error) {
    console.error("Haris AI chat failed:", error);
    return NextResponse.json({ error: "Haris AI could not reach Gemini. Please try again." }, { status: 502 });
  }
}
