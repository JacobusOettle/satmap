// Well-known files for universal links and Android App Links (SPEC §5.5), built from the variables.

import type { Config } from "../lib/config";

const HEADERS = { "Content-Type": "application/json", "Cache-Control": "public, max-age=3600" };

export function appleAppSiteAssociation(cfg: Config): Response {
  const body = {
    applinks: { details: [{ appIDs: cfg.appleAppIds, components: [{ "/": "/j/*", comment: "Group invites" }] }] },
  };
  return new Response(JSON.stringify(body), { status: 200, headers: HEADERS });
}

export function assetLinks(cfg: Config): Response {
  const body = [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: { namespace: "android_app", package_name: cfg.androidPackage, sha256_cert_fingerprints: cfg.androidCertSha256 },
    },
  ];
  return new Response(JSON.stringify(body), { status: 200, headers: HEADERS });
}
