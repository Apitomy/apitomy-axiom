import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
    plugins: [react()],
    server: {
        port: 9191,
        proxy: {
            "/api/v1/sse": {
                target: "http://localhost:9090",
                changeOrigin: true,
                // Required for SSE: disable response buffering
                configure: (proxy) => {
                    proxy.on("proxyRes", (proxyRes) => {
                        proxyRes.headers["cache-control"] = "no-cache";
                        proxyRes.headers["x-accel-buffering"] = "no";
                    });
                },
            },
            "/api": {
                target: "http://localhost:9090",
                changeOrigin: true,
            },
        },
    },
});
