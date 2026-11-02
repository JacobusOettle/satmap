// Variables and secrets of SPEC §7.8, read once per use from the environment. Test-only variables are honoured only
// under TEST_MODE=1.

export interface Config {
  testMode: boolean;
  publicBaseUrl: string;
  contactEmail: string;
  appStoreUrl: string;
  playStoreUrl: string;
  appleBundleId: string;
  appleAppIds: string[];
  androidPackage: string;
  androidCertSha256: string[];
  proProductsApple: string[];
  proProductsGoogle: string[];
  appleAppAppleId: string;
  maxGroupsPerPurchase: number;
  termsVersion: number;
  allowTestPurchases: boolean;
  /** APPLE_ALLOW_XCODE, effective only under TEST_MODE=1. */
  appleAllowXcode: boolean;
  /** ALLOW_UNVERIFIED_PRO, effective only under TEST_MODE=1. */
  allowUnverifiedPro: boolean;
  /** APPLE_ROOT_OVERRIDE_B64, effective only under TEST_MODE=1; "" otherwise. */
  appleRootOverrideB64: string;
  adminToken: string;
  apple: { issuerId: string; keyId: string; privateKey: string } | null;
  googleServiceAccountJson: string;
  googleRtdnToken: string;
  reportWebhookUrl: string;
}

function text(env: Record<string, unknown>, key: string, fallback = ""): string {
  const value = env[key];
  return typeof value === "string" ? value.trim() : fallback;
}

function flag(env: Record<string, unknown>, key: string, fallback: boolean): boolean {
  const value = text(env, key);
  if (value === "") return fallback;
  return value === "true" || value === "1";
}

function list(env: Record<string, unknown>, key: string, fallback: string): string[] {
  return text(env, key, fallback)
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s !== "");
}

function int(env: Record<string, unknown>, key: string, fallback: number): number {
  const n = Number.parseInt(text(env, key), 10);
  return Number.isFinite(n) ? n : fallback;
}

export function readConfig(source: object): Config {
  const env = source as Record<string, unknown>;
  const testMode = flag(env, "TEST_MODE", false);
  const issuerId = text(env, "APPLE_ISSUER_ID");
  const keyId = text(env, "APPLE_KEY_ID");
  const privateKey = text(env, "APPLE_PRIVATE_KEY");
  return {
    testMode,
    publicBaseUrl: text(env, "PUBLIC_BASE_URL", "https://example.invalid").replace(/\/+$/, ""),
    contactEmail: text(env, "CONTACT_EMAIL", "groups@example.invalid"),
    appStoreUrl: text(env, "APP_STORE_URL"),
    playStoreUrl: text(env, "PLAY_STORE_URL"),
    appleBundleId: text(env, "APPLE_BUNDLE_ID", "com.jacobusoettle.satmap"),
    appleAppIds: list(env, "APPLE_APP_IDS", "TEAMID.com.jacobusoettle.satmap"),
    androidPackage: text(env, "ANDROID_PACKAGE", "com.jacobusoettle.satmap"),
    androidCertSha256: list(env, "ANDROID_CERT_SHA256", ""),
    proProductsApple: list(env, "PRO_PRODUCTS_APPLE", "pro.monthly,pro.yearly,pro.lifetime"),
    proProductsGoogle: list(env, "PRO_PRODUCTS_GOOGLE", "pro,pro_lifetime"),
    appleAppAppleId: text(env, "APPLE_APP_APPLE_ID"),
    maxGroupsPerPurchase: int(env, "MAX_GROUPS_PER_PURCHASE", 20),
    termsVersion: int(env, "TERMS_VERSION", 1),
    allowTestPurchases: flag(env, "ALLOW_TEST_PURCHASES", true),
    appleAllowXcode: testMode && flag(env, "APPLE_ALLOW_XCODE", false),
    allowUnverifiedPro: testMode && flag(env, "ALLOW_UNVERIFIED_PRO", false),
    appleRootOverrideB64: testMode ? text(env, "APPLE_ROOT_OVERRIDE_B64") : "",
    adminToken: text(env, "ADMIN_TOKEN"),
    apple: issuerId && keyId && privateKey ? { issuerId, keyId, privateKey } : null,
    googleServiceAccountJson: text(env, "GOOGLE_SERVICE_ACCOUNT_JSON"),
    googleRtdnToken: text(env, "GOOGLE_RTDN_TOKEN"),
    reportWebhookUrl: text(env, "REPORT_WEBHOOK_URL"),
  };
}
