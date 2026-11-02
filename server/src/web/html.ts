// The shared look of the Worker's pages (landing page, /terms, /privacy, /): no external resources, no cookies,
// no analytics.

import type { Config } from "../lib/config";

export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

const STYLE = `
:root{color-scheme:light dark;--bg:#f6f7f4;--fg:#1d2321;--muted:#5d6763;--card:#fff;--accent:#2f7c57;--line:#dfe3df}
@media (prefers-color-scheme:dark){:root{--bg:#141816;--fg:#e9ece9;--muted:#a3ada8;--card:#1d2321;--accent:#5fbf8c;--line:#2e3532}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
main{max-width:640px;margin:0 auto;padding:32px 16px 48px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:24px}
h1{font-size:1.5rem;line-height:1.25;margin:0 0 12px}
h2{font-size:1.15rem;margin:28px 0 8px}
p,li{margin:0 0 12px}
a{color:var(--accent)}
.button{display:block;text-align:center;background:var(--accent);color:#fff;text-decoration:none;font-weight:600;padding:14px 16px;border-radius:12px;margin:20px 0}
.stores a{display:block;margin:6px 0}
.small{font-size:.875rem;color:var(--muted)}
footer{margin-top:24px;font-size:.875rem;color:var(--muted);text-align:center}
[hidden]{display:none!important}
`;

export function footer(cfg: Config): string {
  const email = escapeHtml(cfg.contactEmail);
  return `<footer><a href="/privacy">Privacy notice</a> · <a href="/terms">Terms of use</a> · <a href="mailto:${email}">${email}</a></footer>`;
}

export function page(cfg: Config, title: string, body: string, script?: string): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>${escapeHtml(title)}</title>
<style>${STYLE}</style>
</head>
<body>
<main>
${body}
${footer(cfg)}
</main>
${script === undefined ? "" : `<script>${script}</script>`}
</body>
</html>
`;
}

export function htmlResponse(html: string, status: number, headers: Record<string, string>): Response {
  return new Response(html, {
    status,
    headers: { "Content-Type": "text/html; charset=utf-8", "Referrer-Policy": "no-referrer", ...headers },
  });
}
