import type { NextConfig } from "next";

const nextConfig: NextConfig = {
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
