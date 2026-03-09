import type { NextConfig } from "next";

const nextConfig: NextConfig = {
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
