#!/usr/bin/env python3
"""Makes the Apple-like test certificate chain for the JWS verifier tests (SPEC §12.1).

Run once from server/: python3 test/fixtures/make_apple_test_chain.py
It writes PEM files into test/fixtures/apple/, which are committed. Nothing here is a real Apple certificate: the
tests pass test root's DER to the Worker through APPLE_ROOT_OVERRIDE_B64 (honoured only under TEST_MODE=1).

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
All certificates are valid from 2026-01-01 to 2046-12-31.
"""

import datetime
import os

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
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


if __name__ == "__main__":
    main()
