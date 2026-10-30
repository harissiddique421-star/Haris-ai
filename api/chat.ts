type VercelRequest = { method?: string; body?: unknown };
type VercelResponse = {
  setHeader(name: string, value: string): void;
  status(code: number): VercelResponse;
  json(body: unknown): VercelResponse;
};

const SYSTEM_INSTRUCTION = `You are Haris AI, a warm, respectful female assistant for Haris. Speak naturally in Roman Urdu when the user speaks Roman Urdu, and match the user's language. Do not repeat the user's request as if it were an answer. Answer the actual question, be emotionally aware without pretending to have human feelings, and be concise. For phone actions such as messaging or calling, explain that the Android app must handle the action and require confirmation before sending.`;

export default async function handler(req: VercelRequest, res: VercelResponse) {
  res.setHeader("Cache-Control", "no-store");
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }

  const key = process.env.GEMINI_API_KEY;
  if (!key) return res.status(503).json({ error: "AI service is not configured yet." });

  const body = typeof req.body === "string" ? JSON.parse(req.body) : req.body;
  const message = typeof body?.message === "string" ? body.message.trim() : "";
  const history = Array.isArray(body?.history) ? body.history.slice(-12) : [];
  if (!message) return res.status(400).json({ error: "Message is required." });
  if (message.length > 8000) return res.status(413).json({ error: "Message is too long." });

  const contents = [
    ...history.filter((m: any) => (m?.role === "user" || m?.role === "model") && typeof m?.text === "string")
      .map((m: any) => ({ role: m.role, parts: [{ text: m.text.slice(0, 4000) }] })),
    { role: "user", parts: [{ text: message }] }
  ];

  try {
    const upstream = await fetch("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": key },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: SYSTEM_INSTRUCTION }] },
        contents,
        generationConfig: { temperature: 0.8, maxOutputTokens: 700 }
      })
    });
    const data: any = await upstream.json();
    if (!upstream.ok) {
      console.error("Gemini API returned status", upstream.status);
      return res.status(502).json({ error: "AI service request failed. Check the server configuration and Gemini quota." });
    }
    const reply = data?.candidates?.[0]?.content?.parts?.map((p: any) => p.text || "").join("").trim();
    if (!reply) return res.status(502).json({ error: "AI returned an empty response." });
    return res.status(200).json({ reply });
  } catch (error) {
    console.error("Gemini request failed", error instanceof Error ? error.message : "unknown");
    return res.status(502).json({ error: "Could not reach the AI service." });
  }
}
