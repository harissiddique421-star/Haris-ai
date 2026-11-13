"use client";

import { useEffect, useRef, useState } from "react";
import { GoogleGenAI, Modality } from "@google/genai";

const SYSTEM_PROMPT = `
You are Maya, a warm, expressive female AI assistant.
Speak naturally in Roman Urdu and English, matching the user's language.
Be friendly, calm, clear and conversational. Keep spoken replies concise.
Use natural pacing and intonation. Never claim to be human.
Do not claim to remember facts unless they are present in this conversation.
Ask for confirmation before sensitive actions.
`;

// Public Supabase settings (the publishable key is meant for browsers). When they are set the page
// signs in with Supabase; otherwise it asks for the MAYA_ACCESS_CODE.
const SUPABASE_URL = process.env.NEXT_PUBLIC_SUPABASE_URL || "";
const SUPABASE_KEY = process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY || "";

function decodePcm(base64: string): Int16Array {
  const binary = atob(base64);
  const output = new Int16Array(Math.floor(binary.length / 2));
  for (let i = 0; i < output.length; i++) {
    let value = binary.charCodeAt(i * 2) | (binary.charCodeAt(i * 2 + 1) << 8);
    if (value >= 32768) value -= 65536;
    output[i] = value;
  }
  return output;
}

export default function MayaPage() {
  const [status, setStatus] = useState("Ready to connect");
  const [busy, setBusy] = useState(false);
  const [heard, setHeard] = useState("");
  const [reply, setReply] = useState("");
  const [manualText, setManualText] = useState("");
  const [accessCode, setAccessCode] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [authToken, setAuthToken] = useState("");
  useEffect(() => {
    try {
      setAccessCode(localStorage.getItem("mayaAccessCode") || "");
      setAuthToken(sessionStorage.getItem("mayaSession") || "");
    } catch {}
  }, []);

  async function signIn() {
    if (!SUPABASE_URL || !SUPABASE_KEY) return;
    try {
      setBusy(true);
      setStatus("Signing in…");
      const res = await fetch(`${SUPABASE_URL}/auth/v1/token?grant_type=password`, {
        method: "POST",
        headers: { apikey: SUPABASE_KEY, "Content-Type": "application/json" },
        body: JSON.stringify({ email: email.trim(), password }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok || !data.access_token) throw new Error(data.error_description || data.msg || "Sign-in failed");
      setAuthToken(data.access_token);
      try { sessionStorage.setItem("mayaSession", data.access_token); } catch {}
      setPassword("");
      setStatus("Signed in. Tap Connect Maya.");
    } catch (e) {
      setStatus(e instanceof Error ? e.message : "Sign-in failed");
    } finally { setBusy(false); }
  }

  function signOut() {
    setAuthToken("");
    try { sessionStorage.removeItem("mayaSession"); } catch {}
    void disconnectMaya();
  }
  const sessionRef = useRef<any>(null);
  const audioRef = useRef<AudioContext | null>(null);
  const nextPlayRef = useRef(0);
  const recognitionRef = useRef<any>(null);

  async function connectMaya() {
    if (sessionRef.current) return;
    try {
      setBusy(true);
      setStatus("Connecting to Maya…");
      const headers: Record<string, string> = {};
      if (authToken) headers.Authorization = `Bearer ${authToken}`;
      if (accessCode) {
        headers["x-maya-access-code"] = accessCode;
        try { localStorage.setItem("mayaAccessCode", accessCode); } catch {}
      }
      const res = await fetch("/api/maya-token", { method: "POST", headers });
      const payload = await res.json();
      if (res.status === 401 && authToken) { setAuthToken(""); try { sessionStorage.removeItem("mayaSession"); } catch {} }
      if (!res.ok || !payload.token) throw new Error(payload.error || "Token setup failed");
      const ai = new GoogleGenAI({ apiKey: payload.token, httpOptions: { apiVersion: "v1alpha" } });
      const ctx = new AudioContext({ sampleRate: 24000 });
      await ctx.resume();
      audioRef.current = ctx;
      const session = await ai.live.connect({
        model: payload.model,
        config: {
          responseModalities: [Modality.AUDIO],
          systemInstruction: SYSTEM_PROMPT,
          speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: "Kore" } } },
        },
        callbacks: {
          onopen: () => setStatus("Connected — Maya is ready"),
          onerror: (e: any) => { console.error("Live API error", e); setStatus("Voice connection error — check deployment logs"); },
          onclose: () => { sessionRef.current = null; setStatus("Disconnected"); },
          onmessage: (message: any) => {
            const parts = message.serverContent?.modelTurn?.parts || [];
            for (const part of parts) {
              const encoded = part.inlineData?.data;
              if (!encoded || !audioRef.current) continue;
              const pcm = decodePcm(encoded);
              const audio = audioRef.current;
              const buffer = audio.createBuffer(1, pcm.length, 24000);
              const channel = buffer.getChannelData(0);
              for (let i = 0; i < pcm.length; i++) channel[i] = pcm[i] / 32768;
              const source = audio.createBufferSource();
              source.buffer = buffer;
              source.connect(audio.destination);
              const start = Math.max(audio.currentTime + 0.03, nextPlayRef.current);
              source.start(start);
              nextPlayRef.current = start + buffer.duration;
            }
            if (message.serverContent?.outputTranscription?.text) setReply((old) => old + message.serverContent.outputTranscription.text);
            if (message.serverContent?.turnComplete) setStatus("Connected — Maya is ready");
          },
        },
      });
      sessionRef.current = session;
    } catch (e) {
      console.error(e);
      setStatus(e instanceof Error ? e.message : "Could not connect");
    } finally { setBusy(false); }
  }

  function sendText(text: string) {
    if (!sessionRef.current || !text.trim()) return;
    setHeard(text);
    setReply("");
    setStatus("Maya is thinking…");
    sessionRef.current.sendRealtimeInput({ text });
  }

  function startMic() {
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognition) { setStatus("Speech recognition is not supported here. Type a message instead."); return; }
    if (!sessionRef.current) { setStatus("Connect Maya first."); return; }
    const recognition = new SpeechRecognition();
    recognition.lang = "ur-PK";
    recognition.continuous = false;
    recognition.interimResults = false;
    recognition.onstart = () => setStatus("Listening…");
    recognition.onerror = () => setStatus("Microphone error — try again or type instead.");
    recognition.onresult = (event: any) => sendText(event.results[0][0].transcript);
    recognition.onend = () => { recognitionRef.current = null; };
    recognitionRef.current = recognition;
    recognition.start();
  }

  async function disconnectMaya() {
    recognitionRef.current?.stop();
    try { sessionRef.current?.close(); } catch {}
    sessionRef.current = null;
    try { await audioRef.current?.close(); } catch {}
    audioRef.current = null;
    nextPlayRef.current = 0;
    setStatus("Disconnected");
    setReply("");
  }

  return (
    <main style={{ minHeight: "100vh", display: "grid", placeItems: "center", padding: 20 }}>
      <section style={{ width: "100%", maxWidth: 480, border: "1px solid #ffffff25", background: "#ffffff0c", borderRadius: 28, padding: 24, boxShadow: "0 24px 90px #0008" }}>
        <div style={{ textAlign: "center" }}>
          <div style={{ width: 88, height: 88, margin: "0 auto 14px", borderRadius: 999, display: "grid", placeItems: "center", background: "linear-gradient(135deg,#f0abfc,#8b5cf6,#ec4899)", boxShadow: "0 0 50px #c084fc55", fontSize: 38 }}>✦</div>
          <h1 style={{ fontSize: 34, margin: "8px 0" }}>Maya AI</h1>
          <p style={{ color: "#d8b4fe", marginTop: 0 }}>Your natural voice companion</p>
        </div>
        <div aria-live="polite" style={{ background: "#0003", borderRadius: 14, padding: 14, margin: "22px 0" }}>
          <small style={{ color: "#d8b4fe" }}>STATUS</small><div style={{ marginTop: 5 }}>{status}</div>
        </div>
        {SUPABASE_URL && SUPABASE_KEY ? (
          authToken ? (
            <p style={{ textAlign: "center", color: "#d8b4fe", margin: "0 0 10px" }}>Signed in · <button onClick={signOut} style={{ background: "transparent", color: "#ddd", border: 0, textDecoration: "underline" }}>Sign out</button></p>
          ) : (
            <form onSubmit={(e) => { e.preventDefault(); void signIn(); }} style={{ display: "grid", gap: 8, marginBottom: 10 }}>
              <input aria-label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="Email" autoComplete="email" style={field} />
              <input aria-label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Password" autoComplete="current-password" style={field} />
              <button disabled={busy || !email.trim() || !password} style={primary}>Sign in</button>
            </form>
          )
        ) : (
          <input aria-label="Access code" type="password" value={accessCode} onChange={(e) => setAccessCode(e.target.value)} placeholder="Access code" autoComplete="current-password" style={{ ...field, marginBottom: 10 }} />
        )}
        <div style={{ display: "grid", gap: 10 }}>
          <button disabled={busy || !!sessionRef.current} onClick={connectMaya} style={primary}>{busy ? "Connecting…" : "Connect Maya"}</button>
          <button disabled={!sessionRef.current} onClick={startMic} style={{ ...primary, background: "linear-gradient(90deg,#db2777,#9333ea)" }}>🎙️ Talk to Maya</button>
        </div>
        <form onSubmit={(e) => { e.preventDefault(); sendText(manualText); setManualText(""); }} style={{ display: "flex", gap: 8, marginTop: 14 }}>
          <input aria-label="Type to Maya" value={manualText} onChange={(e) => setManualText(e.target.value)} placeholder="Roman Urdu mein type karo…" style={{ minWidth: 0, flex: 1, padding: 12, borderRadius: 12, border: "1px solid #ffffff25", background: "#ffffff0d", color: "white" }} />
          <button disabled={!sessionRef.current || !manualText.trim()} style={{ ...primary, width: 78, padding: 10 }}>Send</button>
        </form>
        <div style={{ background: "#ffffff09", borderRadius: 16, padding: 16, marginTop: 18, minHeight: 120 }}>
          <small style={{ color: "#d8b4fe" }}>YOU SAID</small><p style={{ overflowWrap: "anywhere" }}>{heard || "Abhi conversation shuru nahi hui."}</p>
          <small style={{ color: "#d8b4fe" }}>MAYA</small><p style={{ overflowWrap: "anywhere" }}>{reply || "Maya ke jawab ka intezar hai…"}</p>
        </div>
        <button onClick={disconnectMaya} style={{ display: "block", margin: "18px auto 0", background: "transparent", color: "#ddd", border: 0, textDecoration: "underline" }}>Disconnect</button>
        <p style={{ color: "#aaa", fontSize: 12, textAlign: "center", lineHeight: 1.5 }}>Prototype: mic recognition support depends on your browser. Voice API usage may incur charges. Do not put secret API keys in this page.</p>
      </section>
    </main>
  );
}

const field: React.CSSProperties = { width: "100%", boxSizing: "border-box", padding: 12, borderRadius: 12, border: "1px solid #ffffff25", background: "#ffffff0d", color: "white" };
const primary: React.CSSProperties = { width: "100%", border: 0, borderRadius: 13, padding: 15, color: "white", fontWeight: 650, background: "linear-gradient(90deg,#9333ea,#7c3aed)" };
