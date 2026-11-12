// /privacy, /terms and / (SPEC §11.1). Drafted from the points in the spec; the owner reviews both texts before
// release (a release blocker, §14 R1).

import type { Config } from "../lib/config";
import { escapeHtml, htmlResponse, page } from "./html";

/** The responsible party named in the privacy notice (POPIA). The owner confirms it in the review. */
export const RESPONSIBLE_PARTY = "Jacobus Oettle, the developer of Spoor";
export const NOTICE_VERSION = "Version 1, 2 November 2026";

const HEADERS = {
  "Cache-Control": "public, max-age=3600",
  "Content-Security-Policy":
    "default-src 'none'; style-src 'unsafe-inline'; img-src data:; script-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
};

function mail(cfg: Config): string {
  const email = escapeHtml(cfg.contactEmail);
  return `<a href="mailto:${email}">${email}</a>`;
}

export function privacyPage(cfg: Config): Response {
  const body = `<div class="card">
<h1>Spoor groups: privacy notice</h1>
<p class="small">${NOTICE_VERSION}</p>
<p>This notice explains what happens to your information when you use groups in Spoor: sharing your position,
trail and pins with the members of a group. The rest of Spoor keeps your data on your phone.</p>

<h2>Who is responsible</h2>
<p>${escapeHtml(RESPONSIBLE_PARTY)}. Contact: ${mail(cfg)}.</p>

<h2>What we process</h2>
<ul>
<li>Random ids for each group, member and pin, your role in a group, the time you joined, and a hash of your
member pass (a secret your phone makes; we never store the pass itself).</li>
<li>Encrypted names, positions, trails and pins. They are encrypted on the phones with the group's key before they
reach us, and we cannot read them.</li>
<li>When positions and trails were uploaded, and how large the encrypted records are.</li>
<li>Pro purchase records: the Apple transaction id or Google purchase token, the product and when it ends.</li>
<li>Reports you send: a decrypted copy of the reported name or text, the reason and your comment (never positions),
plus what our server knows about the reported item itself: when it was made, by which member id, and its size.</li>
<li>The ids of removed pins, trails and members, kept for 400 days so other phones can delete their copies.</li>
<li>IP addresses, for security and to limit abuse. We don't store or log them; Cloudflare processes them at its
network edge.</li>
</ul>

<h2>Why</h2>
<p>To run groups, to check Spoor Pro, to handle reports, and for security and abuse prevention.</p>

<h2>Lawful basis</h2>
<p>Your consent for sharing your location (asked in the app before sharing starts); performing the service you
asked for; and our legitimate interest in keeping the service secure.</p>

<h2>Who receives what</h2>
<ul>
<li>The members of your group: they hold the group's key and can read what is shared in the group.</li>
<li>Cloudflare, which hosts the service as our processor.</li>
<li>Apple and Google, to check purchases.</li>
</ul>
<p>We don't sell your information, show advertising or track you.</p>

<h2>Where it is stored</h2>
<p>On Cloudflare's servers outside South Africa (there is no South African data location option). The transfer is
made under section 72(1)(a) of POPIA: Cloudflare is bound by its data processing addendum, which gives an adequate
level of protection.</p>

<h2>How long we keep it</h2>
<ul>
<li>Trails are kept while the group exists, unless you clear your trail, leave or are removed.</li>
<li>Animal sightings disappear after 12 hours. Other pins stay until they are deleted, or until the person who
dropped them leaves or is removed.</li>
<li>A live position is replaced by the next one and deleted when you stop sharing or the group is paused. It is not
shown after 24 hours, and old ones are deleted after 30 days.</li>
<li>Your membership is kept until you leave or are removed.</li>
<li>When you clear your trail, leave or are removed, your archived trail is deleted at once.</li>
<li>Used, cancelled and expired invites are deleted after 30 days.</li>
<li>If a group's owner's Pro ends, the group is paused, and everything in it is deleted 30 days later unless it
comes back first. A deleted group's content is deleted at once.</li>
<li>Reports are kept for 180 days after they are dealt with, and open reports for at most a year.</li>
<li>Purchase records are kept while a group uses the purchase, and deleted 30 days after the last group stops
using it.</li>
</ul>
<p>Deleting the app doesn't take you out of a group; your trail stays until you leave or clear it. On iPhone,
reinstalling the app restores your groups from the phone's keychain.</p>

<h2>Your rights</h2>
<ul>
<li>Access and correction: change your name in the app, or write to ${mail(cfg)}.</li>
<li>Deletion: use Clear my trail, Leave group or Delete group in the app, or write to us.</li>
<li>Objection and withdrawing consent: switch sharing off (and clear your trail).</li>
<li>You may complain to the Information Regulator (<a href="https://inforegulator.org.za">inforegulator.org.za</a>).</li>
</ul>
<p>We can't tell who you are from our data. When you write about your data, quote the support code shown at the
bottom of the group's screen.</p>

<h2>Security</h2>
<p>Everything a group shares is end-to-end encrypted. The group key travels only inside the invite links you send;
our server never receives it. Member passes are stored only as hashes. Please note: anyone who ever had an invite
link to a group (even a used, cancelled or expired one) keeps that group's key.</p>

<h2>Children</h2>
<p>Groups are not meant for people under 18 without a parent's or guardian's permission. The app asks you to confirm
this before you first use groups.</p>
</div>`;
  return htmlResponse(page(cfg, "Privacy notice · Spoor groups", body), 200, HEADERS);
}

export function termsPage(cfg: Config): Response {
  const body = `<div class="card">
<h1>Spoor groups: terms of use</h1>
<p class="small">${NOTICE_VERSION}</p>

<h2>Who may use groups</h2>
<p>You may use groups if you are 18 or older, or have a parent's or guardian's permission. You join a group only
through an invite from one of its owners or admins.</p>

<h2>Acceptable use</h2>
<p>Don't use groups for anything illegal, hateful, harassing, sexual, dangerous or for spam. Follow the law and park
rules, including any rules about sharing wildlife sightings.</p>

<h2>Reports and removal</h2>
<p>You can report any member, pin or group name, and block members on your phone. A group's owner and admins can
remove members and delete pins. We review reports and may delete content, remove members or delete groups that
break these terms.</p>

<h2>Spoor Pro</h2>
<p>Only a group's owner needs Spoor Pro; members join free. Subscriptions renew through the App Store or Google
Play until you cancel them there. If the owner's Pro ends, the group pauses at once: no live sharing and no new pins.
It resumes when the owner renews or hands the group to a member who has Pro. A group that stays paused is deleted
after 30 days.</p>

<h2>No guarantee</h2>
<p>Positions can be wrong or late, and the service can be unavailable. Don't rely on groups in an emergency.</p>

<h2>Ending use</h2>
<p>You can leave a group at any time (an owner hands over ownership or deletes the group first). We may end access
for anyone who breaks these terms.</p>

<h2>Contact</h2>
<p>${mail(cfg)}</p>

<h2>Law</h2>
<p>These terms are governed by the law of South Africa.</p>
</div>`;
  return htmlResponse(page(cfg, "Terms of use · Spoor groups", body), 200, HEADERS);
}

export function homePage(cfg: Config): Response {
  const stores: string[] = [];
  if (cfg.appStoreUrl !== "") stores.push(`<a href="${escapeHtml(cfg.appStoreUrl)}">Download on the App Store</a>`);
  if (cfg.playStoreUrl !== "") stores.push(`<a href="${escapeHtml(cfg.playStoreUrl)}">Get it on Google Play</a>`);
  const body = `<div class="card">
<h1>Spoor groups</h1>
<p>Spoor is a satellite map for drone teams and friends on the road. In a group, members see each other's live
position, trails and pins on the map. Everything a group shares is encrypted on the phones; this server only
stores and forwards it.</p>
${stores.length > 0 ? `<p class="stores">${stores.join("\n")}</p>` : ""}
</div>`;
  return htmlResponse(page(cfg, "Spoor groups", body), 200, HEADERS);
}
