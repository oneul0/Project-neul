import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import Link from "next/link";
import { Activity } from "lucide-react";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Neul Chat Explorer",
  description: "Explore Chzzk streamers based on real-time chat sentiment analysis.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko" className="dark">
      <body className={`${geistSans.variable} ${geistMono.variable} antialiased selection:bg-primary/30 selection:text-primary-400`}>
        <div className="flex flex-col min-h-screen">
          {/* Header */}
          <header className="sticky top-0 z-50 glass border-b border-card-border/50">
            <div className="container mx-auto px-4 h-16 flex items-center justify-between">
              <Link href="/" className="flex items-center gap-2 group transition-transform hover:scale-105 active:scale-95">
                <div className="bg-gradient-to-br from-emerald-400 to-blue-500 p-2 rounded-xl shadow-lg shadow-emerald-500/20">
                  <Activity className="w-5 h-5 text-white" />
                </div>
                <span className="text-xl font-bold tracking-tight text-white">
                  Neul <span className="text-gradient">Explorer</span>
                </span>
              </Link>
              <nav className="hidden md:flex items-center gap-6">
                <Link href="/" className="text-sm font-medium text-slate-300 hover:text-white transition-colors">
                  현재 라이브
                </Link>
                <a href="https://chzzk.naver.com" target="_blank" rel="noreferrer" className="text-sm font-medium text-slate-300 hover:text-white transition-colors">
                  치지직 바로가기
                </a>
              </nav>
            </div>
          </header>

          {/* Main Content */}
          <main className="flex-1 container mx-auto px-4 py-8">
            {children}
          </main>

          {/* Footer */}
          <footer className="py-6 border-t border-slate-800 text-center text-slate-500 text-sm">
            <p>© {new Date().getFullYear()} Project Neul. Not affiliated with Naver or Chzzk.</p>
          </footer>
        </div>
      </body>
    </html>
  );
}
