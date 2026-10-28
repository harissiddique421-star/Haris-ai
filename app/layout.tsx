import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = { title: "Maya AI — Voice Companion", description: "A secure-first Roman Urdu and English voice assistant prototype" };

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
