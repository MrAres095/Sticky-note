#!/usr/bin/env python3
import base64
import hashlib
import sys

if len(sys.argv) != 4:
    print("Usage: recover_jks.py <base64> <password> <output>", file=sys.stderr)
    sys.exit(2)

b64, password, output = sys.argv[1:]

def valid_jks(raw: bytes) -> bool:
    if len(raw) < 28 or raw[:4] != b"\xfe\xed\xfe\xed":
        return False
    expected = raw[-20:]
    material = raw[:-20] + password.encode("utf-16be") + b"Mighty Aphrodite"
    return hashlib.sha1(material).digest() == expected

def decode_candidate(value: str):
    try:
        padding = "=" * ((4 - len(value) % 4) % 4)
        raw = base64.b64decode(value + padding, validate=True)
    except Exception:
        return None
    return raw if valid_jks(raw) else None

found = decode_candidate(b64)

if found is None:
    # The stored secret was observed with a one-character Base64 alignment error.
    for i in range(len(b64)):
        candidate = decode_candidate(b64[:i] + b64[i + 1:])
        if candidate is not None:
            found = candidate
            break

if found is None and len(b64) % 4 == 3:
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    for i in range(len(b64) + 1):
        for ch in alphabet:
            candidate = decode_candidate(b64[:i] + ch + b64[i:])
            if candidate is not None:
                found = candidate
                break
        if found is not None:
            break

if found is None:
    print("Unable to recover a valid persistent signing keystore from the stored secret.", file=sys.stderr)
    sys.exit(1)

with open(output, "wb") as f:
    f.write(found)

print("Persistent signing keystore recovered and integrity-validated.")
