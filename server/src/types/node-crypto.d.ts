// The part of `node:crypto` the Apple verifier uses (workerd implements it under nodejs_compat). Declared here so
// the server needs no @types/node (SPEC §13.2: no dependencies beyond wrangler, the vitest plugin, vitest and
// TypeScript).
declare module "node:crypto" {
  export interface KeyObject {
    readonly type: string;
  }
  export class X509Certificate {
    constructor(buffer: ArrayBufferView | ArrayBuffer | string);
    readonly ca: boolean;
    readonly publicKey: KeyObject;
    checkIssued(otherCert: X509Certificate): boolean;
    verify(publicKey: KeyObject): boolean;
  }
}
