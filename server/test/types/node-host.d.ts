// The few Node APIs that vitest.config.ts and the examples reporter use. They run in Node, not in workerd; declared
// here so the server needs no @types/node (SPEC §13.2).
declare module "node:fs" {
  export function readFileSync(path: string | URL, encoding: "utf8"): string;
  export function writeFileSync(path: string | URL, data: string): void;
}

interface ImportMeta {
  readonly url: string;
}
