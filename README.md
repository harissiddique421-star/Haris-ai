# Maya AI — Voice Prototype

This repository's Vercel web app is being switched to a Maya AI voice companion prototype. Existing Android source files are retained in the repository as a backup, but the Vercel app will use the Next.js app at the root.

## Required Vercel environment variables

- `GEMINI_API_KEY`: your Google AI Studio API key. Add it in Vercel Project Settings → Environment Variables; never commit it to GitHub.
- `GEMINI_LIVE_MODEL` (optional): a Live API model currently enabled for your API key. The current default in the API route may need updating based on your account's available models.

After adding variables, redeploy the project. Use Chrome on Android and allow microphone access. Voice and speech-recognition support varies by browser/device.

## Current limitations

This is an early voice prototype, not a complete JARVIS/Maya assistant. It does not yet implement persistent memory, vision, Android phone control, WhatsApp automation, wake phrase, or APK packaging. Do not expose the app publicly without adding authentication, rate limits, and usage controls. Gemini API usage may incur charges.


## Haris AI Android development branch

The native Android app is in `app/` (Kotlin + Jetpack Compose). The branch `feat/haris-ai-gemini-android` adds:
- A server-side Gemini chat endpoint at `/api/assistant/chat`; the Gemini API key stays on Vercel.
- Native Android chat requests through that endpoint and spoken replies through Android TextToSpeech.
- A GitHub Actions workflow that builds a debug APK and uploads it as an artifact.

Before testing the Gemini connection, add `GEMINI_API_KEY` to the Vercel project environment (Production and Preview). Do not commit the key or put it in Android source. The project currently has no Vercel environment variables configured. Deployment Protection/SSO must also permit the Android app to reach the API route; do not put a Vercel bypass secret in the APK.

This is an incremental first milestone. Always-on wake-word detection, robust background listening, full Android device actions, and a signed release APK are not implemented yet.
