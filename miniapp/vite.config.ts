import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  return {
    plugins: [react()],
    server: {
      port: 5173
    },
    preview: {
      port: 5173
    },
    define: {
      "import.meta.env.VITE_API_BASE": JSON.stringify(env.VITE_API_BASE),
      "import.meta.env.VITE_APP_NAME": JSON.stringify(env.VITE_APP_NAME)
    }
  };
});
