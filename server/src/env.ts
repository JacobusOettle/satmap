// The Worker's environment: the generated bindings and variables (worker-configuration.d.ts, `npm run types`) plus
// the secrets of SPEC §7.8, which `wrangler types` can't see because they are never in wrangler.jsonc.

export interface AppEnv extends Env {
  ADMIN_TOKEN?: string;
  APPLE_ISSUER_ID?: string;
  APPLE_KEY_ID?: string;
  APPLE_PRIVATE_KEY?: string;
  GOOGLE_SERVICE_ACCOUNT_JSON?: string;
  GOOGLE_RTDN_TOKEN?: string;
  REPORT_WEBHOOK_URL?: string;
}
