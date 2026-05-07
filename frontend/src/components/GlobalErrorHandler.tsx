"use client";
import { useEffect } from "react";

export default function GlobalErrorHandler() {
  useEffect(() => {
    if (process.env.NODE_ENV !== "production") return;
    const handler = (event: PromiseRejectionEvent) => {
      event.preventDefault();
    };
    window.addEventListener("unhandledrejection", handler);
    return () => window.removeEventListener("unhandledrejection", handler);
  }, []);
  return null;
}
