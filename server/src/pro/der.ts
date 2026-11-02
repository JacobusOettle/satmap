// A small DER reader for X.509 certificates (SPEC §7.6 step 4): enough to walk tbsCertificate, read the validity
// times, the subject public key info and the extension ids. Searching raw bytes is not enough: the same bytes could
// sit in a name or a key.

export interface Tlv {
  tag: number;
  /** The whole element: tag, length and content. */
  raw: Uint8Array;
  content: Uint8Array;
}

export class DerError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DerError";
  }
}

/** Reads one element at `offset`. Only low tag numbers (single tag byte) and definite lengths, as DER requires. */
export function readTlv(buf: Uint8Array, offset = 0): Tlv {
  if (offset + 2 > buf.length) throw new DerError("truncated");
  const tag = buf[offset]!;
  if ((tag & 0x1f) === 0x1f) throw new DerError("high tag number");
  let len = buf[offset + 1]!;
  let header = 2;
  if (len & 0x80) {
    const count = len & 0x7f;
    if (count === 0 || count > 4) throw new DerError("bad length");
    if (offset + 2 + count > buf.length) throw new DerError("truncated");
    len = 0;
    for (let i = 0; i < count; i++) len = len * 256 + buf[offset + 2 + i]!;
    header += count;
  }
  const end = offset + header + len;
  if (end > buf.length) throw new DerError("truncated");
  return { tag, raw: buf.subarray(offset, end), content: buf.subarray(offset + header, end) };
}

/** The elements inside a constructed element. */
export function children(tlv: Tlv): Tlv[] {
  const out: Tlv[] = [];
  let offset = 0;
  while (offset < tlv.content.length) {
    const child = readTlv(tlv.content, offset);
    out.push(child);
    offset += child.raw.length;
  }
  return out;
}

const SEQUENCE = 0x30;
const UTC_TIME = 0x17;
const GENERALIZED_TIME = 0x18;
const OID = 0x06;
const EXTENSIONS = 0xa3; // [3] EXPLICIT
const VERSION = 0xa0; // [0] EXPLICIT

function parseTime(tlv: Tlv): number {
  const text = new TextDecoder().decode(tlv.content);
  let m: RegExpExecArray | null;
  let year: number;
  if (tlv.tag === UTC_TIME && (m = /^(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})Z$/.exec(text))) {
    const yy = Number(m[1]);
    year = yy >= 50 ? 1900 + yy : 2000 + yy;
  } else if (tlv.tag === GENERALIZED_TIME && (m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})Z$/.exec(text))) {
    year = Number(m[1]);
  } else {
    throw new DerError("bad time");
  }
  return Date.UTC(year, Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]), Number(m[6])) / 1000;
}

export interface CertificateFields {
  /** Unix seconds. */
  notBefore: number;
  notAfter: number;
  /** DER SubjectPublicKeyInfo. */
  spki: Uint8Array;
  /** Each extension's extnID as a whole DER element (06 len bytes). */
  extensionIds: Uint8Array[];
}

export function parseCertificate(der: Uint8Array): CertificateFields {
  const cert = readTlv(der);
  if (cert.tag !== SEQUENCE || cert.raw.length !== der.length) throw new DerError("not a certificate");
  const [tbs] = children(cert);
  if (tbs === undefined || tbs.tag !== SEQUENCE) throw new DerError("no tbsCertificate");
  const fields = children(tbs);
  let i = fields[0]?.tag === VERSION ? 1 : 0;
  i += 3; // serialNumber, signature, issuer
  const validity = fields[i];
  const spki = fields[i + 2];
  if (validity?.tag !== SEQUENCE || spki?.tag !== SEQUENCE) throw new DerError("bad tbsCertificate");
  const [notBefore, notAfter] = children(validity);
  if (notBefore === undefined || notAfter === undefined) throw new DerError("bad validity");
  const extensionIds: Uint8Array[] = [];
  for (const field of fields.slice(i + 3)) {
    if (field.tag !== EXTENSIONS) continue;
    const [list] = children(field);
    if (list?.tag !== SEQUENCE) throw new DerError("bad extensions");
    for (const ext of children(list)) {
      if (ext.tag !== SEQUENCE) throw new DerError("bad extension");
      const [id] = children(ext);
      if (id?.tag !== OID) throw new DerError("bad extension id");
      extensionIds.push(id.raw);
    }
  }
  return { notBefore: parseTime(notBefore), notAfter: parseTime(notAfter), spki: spki.raw, extensionIds };
}
