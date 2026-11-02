// A fake Google Play Developer API for the tests: a generated service account and an httpFetch that answers the
// OAuth token endpoint and the purchases endpoints from a table. Nothing calls real Google.

import { b64urlDecode } from "../../src/lib/b64url";

export interface FakeGoogle {
  serviceAccountJson: string;
  calls: string[];
  /** Answers by purchase token: [HTTP status, body]. */
  purchases: Map<string, [number, unknown]>;
  fetch: (input: string, init?: RequestInit) => Promise<Response>;
  tokenRequests: number;
}

let keyPem: Promise<string> | null = null;

async function rsaPem(): Promise<string> {
  const pair = (await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
  const der = new Uint8Array((await crypto.subtle.exportKey("pkcs8", pair.privateKey)) as ArrayBuffer);
  let bin = "";
  for (const b of der) bin += String.fromCharCode(b);
  return `-----BEGIN PRIVATE KEY-----\n${btoa(bin)}\n-----END PRIVATE KEY-----\n`;
}

export async function fakeGoogle(clientEmail = `sa-${crypto.randomUUID()}@example.iam.gserviceaccount.com`): Promise<FakeGoogle> {
  keyPem ??= rsaPem();
  const fake: FakeGoogle = {
    serviceAccountJson: JSON.stringify({ type: "service_account", client_email: clientEmail, private_key: await keyPem }),
    calls: [],
    purchases: new Map(),
    tokenRequests: 0,
    fetch: async (input, init) => {
      const url = String(input);
      fake.calls.push(url);
      if (url === "https://oauth2.googleapis.com/token") {
        fake.tokenRequests += 1;
        const body = String(init?.body ?? "");
        const assertion = new URLSearchParams(body).get("assertion") ?? "";
        const claims = JSON.parse(new TextDecoder().decode(b64urlDecode(assertion.split(".")[1] ?? "") ?? new Uint8Array()));
        if (claims.scope !== "https://www.googleapis.com/auth/androidpublisher") return new Response("{}", { status: 400 });
        return Response.json({ access_token: "fake-access-token", expires_in: 3600, token_type: "Bearer" });
      }
      const m = /\/purchases\/(subscriptionsv2|productsv2)\/tokens\/([^/?]+)$/.exec(url);
      if (m) {
        if ((init?.headers as Record<string, string> | undefined)?.Authorization !== "Bearer fake-access-token") {
          return new Response("{}", { status: 401 });
        }
        const answer = fake.purchases.get(decodeURIComponent(m[2]!));
        if (answer === undefined) return new Response("{}", { status: 404 });
        return new Response(JSON.stringify(answer[1]), { status: answer[0] });
      }
      return new Response("not found", { status: 404 });
    },
  };
  return fake;
}

export function rfc3339(t: number): string {
  return new Date(t * 1000).toISOString();
}

export function subscription(now: number, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    kind: "androidpublisher#subscriptionPurchaseV2",
    startTime: rfc3339(now - 3600),
    regionCode: "ZA",
    subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
    acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    lineItems: [{ productId: "pro", expiryTime: rfc3339(now + 30 * 86400), offerDetails: { basePlanId: "yearly" } }],
    ...overrides,
  };
}

export function product(now: number, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    productLineItem: [{ productId: "pro_lifetime" }],
    purchaseStateContext: { purchaseState: "PURCHASED" },
    purchaseCompletionTime: rfc3339(now - 3600),
    acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    ...overrides,
  };
}

export function purchaseToken(): string {
  return `tok.${crypto.randomUUID()}.${crypto.randomUUID()}`;
}
