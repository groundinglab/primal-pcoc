"""Stand-in sign-in vectors for SPEC §15.1, built with the BIP-340 reference algorithm.

Checks the published §15.1 EXAMPLE first, then signs fresh challenges with a test key.
"""
import base64
import hashlib
import json

p = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
n = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)


def tagged_hash(tag, msg):
    t = hashlib.sha256(tag.encode()).digest()
    return hashlib.sha256(t + t + msg).digest()


def point_add(P1, P2):
    if P1 is None:
        return P2
    if P2 is None:
        return P1
    if P1[0] == P2[0] and P1[1] != P2[1]:
        return None
    if P1 == P2:
        lam = (3 * P1[0] * P1[0] * pow(2 * P1[1], p - 2, p)) % p
    else:
        lam = ((P2[1] - P1[1]) * pow(P2[0] - P1[0], p - 2, p)) % p
    x3 = (lam * lam - P1[0] - P2[0]) % p
    return (x3, (lam * (P1[0] - x3) - P1[1]) % p)


def point_mul(P, k):
    R = None
    for i in range(256):
        if (k >> i) & 1:
            R = point_add(R, P)
        P = point_add(P, P)
    return R


def bytes_from_int(x):
    return x.to_bytes(32, "big")


def int_from_bytes(b):
    return int.from_bytes(b, "big")


def lift_x(x):
    if x >= p:
        return None
    y_sq = (pow(x, 3, p) + 7) % p
    y = pow(y_sq, (p + 1) // 4, p)
    if pow(y, 2, p) != y_sq:
        return None
    return (x, y if y & 1 == 0 else p - y)


def pubkey_gen(seckey):
    d0 = int_from_bytes(seckey)
    P = point_mul(G, d0)
    return bytes_from_int(P[0])


def schnorr_sign(msg, seckey, aux_rand):
    d0 = int_from_bytes(seckey)
    P = point_mul(G, d0)
    d = d0 if P[1] % 2 == 0 else n - d0
    t = bytes(a ^ b for a, b in zip(bytes_from_int(d), tagged_hash("BIP0340/aux", aux_rand)))
    k0 = int_from_bytes(tagged_hash("BIP0340/nonce", t + bytes_from_int(P[0]) + msg)) % n
    R = point_mul(G, k0)
    k = n - k0 if R[1] % 2 != 0 else k0
    e = int_from_bytes(tagged_hash("BIP0340/challenge", bytes_from_int(R[0]) + bytes_from_int(P[0]) + msg)) % n
    sig = bytes_from_int(R[0]) + bytes_from_int((k + e * d) % n)
    assert schnorr_verify(msg, bytes_from_int(P[0]), sig)
    return sig


def schnorr_verify(msg, pubkey, sig):
    P = lift_x(int_from_bytes(pubkey))
    r = int_from_bytes(sig[0:32])
    s = int_from_bytes(sig[32:64])
    if P is None or r >= p or s >= n:
        return False
    e = int_from_bytes(tagged_hash("BIP0340/challenge", sig[0:32] + pubkey + msg)) % n
    R = point_add(point_mul(G, s), point_mul(P, n - e))
    return R is not None and R[1] % 2 == 0 and R[0] == r


DOMAIN = b"pcoc-gate-signin-v1"
ORIGIN = "https://pcoc.app"
DOOR = "https://admission.pcoc.app/v1/sign-in"


def challenge_bytes(origin, nonce, exp, object_sha256):
    o = origin.encode("utf-8")
    return DOMAIN + b"\x00" + bytes([len(o)]) + o + nonce + exp.to_bytes(8, "big") + object_sha256


def link(nonce, exp, object_sha256):
    return f"https://pcoc.app/sign-in/?v=1&nonce={nonce.hex()}&exp={exp}&object={object_sha256.hex()}"


# §15.1 Informative EXAMPLE, copied from the published text.
SPEC_CHALLENGE_HEX = (
    "70636f632d676174652d7369676e696e2d7631001068747470733a2f2f70636f632e617070"
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    "0000000070dbd880"
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
)
SPEC_MESSAGE_HEX = "69f340e811a370b22200bc7be89e8fd844548156bddcae55f449cb599470733a"

spec_nonce = bytes.fromhex("0123456789abcdef" * 4)
spec_exp = 1893456000
spec_object = bytes([0xAA]) * 32
cb = challenge_bytes(ORIGIN, spec_nonce, spec_exp, spec_object)
assert cb.hex() == SPEC_CHALLENGE_HEX, cb.hex()
assert len(cb) == 109
assert hashlib.sha256(cb).hexdigest() == SPEC_MESSAGE_HEX

# Test key only. Not a production key.
secret = bytes.fromhex("b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef")
public = pubkey_gen(secret)
aux = bytes(32)

vectors = []


def signed_vector(name, nonce, exp, object_bytes):
    obj_sha = hashlib.sha256(object_bytes).digest()
    cbytes = challenge_bytes(ORIGIN, nonce, exp, obj_sha)
    msg = hashlib.sha256(cbytes).digest()
    sig = schnorr_sign(msg, secret, aux)
    double = hashlib.sha256(msg).digest()
    assert not schnorr_verify(double, public, sig)
    body = {
        "origin": ORIGIN,
        "nonce": nonce.hex(),
        "exp": exp,
        "object": obj_sha.hex(),
        "object_b64": base64.b64encode(object_bytes).decode("ascii"),
        "signature": sig.hex(),
    }
    return {
        "name": name,
        "secret_key": secret.hex(),
        "public_key": public.hex(),
        "aux_rand": aux.hex(),
        "origin": ORIGIN,
        "nonce": nonce.hex(),
        "exp": exp,
        "object_bytes_hex": object_bytes.hex(),
        "object": obj_sha.hex(),
        "link": link(nonce, exp, obj_sha),
        "challenge_bytes": cbytes.hex(),
        "bip340_message_32": msg.hex(),
        "signature": sig.hex(),
        "body": body,
    }


vectors.append({
    "name": "spec-15.1-example",
    "origin": ORIGIN,
    "nonce": spec_nonce.hex(),
    "exp": spec_exp,
    "object": spec_object.hex(),
    "link": link(spec_nonce, spec_exp, spec_object),
    "challenge_bytes": SPEC_CHALLENGE_HEX,
    "bip340_message_32": SPEC_MESSAGE_HEX,
})

# A CBOR map {"public_key": <32 bytes>} so the object carries the signer's key.
cbor_object = bytes([0xA1, 0x6A]) + b"public_key" + bytes([0x58, 0x20]) + public
vectors.append(signed_vector("signed-spec-nonce", spec_nonce, spec_exp, cbor_object))
vectors.append(signed_vector(
    "signed-padded-base64",
    bytes(range(32)),
    1791000120,
    cbor_object + b"\x00",
))
vectors.append(signed_vector(
    "signed-max-exp",
    bytes([0xFF]) * 32,
    18446744073709551615,
    cbor_object + b"\x01\x02",
))

h = "ab" * 32
good = link(spec_nonce, spec_exp, spec_object)
links = {
    "accept": [
        good,
        "  " + good + "\n",
        "\t" + good + "\r\n",
        link(bytes(32), 0, bytes(32)),
        link(bytes([0xFF]) * 32, 18446744073709551615, bytes([0xFF]) * 32),
    ],
    "reject": [
        "https://pcoc.app/sign-in/?h=" + h,
        "https://pcoc.app/sign-in/?h=" + h.upper(),
        h,
        "0x" + h,
        good.replace(spec_nonce.hex(), spec_nonce.hex().upper()),
        good.replace("object=aa", "object=AA"),
        good + "&x=1",
        good + "#frag",
        good.replace("https://pcoc.app/", "https://www.pcoc.app/"),
        good.replace("https://pcoc.app/", "https://admission.pcoc.app/"),
        good.replace("https://pcoc.app/", "http://pcoc.app/"),
        good.replace("https://pcoc.app/", "https://PCOC.app/"),
        good.replace("https://pcoc.app/", "https://pcoc.app:443/"),
        good.replace("/sign-in/?", "/sign-in?"),
        good.replace("v=1&", "v=2&"),
        good.replace("v=1&", ""),
        good.replace(f"exp={spec_exp}", "exp=01893456000"),
        good.replace(f"exp={spec_exp}", "exp=+1893456000"),
        good.replace(f"exp={spec_exp}", "exp=18446744073709551616"),
        good.replace(f"exp={spec_exp}", "exp="),
        good.replace("nonce=" + spec_nonce.hex(), "nonce=" + spec_nonce.hex()[:62]),
        "https://pcoc.app/sign-in/?v=1&exp=1893456000&nonce=" + spec_nonce.hex() + "&object=" + spec_object.hex(),
        "\u00a0" + good,
        good + "\u2003",
        "",
        "   ",
    ],
}
assert links["reject"][4] != good

out = {
    "about": (
        "Stand-in for groundinglab/pcoc conformance/v2.0/sign-in.json, which this build could not fetch. "
        "Built from SPEC 15.1 and IMPLEMENTATION-PLAN PR-A with the BIP-340 reference algorithm. "
        "The spec-15.1-example entry is the published EXAMPLE. The test key is not a production key. "
        "Replace this file with the pcoc vector when it is reachable."
    ),
    "domain": DOMAIN.decode(),
    "origin": ORIGIN,
    "door": DOOR,
    "vectors": vectors,
    "links": links,
}
print(json.dumps(out, indent=2))
