// The landing page (headers, CSP hash, token check), the well-known files from the variables, and the pages
// (SPEC §5.4, §5.5, §11.1).

import { describe, expect, it } from "vitest";
import { b64Encode } from "../../src/lib/b64url";
import { readConfig } from "../../src/lib/config";
import { sha256 } from "../../src/lib/hash";
import { landingPage, landingScript } from "../../src/web/landing";
import { homePage, privacyPage, termsPage } from "../../src/web/pages";
import { appleAppSiteAssociation, assetLinks } from "../../src/web/wellknown";
import { vectors } from "../helpers/crypto";

const cfg = readConfig({});

describe("landing page", () => {
  it("is a 200 page with the exact texts and headers", async () => {
    const res = await landingPage(vectors.invite.token, cfg);
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("text/html; charset=utf-8");
    expect(res.headers.get("Cache-Control")).toBe("no-store");
    expect(res.headers.get("Referrer-Policy")).toBe("no-referrer");
    expect(res.headers.get("X-Robots-Tag")).toBe("noindex");
    expect(res.headers.get("Set-Cookie")).toBeNull();
    const html = await res.text();
    expect(html).toContain("<title>Join a SatMap group</title>");
    expect(html).toContain("You&#39;re invited to a SatMap group".replace("&#39;", "'"));
    expect(html).toContain("Open this invite in the SatMap app to join. It works once and expires 7 days after it was sent.");
    expect(html).toContain(">Open in SatMap</a>");
    expect(html).toContain('<p id="incomplete" hidden>This link is incomplete. Open it again from the message you received.</p>');
    expect(html).toContain("Don't have SatMap yet?");
    expect(html).toContain("After installing, go back to the message and tap the invite link again.");
    expect(html).toContain('<a href="/privacy">Privacy notice</a> · <a href="/terms">Terms of use</a> · <a href="mailto:groups@example.invalid">groups@example.invalid</a>');
    // No external resources and no token in the page itself.
    expect(html).not.toMatch(/(src|href)="https?:/);
    expect(html).not.toContain(vectors.invite.token);
  });

  it("allows exactly its one inline script by hash", async () => {
    const res = await landingPage(vectors.invite.token, cfg);
    const html = await res.text();
    const script = /<script>([\s\S]*)<\/script>/.exec(html)![1]!;
    expect(script).toBe(landingScript("com.jacobusoettle.satmap"));
    const hash = `sha256-${b64Encode(await sha256(script))}`;
    expect(res.headers.get("Content-Security-Policy")).toBe(
      `default-src 'none'; style-src 'unsafe-inline'; img-src data:; script-src '${hash}'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`,
    );
  });

  it("fills ANDROID_PACKAGE into the script, which reads the fragment once and clears it from history first", async () => {
    const script = landingScript("com.example.app");
    expect(script).toContain('var PACKAGE = "com.example.app";');
    const hashAt = script.indexOf("location.hash");
    const replaceAt = script.indexOf('history.replaceState(null, "", location.pathname)');
    expect(hashAt).toBeGreaterThan(0);
    expect(replaceAt).toBeGreaterThan(hashAt);
    expect(script.indexOf("location.hash", hashAt + 1)).toBe(-1);
    expect(script).toContain('"intent://join/" + token + "#Intent;scheme=satmap;package=" + PACKAGE + ";S.groupKey=" + key + ";end"');
    expect(script).toContain('"satmap://join/" + token + "#" + key');
    expect(script).toContain("/Android/.test(navigator.userAgent)");
    // A different package gives a different hash.
    const other = await landingPage(vectors.invite.token, readConfig({ ANDROID_PACKAGE: "com.example.app" }));
    const mine = await landingPage(vectors.invite.token, cfg);
    expect(other.headers.get("Content-Security-Policy")).not.toBe(mine.headers.get("Content-Security-Policy"));
  });

  it("answers 404 with the same look for a malformed token", async () => {
    for (const token of ["", "abc", `${vectors.invite.token}A`, vectors.invite.token.slice(1), `${vectors.invite.token.slice(0, 42)}=`]) {
      const res = await landingPage(token, cfg);
      expect(res.status, token).toBe(404);
      const html = await res.text();
      expect(html).toContain("This isn't a valid invite link.");
      expect(html).not.toContain("<script>");
      expect(res.headers.get("Cache-Control")).toBe("no-store");
      expect(res.headers.get("X-Robots-Tag")).toBe("noindex");
    }
  });

  it("shows each store link only when its variable is set", async () => {
    const none = await (await landingPage(vectors.invite.token, cfg)).text();
    expect(none).not.toContain("Download on the App Store");
    expect(none).not.toContain("Get it on Google Play");
    const both = await (
      await landingPage(
        vectors.invite.token,
        readConfig({ APP_STORE_URL: "https://apps.apple.com/app/id1", PLAY_STORE_URL: "https://play.google.com/store/apps/details?id=x&y=1" }),
      )
    ).text();
    expect(both).toContain('<a href="https://apps.apple.com/app/id1">Download on the App Store</a>');
    expect(both).toContain('<a href="https://play.google.com/store/apps/details?id=x&amp;y=1">Get it on Google Play</a>');
  });
});

describe("well-known files", () => {
  it("builds apple-app-site-association from APPLE_APP_IDS", async () => {
    const res = appleAppSiteAssociation(readConfig({ APPLE_APP_IDS: "TEAM1.com.a, TEAM2.com.b" }));
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("application/json");
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=3600");
    expect(await res.json()).toEqual({
      applinks: { details: [{ appIDs: ["TEAM1.com.a", "TEAM2.com.b"], components: [{ "/": "/j/*", comment: "Group invites" }] }] },
    });
    expect(await appleAppSiteAssociation(cfg).text()).toBe(
      '{"applinks":{"details":[{"appIDs":["TEAMID.com.jacobusoettle.satmap"],"components":[{"/":"/j/*","comment":"Group invites"}]}]}}',
    );
  });

  it("builds assetlinks.json from ANDROID_PACKAGE and ANDROID_CERT_SHA256", async () => {
    const res = assetLinks(readConfig({ ANDROID_PACKAGE: "com.x", ANDROID_CERT_SHA256: "AA:BB,CC:DD" }));
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=3600");
    expect(await res.json()).toEqual([
      {
        relation: ["delegate_permission/common.handle_all_urls"],
        target: { namespace: "android_app", package_name: "com.x", sha256_cert_fingerprints: ["AA:BB", "CC:DD"] },
      },
    ]);
    expect((await assetLinks(cfg).json()) as unknown[]).toEqual([
      {
        relation: ["delegate_permission/common.handle_all_urls"],
        target: { namespace: "android_app", package_name: "com.jacobusoettle.satmap", sha256_cert_fingerprints: [] },
      },
    ]);
  });
});

describe("pages", () => {
  it("serves the privacy notice with the required points and the contact address", async () => {
    const html = await privacyPage(readConfig({ CONTACT_EMAIL: "help@example.org" })).text();
    for (const text of [
      "help@example.org",
      "Information Regulator",
      "72(1)(a)",
      "400 days",
      "30 days",
      "support code",
      "our server never receives it",
      "under 18",
      "Version 1",
      "Deleting the app doesn't take you out of a group",
      "reinstalling the app restores your groups",
    ]) {
      expect(html, text).toContain(text);
    }
  });

  it("serves the terms and the home page", async () => {
    const terms = await termsPage(cfg).text();
    for (const text of ["rules about sharing wildlife sightings", "South Africa", "emergency", "pauses", "groups@example.invalid"]) expect(terms, text).toContain(text);
    expect((await homePage(cfg).text())).toContain("SatMap groups");
  });
});
