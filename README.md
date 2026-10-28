# Maya AI — Voice Prototype

This repository's Vercel web app is being switched to a Maya AI voice companion prototype. Existing Android source files are retained in the repository as a backup, but the Vercel app will use the Next.js app at the root.

## Required Vercel environment variables

- `GEMINI_API_KEY`: your Google AI Studio API key. Add it in Vercel Project Settings → Environment Variables; never commit it to GitHub.
- `GEMINI_LIVE_MODEL` (optional): a Live API model currently enabled for your API key. The current default in the API route may need updating based on your account's available models.

After adding variables, redeploy the project. Use Chrome on Android and allow microphone access. Voice and speech-recognition support varies by browser/device.

## Current limitations

This is an early voice prototype, not a complete JARVIS/Maya assistant. It does not yet implement persistent memory, vision, Android phone control, WhatsApp automation, wake phrase, or APK packaging. Do not expose the app publicly without adding authentication, rate limits, and usage controls. Gemini API usage may incur charges.
