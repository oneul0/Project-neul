import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Docker 배포 시에만 standalone 모드 사용 (Vercel은 자체 최적화 사용)
  output: process.env.NEXT_STANDALONE === "true" ? "standalone" : undefined,
  compiler: {
    removeConsole: process.env.NODE_ENV === "production" ? { exclude: ["error"] } : false,
  },
  images: {
    remotePatterns: [
      {
        protocol: "https",
        hostname: "**", // Allowing all image domains since Chzzk thumbnails can come from multiple CDNs
      },
    ],
  },
};

export default nextConfig;
