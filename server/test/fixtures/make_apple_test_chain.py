#!/usr/bin/env python3
"""Makes the Apple-like test certificate chain for the JWS verifier tests (SPEC §12.1).

Run once from server/: python3 test/fixtures/make_apple_test_chain.py
It writes PEM files into test/fixtures/apple/, which are committed. Nothing here is a real Apple certificate: the
tests pass test root's DER to the Worker through APPLE_ROOT_OVERRIDE_B64 (honoured only under TEST_MODE=1).
With --forged-only it writes just the two forged_*.pem files, from the committed root.pem, inter.pem and leaf.pem,
and leaves everything else (and so example-jws.json and api-examples.json) as it is.

Files:
  root.pem                test root CA (P-384), the pinned root in tests
  inter.pem               intermediate CA with the Apple intermediate OID 1.2.840.113635.100.6.2.1
  leaf.pem, leaf.key      signing leaf (P-256) with the Apple leaf OID 1.2.840.113635.100.6.11.1; the key signs JWS
  leaf_no_oid.pem         the same leaf key, without the leaf OID
  leaf_oid_in_name.pem    the same leaf key; the leaf OID's bytes appear only as an attribute type in its subject
  inter_not_ca.pem        an intermediate with the OID but basicConstraints CA:FALSE
  leaf_under_not_ca.pem   the same leaf key, issued by inter_not_ca
  inter_no_oid.pem        an intermediate CA without the intermediate OID
  leaf_under_no_oid.pem   the same leaf key, issued by inter_no_oid
  other_root.pem          another self-signed root (the "wrong root" case)
  forged_inter.pem        an intermediate CA with the intermediate OID whose subject and issuer copy inter.pem's, so
                          its names match the root, but signed by an unrelated key instead of the root's
  forged_leaf.pem         the leaf key with the leaf OID, subject and issuer copied from leaf.pem, signed by the same
                          unrelated key: under inter.pem its names match but its signature doesn't; under
                          forged_inter.pem it is properly issued, so only the intermediate's signature is wrong
  example-jws.json        two fixed signed transactions for api-examples.json (ECDSA signatures are random, so the
                          integration tests use these to keep the examples file stable): an active yearly purchase
                          and an expired one
All certificates are valid from 2026-01-01 to 2046-12-31.
"""

import base64
import datetime
import json
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
from cryptography.x509.oid import NameOID

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apple")
LEAF_OID = x509.ObjectIdentifier("1.2.840.113635.100.6.11.1")
INTER_OID = x509.ObjectIdentifier("1.2.840.113635.100.6.2.1")
NOT_BEFORE = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
NOT_AFTER = datetime.datetime(2046, 12, 31, 23, 59, 59, tzinfo=datetime.timezone.utc)
DER_NULL = b"\x05\x00"


def name(common_name, extra=None):
    attrs = [x509.NameAttribute(NameOID.COMMON_NAME, common_name),
             x509.NameAttribute(NameOID.ORGANIZATION_NAME, "SatMap test")]
    if extra is not None:
        attrs.append(extra)
    return x509.Name(attrs)


def cert(subject, issuer, public_key, signing_key, ca, oid=None, serial=1):
    builder = (x509.CertificateBuilder()
               .subject_name(subject)
               .issuer_name(issuer)
               .public_key(public_key)
               .serial_number(serial)
               .not_valid_before(NOT_BEFORE)
               .not_valid_after(NOT_AFTER)
               .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True))
    if oid is not None:
        builder = builder.add_extension(x509.UnrecognizedExtension(oid, DER_NULL), critical=False)
    algorithm = hashes.SHA384() if isinstance(signing_key.curve, ec.SECP384R1) else hashes.SHA256()
    return builder.sign(signing_key, algorithm)


def write_cert(file_name, certificate):
    with open(os.path.join(OUT, file_name), "wb") as f:
        f.write(certificate.public_bytes(serialization.Encoding.PEM))


def read_cert(file_name):
    with open(os.path.join(OUT, file_name), "rb") as f:
        return x509.load_pem_x509_certificate(f.read())


def write_forged(root, inter, leaf):
    """Copies of the chain's names, signed by a key that is neither the root's nor the intermediate's."""
    forger_key = ec.generate_private_key(ec.SECP384R1())
    forged_inter = cert(inter.subject, root.subject, forger_key.public_key(), forger_key, ca=True, oid=INTER_OID,
                        serial=40)
    forged_leaf = cert(leaf.subject, inter.subject, leaf.public_key(), forger_key, ca=False, oid=LEAF_OID, serial=41)
    write_cert("forged_inter.pem", forged_inter)
    write_cert("forged_leaf.pem", forged_leaf)


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def jws(payload, leaf_key, x5c):
    header = b64url(json.dumps({"alg": "ES256", "x5c": x5c}, separators=(",", ":")).encode())
    body = b64url(json.dumps(payload, separators=(",", ":")).encode())
    r, s = decode_dss_signature(leaf_key.sign(f"{header}.{body}".encode(), ec.ECDSA(hashes.SHA256())))
    return f"{header}.{body}.{b64url(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


def example_transaction(transaction_id, expires_ms):
    # Times around the vectors' window (2 November 2026); see test/integration/api-examples.test.ts.
    return {
        "transactionId": transaction_id,
        "originalTransactionId": transaction_id,
        "bundleId": "com.jacobusoettle.satmap",
        "productId": "pro.yearly",
        "purchaseDate": 1793600000000,
        "originalPurchaseDate": 1793600000000,
        "expiresDate": expires_ms,
        "quantity": 1,
        "type": "Auto-Renewable Subscription",
        "inAppOwnershipType": "PURCHASED",
        "signedDate": 1793612000000,
        "environment": "Production",
        "storefront": "ZAF",
    }


def main():
    os.makedirs(OUT, exist_ok=True)
    root_key = ec.generate_private_key(ec.SECP384R1())
    other_root_key = ec.generate_private_key(ec.SECP384R1())
    inter_key = ec.generate_private_key(ec.SECP384R1())
    leaf_key = ec.generate_private_key(ec.SECP256R1())

    root_name = name("SatMap Test Root CA - G3")
    root = cert(root_name, root_name, root_key.public_key(), root_key, ca=True, serial=10)
    other_name = name("SatMap Other Root CA")
    other_root = cert(other_name, other_name, other_root_key.public_key(), other_root_key, ca=True, serial=11)

    inter_name = name("SatMap Test WWDR CA")
    inter = cert(inter_name, root_name, inter_key.public_key(), root_key, ca=True, oid=INTER_OID, serial=20)
    not_ca_name = name("SatMap Test Not A CA")
    inter_not_ca = cert(not_ca_name, root_name, inter_key.public_key(), root_key, ca=False, oid=INTER_OID, serial=21)
    no_oid_name = name("SatMap Test CA Without OID")
    inter_no_oid = cert(no_oid_name, root_name, inter_key.public_key(), root_key, ca=True, serial=22)

    leaf_name = name("SatMap Test StoreKit Signing")
    leaf = cert(leaf_name, inter_name, leaf_key.public_key(), inter_key, ca=False, oid=LEAF_OID, serial=30)
    leaf_no_oid = cert(leaf_name, inter_name, leaf_key.public_key(), inter_key, ca=False, serial=31)
    oid_name = name("SatMap Test OID In Name", x509.NameAttribute(LEAF_OID, "x"))
    leaf_oid_in_name = cert(oid_name, inter_name, leaf_key.public_key(), inter_key, ca=False, serial=32)
    leaf_under_not_ca = cert(leaf_name, not_ca_name, leaf_key.public_key(), inter_key, ca=False, oid=LEAF_OID,
                             serial=33)
    leaf_under_no_oid = cert(leaf_name, no_oid_name, leaf_key.public_key(), inter_key, ca=False, oid=LEAF_OID,
                             serial=34)

    write_cert("root.pem", root)
    write_cert("other_root.pem", other_root)
    write_cert("inter.pem", inter)
    write_cert("inter_not_ca.pem", inter_not_ca)
    write_cert("inter_no_oid.pem", inter_no_oid)
    write_cert("leaf.pem", leaf)
    write_cert("leaf_no_oid.pem", leaf_no_oid)
    write_cert("leaf_oid_in_name.pem", leaf_oid_in_name)
    write_cert("leaf_under_not_ca.pem", leaf_under_not_ca)
    write_cert("leaf_under_no_oid.pem", leaf_under_no_oid)
    with open(os.path.join(OUT, "leaf.key"), "wb") as f:
        f.write(leaf_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                       serialization.NoEncryption()))
    write_forged(root, inter, leaf)

    x5c = [base64.b64encode(c.public_bytes(serialization.Encoding.DER)).decode() for c in (leaf, inter, root)]
    examples = {
        "active": jws(example_transaction("2000000000000001", 1825149600000), leaf_key, x5c),
        "expired": jws(example_transaction("2000000000000002", 1793600100000), leaf_key, x5c),
    }
    with open(os.path.join(OUT, "example-jws.json"), "w") as f:
        json.dump(examples, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    if sys.argv[1:] == ["--forged-only"]:
        write_forged(read_cert("root.pem"), read_cert("inter.pem"), read_cert("leaf.pem"))
    elif sys.argv[1:]:
        sys.exit("usage: make_apple_test_chain.py [--forged-only]")
    else:
        main()
