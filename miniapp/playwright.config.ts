import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "tests-e2e",
  timeout: 30_000,
  expect: {
    timeout: 3_000
  },
  use: {
    baseURL: "http://localhost:5173",
    trace: "retain-on-failure",
    video: "off"
  },
  reporter: [["list"], ["html", { open: "never" }]],
  webServer: {
    command: "pnpm preview",
    port: 5173,
    reuseExistingServer: process.env.CI ? false : true
  }
});
