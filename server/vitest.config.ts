import { readFileSync } from "node:fs";
import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// The test root of test/fixtures/apple (made by make_apple_test_chain.py) replaces the pinned Apple root; the
// Worker honours APPLE_ROOT_OVERRIDE_B64 only under TEST_MODE=1.
const rootPem = readFileSync(new URL("./test/fixtures/apple/root.pem", import.meta.url), "utf8");
const rootB64 = rootPem.replace(/-----(BEGIN|END) CERTIFICATE-----/g, "").replace(/\s+/g, "");

export const TEST_BINDINGS = {
  TEST_MODE: "1",
  APPLE_ROOT_OVERRIDE_B64: rootB64,
  APPLE_APP_APPLE_ID: "1234567890",
  ADMIN_TOKEN: "test-admin-token-0123456789abcdefghijklmnop",
  GOOGLE_RTDN_TOKEN: "test-rtdn-token-0123456789abcdefghijklmnop",
};

const workers = () =>
  cloudflareTest({
    wrangler: { configPath: "./wrangler.jsonc" },
    miniflare: { bindings: TEST_BINDINGS },
  });

export default defineConfig({
  server: { fs: { allow: [".."] } },
  test: {
    reporters: ["default", "./test/helpers/examples-reporter.ts"],
    projects: [
      { plugins: [workers()], test: { name: "unit", include: ["test/unit/**/*.test.ts"] } },
      { plugins: [workers()], test: { name: "integration", include: ["test/integration/**/*.test.ts"], testTimeout: 30_000 } },
      // WebSockets with Durable Objects don't work under per-file storage isolation: run alone (npm run test:ws).
      { plugins: [workers()], test: { name: "ws", include: ["test/ws/**/*.test.ts"], testTimeout: 60_000 } },
    ],
  },
});
