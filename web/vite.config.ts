import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The Go binary embeds the build output with go:embed, which cannot reach
// outside its own package directory, so Vite writes straight into
// internal/webassets/dist.
const OUT_DIR = "../internal/webassets/dist";

// Development: `make dev-backend` pins the Go server to this port so the proxy
// target is stable. The backend must also be started with
// --dev-origin http://localhost:5173 so it accepts this page's Origin.
const DEV_BACKEND = "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: OUT_DIR,
    emptyOutDir: false, // keep dist/.gitkeep so go:embed always has a target
    sourcemap: false,
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": { target: DEV_BACKEND, changeOrigin: false },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
  },
});
