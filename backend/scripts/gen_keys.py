#!/usr/bin/env python3
"""Generate a fresh Ed25519 keypair and print as standard base64.

Usage:
    uv run python scripts/gen_keys.py

Prints to stdout only — never writes files.
"""

import base64

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def main():
    private_key = Ed25519PrivateKey.generate()
    # Raw 32-byte private seed
    priv_bytes = private_key.private_bytes_raw()
    pub_bytes = private_key.public_key().public_bytes_raw()

    priv_b64 = base64.b64encode(priv_bytes).decode()
    pub_b64 = base64.b64encode(pub_bytes).decode()

    print("=== Sahay Ed25519 Keypair (raw 32 bytes, standard base64) ===")
    print()
    print(f"SIGNING_PRIVATE_KEY_B64={priv_b64}")
    print(f"SIGNING_PUBLIC_KEY_B64={pub_b64}")
    print()
    print("Copy both lines into your .env file.")


if __name__ == "__main__":
    main()
