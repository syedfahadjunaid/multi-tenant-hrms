import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Must match the redirect URI registered for the "hrms-bff" client in Keycloak.
    port: 5280,
    strictPort: true,
    // Same origin in development too: everything dynamic goes to the BFF (published on
    // 127.0.0.1:8090 by Docker Compose). In the Docker image, nginx does this job (nginx.conf).
    proxy: {
      "/api": "http://localhost:8090",
      "/oauth2": "http://localhost:8090",
      "/login/oauth2": "http://localhost:8090",
      "/logout": "http://localhost:8090",
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
  },
});
