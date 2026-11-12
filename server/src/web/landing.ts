// The invite landing page, GET /j/{token} (SPEC §5.4). It never looks the token up and never consumes it, so
// link-preview robots (which fetch it without the fragment) learn nothing. The one inline script reads the key from
// the fragment, removes it from the address bar and history, and points the button at the app.

import { b64Encode } from "../lib/b64url";
import type { Config } from "../lib/config";
import { sha256 } from "../lib/hash";
import { escapeHtml, htmlResponse, page } from "./html";

export const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;

export function landingScript(androidPackage: string): string {
  return `(function () {
  var PACKAGE = ${JSON.stringify(androidPackage).replace(/</g, "\\u003c")};
  var hash = location.hash;
  history.replaceState(null, "", location.pathname);
  var token = location.pathname.split("/")[2] || "";
  var button = document.getElementById("open");
  var match = /^#([A-Za-z0-9_-]{43})$/.exec(hash);
  if (!match || !/^[A-Za-z0-9_-]{43}$/.test(token)) {
    button.hidden = true;
    document.getElementById("incomplete").hidden = false;
    return;
  }
  var key = match[1];
  if (/Android/.test(navigator.userAgent)) {
    button.href = "intent://join/" + token + "#Intent;scheme=satmap;package=" + PACKAGE + ";S.groupKey=" + key + ";end";
  } else {
    button.href = "satmap://join/" + token + "#" + key;
  }
})();`;
}

/** The script's CSP hash, computed once per isolate (per ANDROID_PACKAGE value). */
const hashes = new Map<string, Promise<string>>();

export function scriptHash(script: string): Promise<string> {
  let hash = hashes.get(script);
  if (hash === undefined) {
    hash = sha256(script).then((digest) => `sha256-${b64Encode(digest)}`);
    hashes.set(script, hash);
  }
  return hash;
}

function csp(scriptSource: string): string {
  return `default-src 'none'; style-src 'unsafe-inline'; img-src data:; script-src ${scriptSource}; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`;
}

const BASE_HEADERS = { "Cache-Control": "no-store", "X-Robots-Tag": "noindex" };

/** GET /j/{token}: the page (200), or the "not a valid invite link" page (404) when the token is malformed. */
export async function landingPage(rest: string, cfg: Config): Promise<Response> {
  const token = rest.endsWith("/") ? rest.slice(0, -1) : rest;
  if (!TOKEN_PATTERN.test(token)) {
    const body = `<div class="card"><h1>This isn't a valid invite link.</h1></div>`;
    return htmlResponse(page(cfg, "Join a Spoor group", body), 404, {
      ...BASE_HEADERS,
      "Content-Security-Policy": csp("'none'"),
    });
  }
  const script = landingScript(cfg.androidPackage);
  const stores: string[] = [];
  if (cfg.appStoreUrl !== "") stores.push(`<a href="${escapeHtml(cfg.appStoreUrl)}">Download on the App Store</a>`);
  if (cfg.playStoreUrl !== "") stores.push(`<a href="${escapeHtml(cfg.playStoreUrl)}">Get it on Google Play</a>`);
  const body = `<div class="card">
<h1>You're invited to a Spoor group</h1>
<p>Open this invite in the Spoor app to join. It works once and expires 7 days after it was sent.</p>
<a id="open" class="button">Open in Spoor</a>
<p id="incomplete" hidden>This link is incomplete. Open it again from the message you received.</p>
<p>Don't have Spoor yet?</p>
${stores.length > 0 ? `<p class="stores">${stores.join("\n")}</p>` : ""}
<p class="small">After installing, go back to the message and tap the invite link again.</p>
</div>`;
  return htmlResponse(page(cfg, "Join a Spoor group", body, script), 200, {
    ...BASE_HEADERS,
    "Content-Security-Policy": csp(`'${await scriptHash(script)}'`),
  });
}
