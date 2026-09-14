import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
    plugins: [react()],
    optimizeDeps: {
        // @apitomy/flow-ui depends on react-simple-code-editor, a CJS-only package
        // (no "module"/"exports" field) nested inside its own node_modules tree.
        // Vite's dependency scanner doesn't always discover such transitive CJS
        // deps automatically, which otherwise leaves a raw `require("react")` call
        // in the served/bundled output and crashes at runtime in the browser
        // ("Calling `require` for react in an environment that doesn't expose the
        // require function"). Forcing it into the pre-bundle step converts it to
        // ESM up front.
        include: ["react-simple-code-editor"],
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
