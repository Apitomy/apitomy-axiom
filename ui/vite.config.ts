import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
    plugins: [react()],
    resolve: {
        alias: {
            // Workaround for https://github.com/Apitomy/apitomy-flow/issues/21
            "@apitomy/flow-ui/style.css": path.resolve(
                __dirname, "node_modules/@apitomy/flow-ui/dist/index.css"
            ),
        },
    },
    server: {
        port: 9191,
        proxy: {
            "/api/v1/sse": {
                target: `http://localhost:${process.env.VITE_BACKEND_PORT || 9090}`,
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
                target: `http://localhost:${process.env.VITE_BACKEND_PORT || 9090}`,
                changeOrigin: true,
            },
        },
    },
});
