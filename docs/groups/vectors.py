#!/usr/bin/env python3
"""Makes the shared group test vectors (docs/groups/SPEC.md §2, §3.6, §4.8, §5.2, §8.12 and §9).

Run from the repository root:  python3 -I docs/groups/vectors.py
It writes Tests/SatMapCoreTests/Fixtures/groups/vectors.json, which the Swift core tests, the Kotlin core tests
and the server tests all read. Needs the `cryptography` package. The output is deterministic: run it again and
the file must not change (CI does not run it; the file is committed).

The text cases (groupText, wordFilter, initials) use only characters whose Unicode properties have not changed
since Unicode 9, so every platform's tables agree on them.
"""
import base64, datetime, hashlib, json, math, os, re, struct, sys, unicodedata, zlib

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def hkdf(key: bytes, salt: bytes, info: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=info).derive(key)


def subkey(group_key: bytes, group_id: bytes, purpose: str) -> bytes:
    return hkdf(group_key, group_id, ("satmap.group.v1|key|" + purpose).encode())


def seal(key: bytes, nonce: bytes, plain: bytes, aad: bytes) -> bytes:
    return bytes([1]) + nonce + AESGCM(key).encrypt(nonce, plain, aad)


def uvarint(v: int) -> bytes:
    assert 0 <= v < 2**32
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def zigzag(v: int) -> int:
    assert -2**31 <= v < 2**31
    return ((v << 1) ^ (v >> 31)) & 0xFFFFFFFF


def round_away(x: float) -> int:
    """Round half away from zero (§2.4)."""
    return int(math.copysign(math.floor(abs(x) + 0.5), x))


def e6(deg: float) -> int:
    return round_away(deg * 1e6)


def chunk_body(points, start):
    out = bytearray(uvarint(len(points)))
    pt, plat, plon = start, 0, 0
    for (t, lat, lon, seg) in points:
        dt = t - pt
        assert dt >= 0 and start <= t < start + 600
        out += uvarint((dt << 1) | (1 if seg else 0))
        out += uvarint(zigzag(lat - plat))
        out += uvarint(zigzag(lon - plon))
        pt, plat, plon = t, lat, lon
    return bytes(out)


def chunk_plain(points, start):
    return bytes([1, 0]) + chunk_body(points, start)


def raw_deflate(data: bytes) -> bytes:
    c = zlib.compressobj(level=9, method=zlib.DEFLATED, wbits=-15)
    return c.compress(data) + c.flush()


def chunk_deflated(points, start):
    body = chunk_body(points, start)
    return bytes([1, 1]) + uvarint(len(body)) + raw_deflate(body), body


def live_plain(t, lat, lon, acc=None, heading=None, speed=None):
    flags = (1 if acc is not None else 0) | (2 if heading is not None else 0) | (4 if speed is not None else 0)
    return struct.pack("<BBqiiHHH", 1, flags, t, lat, lon, acc or 0, heading or 0, speed or 0)


def js(obj) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def padded(obj, block: int) -> bytes:
    """§4.5–4.7: add "pad" (ASCII spaces) so the JSON length is a multiple of `block`."""
    o = dict(obj)
    o["pad"] = ""
    n = len(js(o))
    o["pad"] = " " * ((-n) % block)
    out = js(o)
    assert len(out) % block == 0
    return out


# ---- §2.5 text cleaning -------------------------------------------------------------------------------------------

WHITE_SPACE = set([*range(0x09, 0x0E), 0x20, 0x85, 0xA0, 0x1680, *range(0x2000, 0x200B), 0x2028, 0x2029, 0x202F,
                   0x205F, 0x3000])
LIMITS = {"groupName": (1, 40, 160), "displayName": (1, 30, 120), "animal": (1, 40, 160), "note": (0, 140, 560),
          "reportComment": (0, 500, 2000)}


def clean(text: str, field: str):
    s = unicodedata.normalize("NFC", text)
    cps = [ord(c) for c in s]
    while cps and cps[0] in WHITE_SPACE:
        cps.pop(0)
    while cps and cps[-1] in WHITE_SPACE:
        cps.pop()
    s = "".join(chr(c) for c in cps)
    newlines_ok = field in ("note", "reportComment")
    for c in cps:
        if c in (0x2028, 0x2029):
            return None
        if unicodedata.category(chr(c)) == "Cc" and not (c == 0x0A and newlines_ok):
            return None
    if newlines_ok and cps.count(0x0A) > 3:
        return None
    lo, hi, hib = LIMITS[field]
    if not (lo <= len(cps) <= hi) or len(s.encode()) > hib:
        return None
    return s


# ---- §8.12 word filter --------------------------------------------------------------------------------------------

DIGIT_MAP = {"0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "@": "a", "$": "s"}


def is_letter(ch: str) -> bool:
    return unicodedata.category(ch).startswith("L")


class WordFilter:
    def __init__(self, text: str):
        self.exact, self.prefix, self.contains = set(), [], []
        for line in text.split("\n"):
            e = line.strip(" \t\r")
            if not e or e.startswith("#"):
                continue
            if len(e) > 2 and e.startswith("*") and e.endswith("*"):
                self.contains.append(e[1:-1])
            elif len(e) > 1 and e.endswith("*"):
                self.prefix.append(e[:-1])
            else:
                self.exact.add(e)

    def _tokens(self, text: str):
        norm = []  # (normalised code point, index of the source code point)
        for i, c in enumerate(text):
            for d in unicodedata.normalize("NFKC", c).lower():
                norm.append((DIGIT_MAP.get(d, d), i))
        tokens, cur = [], []
        for (d, i) in norm + [(" ", -1)]:
            if is_letter(d):
                cur.append((d, i))
            elif cur:
                tokens.append(("".join(x for x, _ in cur), cur[0][1], cur[-1][1]))
                cur = []
        return tokens

    def _matches(self, token: str) -> bool:
        return token in self.exact or any(token.startswith(p) for p in self.prefix) or \
            any(w in token for w in self.contains)

    def allows(self, text: str) -> bool:
        tokens = self._tokens(text)
        letters = "".join(t for t, _, _ in tokens)
        return not any(self._matches(t) for t, _, _ in tokens) and not any(w in letters for w in self.contains)

    def masked(self, text: str) -> str:
        tokens = self._tokens(text)
        spans = sorted((a, b) for t, a, b in tokens if self._matches(t))
        if not spans:
            letters = "".join(t for t, _, _ in tokens)
            return "•••" if any(w in letters for w in self.contains) else text
        merged = []
        for a, b in spans:
            if merged and a <= merged[-1][1]:
                merged[-1][1] = max(merged[-1][1], b)
            else:
                merged.append([a, b])
        out, pos = [], 0
        for a, b in merged:
            out.append(text[pos:a])
            out.append("•••")
            pos = b + 1
        out.append(text[pos:])
        return "".join(out)


# ---- §9.3 times and §9.5 initials ---------------------------------------------------------------------------------

def ago(s: int) -> str:
    if s < 60:
        return "just now"
    if s < 3600:
        return f"{s // 60} min ago"
    if s < 172800:
        return f"{s // 3600} h ago"
    return f"{s // 86400} d ago"


def pictographic(c: int) -> bool:
    return 0x2600 <= c <= 0x27BF or 0x1F000 <= c <= 0x1FAFF


def emoji_head(cps):
    out = [cps[0]]
    i = 1
    if 0x1F1E6 <= cps[0] <= 0x1F1FF:
        if i < len(cps) and 0x1F1E6 <= cps[i] <= 0x1F1FF:
            out.append(cps[i])
        return out
    while i < len(cps):
        c = cps[i]
        if c in (0xFE0E, 0xFE0F, 0x20E3) or 0x1F3FB <= c <= 0x1F3FF or 0xE0020 <= c <= 0xE007F:
            out.append(c)
            i += 1
        elif c == 0x200D and i + 1 < len(cps):
            out += [c, cps[i + 1]]
            i += 2
        else:
            break
    return out


def initials(name: str) -> str:
    cps = [ord(c) for c in name]
    if cps and pictographic(cps[0]):
        return "".join(chr(c) for c in emoji_head(cps))
    words, cur = [], []
    for c in cps + [0x20]:
        if c in WHITE_SPACE:
            if cur:
                words.append(cur)
            cur = []
        else:
            cur.append(c)

    def first_alnum(w):
        for c in w:
            cat = unicodedata.category(chr(c))
            if cat.startswith("L") or cat == "Nd":
                return chr(c)
        return None

    firsts = [f for f in (first_alnum(w) for w in words) if f is not None]
    if not firsts:
        return "?"
    picked = firsts[:1] + (firsts[-1:] if len(firsts) >= 2 else [])
    return "".join(f.upper() for f in picked)


# ---- §4.4 live rounding -------------------------------------------------------------------------------------------

def live_from_fix(lat, lon, accuracy, course, speed, heading, time):
    acc = None if accuracy is None or accuracy < 0 else min(65535, round_away(accuracy))
    spd = None if speed is None or speed < 0 else min(65535, round_away(speed * 100))
    if speed is not None and speed >= 0.5 and course is not None and course >= 0:
        hdg = round_away(course) % 360
    elif heading is not None and heading >= 0:
        hdg = round_away(heading) % 360
    else:
        hdg = None
    return {"time": math.floor(time), "lat": e6(lat), "lon": e6(lon), "accuracy": acc, "heading": hdg,
            "speed": spd}


# ---- §5.2 invite links ---------------------------------------------------------------------------------------------

INV_FULL_HTTPS = re.compile(r"https://([A-Za-z0-9.-]+)/j/([A-Za-z0-9_-]{43})/?#([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")
INV_FULL_SCHEME = re.compile(r"satmap://join/([A-Za-z0-9_-]{43})/?#([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")
INV_PART_HTTPS = re.compile(r"https://([A-Za-z0-9.-]+)/j/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")
INV_PART_SCHEME = re.compile(r"satmap://join/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")


def b64u_decode(t: str) -> bytes:
    return base64.urlsafe_b64decode(t + "=" * (-len(t) % 4))


def parse_invite(text: str, domain: str):
    found = []
    for m in INV_FULL_HTTPS.finditer(text):
        if m.group(1).lower() == domain.lower():
            found.append((m.start(), m.group(2), m.group(3)))
    for m in INV_FULL_SCHEME.finditer(text):
        found.append((m.start(), m.group(1), m.group(2)))
    if found:
        _, token, key = min(found)
        return {"result": "invite", "token": token, "groupIdHex": b64u_decode(token)[:16].hex(),
                "groupKeyHex": b64u_decode(key).hex()}
    if any(m.group(1).lower() == domain.lower() for m in INV_PART_HTTPS.finditer(text)) or \
            INV_PART_SCHEME.search(text):
        return {"result": "missingKey"}
    return {"result": "notInvite"}


def main():
    K = bytes(range(0x00, 0x20))
    G = bytes(range(0xA0, 0xB0))
    M = bytes(range(0xB0, 0xC0))
    P = bytes(range(0xD0, 0xE0))
    S = bytes(range(0xE0, 0xF0))
    PASS = bytes(range(0x40, 0x60))
    gid, mid, pid = b64u(G), b64u(M), b64u(P)

    start = int(datetime.datetime(2026, 11, 2, 10, 0, tzinfo=datetime.timezone.utc).timestamp())
    assert start % 600 == 0
    purposes = {"group": "group", "member": "member", "pin": "pin", "join": "join",
                "trail": "trail|" + mid, "live": "live|" + mid}
    keys = {name: subkey(K, G, purpose) for name, purpose in purposes.items()}

    def env(kind, nonce_byte, plain, aad):
        nonce = bytes([nonce_byte] * 12)
        rec = seal(keys[kind], nonce, plain, aad.encode())
        return {"key": kind, "aad": aad, "nonceHex": nonce.hex(), "plainHex": plain.hex(),
                "envelopeHex": rec.hex(), "envelope": b64u(rec)}

    group_obj = {"name": "Kruger trip", "v": 1}
    member_obj = {"colour": 3, "name": "Thandi", "v": 1}
    member2_obj = {"colour": 0, "name": "Zoë 🦏", "v": 1}
    pin_obj = {"animal": "Rhino", "lat": -24996352, "lon": 31592345, "note": "Two adults near the water hole",
               "type": "animal", "v": 1}
    pin2_obj = {"lat": -24990001, "lon": 31600000, "type": "water", "v": 1}
    group_plain, member_plain, member2_plain = js(group_obj), js(member_obj), js(member2_obj)
    pin_plain, pin2_plain = js(pin_obj), js(pin2_obj)
    group_padded = padded(group_obj, 32)
    member_padded = padded(member_obj, 32)
    pin_padded = padded({"animal": "Rhino", "lat": -24996352, "lon": 31592345, "type": "animal", "v": 1}, 64)
    pin_created = start + 300
    pin_expires = pin_created + 43200
    pts = [
        (start + 5, -24996352, 31592345, True),
        (start + 9, -24996310, 31592401, False),
        (start + 13, -24996255, 31592470, False),
        (start + 20, -24996190, 31592512, False),
        (start + 340, -24994000, 31590000, True),
    ]
    cplain = chunk_plain(pts, start)
    many = [(start + 10 * i, -24996352 + 37 * i, 31592345 + 41 * i, i == 0) for i in range(40)]
    cdefl, cbody = chunk_deflated(many, start)
    live1 = live_plain(start + 20, -24996190, 31592512, acc=5, heading=87, speed=312)
    live2 = live_plain(start + 25, -24996150, 31592560)

    aad = {
        "group": f"satmap.group.v1|group|{gid}",
        "member": f"satmap.group.v1|member|{gid}|{mid}",
        "trail": f"satmap.group.v1|trail|{gid}|{mid}|{start}",
        "live1": f"satmap.group.v1|live|{gid}|{mid}|{start + 20}",
        "live2": f"satmap.group.v1|live|{gid}|{mid}|{start + 25}",
        "pin": f"satmap.group.v1|pin|{gid}|{pid}|{mid}|{pin_created}|{pin_expires}",
        "pin2": f"satmap.group.v1|pin|{gid}|{pid}|{mid}|{pin_created}|-",
    }
    trail_env = env("trail", 0x31, cplain, aad["trail"])
    tampered = bytearray(bytes.fromhex(trail_env["envelopeHex"]))
    tampered[-1] ^= 0x01
    token = G + S
    joinproof = keys["join"]

    # Encoding-1 chunks a reader must reject (§4.2): the stream must end exactly at the declared length.
    defl_longer = bytes([1, 1]) + uvarint(len(cbody)) + raw_deflate(cbody + b"\x00")
    defl_trailing = bytes([1, 1]) + uvarint(len(cbody)) + raw_deflate(cbody) + b"\x00"
    defl_short = bytes([1, 1]) + uvarint(len(cbody) + 1) + raw_deflate(cbody)

    tok, key = b64u(token), b64u(K)
    link = f"https://example.invalid/j/{tok}#{key}"
    invite_texts = [
        link,
        f'Join "Kruger trip" on Spoor: {link}',
        f"https://EXAMPLE.Invalid/j/{tok}#{key}",
        f"https://example.invalid/j/{tok}/#{key}",
        f"satmap://join/{tok}#{key}",
        f"https://other.example/j/{tok}#{key}",
        f"https://other.example/j/{tok}#{key} then {link}",
        f"https://example.invalid/j/{tok} then {link}",
        f"http://example.invalid/j/{tok}#{key}",
        f"https://example.invalid/j/{tok}",
        f"https://example.invalid/j/{tok}/",
        f"https://example.invalid/j/{tok}#{key[:42]}",
        f"https://example.invalid/j/{tok}#{key}A",
        f"https://example.invalid/j/{tok}?x=1#{key}",
        f"https://example.invalid/j/{tok}A#{key}",
        f"https://example.invalid/j/{tok[:42]}#{key}",
        f"satmap://join/{tok}",
        f"https://other.example/j/{tok}",
        "https://example.invalid/",
        "Kudu at the bridge",
        "",
    ]

    text_cases = [
        ("groupName", "  Kruger trip \t"), ("groupName", " Kruger　trip　"),
        ("groupName", "Zoë"), ("groupName", "a" * 40), ("groupName", "a" * 41),
        ("groupName", "\U0001F98F" * 40), ("groupName", "\U0001F98F" * 41), ("groupName", "   "),
        ("groupName", "\u001fKruger"), ("groupName", "Kru\u0000ger"), ("groupName", "Kruger trip"),
        ("groupName", "Kruger\ntrip"), ("groupName", " Kruger "), ("groupName", "​Kruger"),
        ("displayName", "Zoë 🦏"), ("displayName", "b" * 30), ("displayName", "b" * 31),
        ("displayName", "é" * 30), ("animal", " Rhino "), ("animal", ""),
        ("note", ""), ("note", "   "), ("note", "a\nb\nc\nd"), ("note", "a\nb\nc\nd\ne"),
        ("note", "a\r\nb"), ("note", "\n\nTwo adults\n"), ("note", "x" * 140), ("note", "x" * 141),
        ("note", "\u0085note"), ("reportComment", "line one\nline two"),
    ]

    word_list = "# test list\nbadword\nworse*\n*nasty*\n\n"
    wf = WordFilter(word_list)
    word_texts = ["Badword", "b4dw0rd!", "worsening", "supernastything", "n.a.s.t.y", "bad word", "Scunthorpe",
                  "Kudu at the bridge", "Ｂａｄｗｏｒｄ", "İbadword",
                  "Sipho's badword.", "Two badwords", "WORSE badword", "a nasty-ish road", "n4sty", "Zoë 🦏"]

    initial_names = ["Thandi", "Pieter van der Merwe", "zoë", "Zoë 🦏", "\U0001F98F Rhino", "\U0001F1FF\U0001F1E6 Sam",
                     "\U0001F44D\U0001F3FD", "❤️ Love", "\U0001F468‍\U0001F469‍\U0001F467 family",
                     "ßen", "élan vital", "123 Go", "(Sam) - Jones", "- -"]

    live_fixes = [
        dict(lat=-24.99619, lon=31.592512, accuracy=4.6, course=87.4, speed=3.12, heading=10.0, time=1793613620.9),
        dict(lat=-24.99619, lon=31.592512, accuracy=4.5, course=359.6, speed=0.5, heading=None, time=1793613620.0),
        dict(lat=-24.99619, lon=31.592512, accuracy=None, course=120.0, speed=0.49, heading=200.5, time=1793613621.2),
        dict(lat=-24.99619, lon=31.592512, accuracy=-1.0, course=-1.0, speed=-1.0, heading=-1.0, time=1793613622.5),
        dict(lat=-24.99619, lon=31.592512, accuracy=70000.0, course=None, speed=700.0, heading=359.5,
             time=1793613623.0),
        dict(lat=-24.99619, lon=31.592512, accuracy=0.4, course=0.4, speed=0.005, heading=None, time=1793613624.0),
    ]

    out = {
        "about": "Shared group test vectors. Made by docs/groups/vectors.py; see docs/groups/SPEC.md §3.6 and §4.8.",
        "inputs": {
            "groupKeyHex": K.hex(), "groupKey": b64u(K),
            "groupIdHex": G.hex(), "groupId": gid,
            "memberIdHex": M.hex(), "memberId": mid,
            "pinIdHex": P.hex(), "pinId": pid,
            "inviteSecretHex": S.hex(),
            "passHex": PASS.hex(), "pass": b64u(PASS),
            "windowStart": start,
        },
        "subkeyInfo": {name: "satmap.group.v1|key|" + purpose for name, purpose in purposes.items()},
        "subkeys": {name: keys[name].hex() for name in keys},
        "hashes": {
            "passSha256Hex": hashlib.sha256(PASS).hexdigest(),
            "joinProof": b64u(joinproof),
            "joinProofSha256Hex": hashlib.sha256(joinproof).hexdigest(),
            "inviteSecretSha256Hex": hashlib.sha256(S).hexdigest(),
        },
        "invite": {
            "token": tok,
            "domain": "example.invalid",
            "url": link,
            "schemeUrl": f"satmap://join/{tok}#{key}",
        },
        "records": {
            "groupProfile": env("group", 0x11, group_plain, aad["group"]),
            "groupProfilePadded": env("group", 0x13, group_padded, aad["group"]),
            "memberProfile": env("member", 0x21, member_plain, aad["member"]),
            "memberProfileUnicode": env("member", 0x22, member2_plain, aad["member"]),
            "memberProfilePadded": env("member", 0x23, member_padded, aad["member"]),
            "trailChunk": trail_env,
            "trailChunkDeflated": env("trail", 0x32, cdefl, aad["trail"]),
            "live": env("live", 0x41, live1, aad["live1"]),
            "liveUnknowns": env("live", 0x42, live2, aad["live2"]),
            "pinAnimal": env("pin", 0x51, pin_plain, aad["pin"]),
            "pinWater": env("pin", 0x52, pin2_plain, aad["pin2"]),
            "pinAnimalPadded": env("pin", 0x53, pin_padded, aad["pin"]),
        },
        "jsonWriters": {
            "about": "What a writer must produce (padded, §4.5–4.7). The unpadded records are reader tests only.",
            "groupProfile": {"name": "Kruger trip", "json": group_padded.decode()},
            "memberProfile": {"name": "Thandi", "colour": 3, "json": member_padded.decode()},
            "pinAnimal": {"type": "animal", "animal": "Rhino", "lat": -24996352, "lon": 31592345,
                          "json": pin_padded.decode()},
        },
        "trailChunk": {
            "windowStart": start,
            "points": [{"time": t, "lat": la, "lon": lo, "startsSegment": s} for (t, la, lo, s) in pts],
            "plainHex": cplain.hex(),
        },
        "trailChunkDeflated": {
            "windowStart": start,
            "points": [{"time": t, "lat": la, "lon": lo, "startsSegment": s} for (t, la, lo, s) in many],
            "bodyHex": cbody.hex(),
            "plainHex": cdefl.hex(),
            "note": "Readers must decode plainHex to the points. Writers need not produce the same DEFLATE bytes.",
        },
        "live": {"time": start + 20, "lat": -24996190, "lon": 31592512, "accuracy": 5, "heading": 87, "speed": 312,
                 "plainHex": live1.hex()},
        "liveUnknowns": {"time": start + 25, "lat": -24996150, "lon": 31592560, "plainHex": live2.hex()},
        "pin": {"pinCreatedAt": pin_created, "pinExpiresAt": pin_expires, "json": pin_plain.decode()},
        "mustFail": {
            "tamperedTrailEnvelopeHex": bytes(tampered).hex(),
            "trailWrongWindowAad": f"satmap.group.v1|trail|{gid}|{mid}|{start + 600}",
            "unknownVersionEnvelopeHex": "02" + trail_env["envelopeHex"][2:],
            "shortEnvelopeHex": "01" + "00" * 27,
            "badBase64url": ["oKGio6SlpqeoqaqrrK2urw==", "oKGio6Slpqeo+aqrrK2urw", "oKGio6Slpqeo/aqrrK2urw",
                             "oKGio6SlpqeoqaqrrK2ur", "oKGio6Slpqeo aqrrK2urw"],
            "nonCanonicalIds": ["oKGio6SlpqeoqaqrrK2urx", "sLGys7S1tre4ubq7vL2-vx"],
            "chunkPlainHex": {
                "deflateLongerThanDeclared": defl_longer.hex(),
                "deflateTrailingInput": defl_trailing.hex(),
                "deflateShorterThanDeclared": defl_short.hex(),
            },
        },
        "coordinates": [{"deg": d, "e6": e6(d)} for d in
                        [-24.996352, 31.592345, -33.918292, 18.921204, 0.0000005, -0.0000005, 0.0000015,
                         -0.0000025, 89.9999995, -179.9999995, 180.0, -90.0, 0.0]],
        "varints": [{"value": v, "hex": uvarint(v).hex()} for v in [0, 1, 127, 128, 300, 16383, 16384, 4294967295]],
        "zigzag": [{"value": v, "zigzag": zigzag(v)} for v in [0, -1, 1, -2, 2, -64, 64, 2147483647, -2147483648]],
        "inviteParse": {
            "domain": "example.invalid",
            "cases": [dict({"text": t}, **parse_invite(t, "example.invalid")) for t in invite_texts],
        },
        "groupText": [{"field": f, "input": t, "output": clean(t, f)} for (f, t) in text_cases],
        "wordFilter": {
            "list": word_list,
            "cases": [{"text": t, "allows": wf.allows(t), "masked": wf.masked(t)} for t in word_texts],
        },
        "ago": [{"seconds": s, "text": ago(s)} for s in
                [-5, 0, 59, 60, 119, 3599, 3600, 7199, 172799, 172800, 259199, 1000000]],
        "initials": [{"name": n, "initials": initials(n)} for n in initial_names],
        "liveRounding": [{"fix": f, "live": live_from_fix(**f)} for f in live_fixes],
    }
    path = os.path.join("Tests", "SatMapCoreTests", "Fixtures", "groups", "vectors.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(path)


if __name__ == "__main__":
    main()
