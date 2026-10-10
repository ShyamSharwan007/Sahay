"""Ed25519 signing / verification using the `cryptography` library.

Key format (CONTRACTS §4):
- Raw 32-byte keys, encoded in **standard base64** (with padding).
- Signatures are 64 bytes, encoded in **base64url WITHOUT padding** (86 chars).
- Signed bytes = UTF-8 of everything up to and including the last `*` before
  the signature field.
"""

from __future__ import annotations

import base64

from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)

from app.config import settings


def _load_private_key() -> Ed25519PrivateKey | None:
    """Load the Ed25519 private key from the env (raw 32 bytes, standard b64)."""
    raw = settings.SIGNING_PRIVATE_KEY_B64
    if not raw:
        return None
    key_bytes = base64.b64decode(raw)
    return Ed25519PrivateKey.from_private_bytes(key_bytes)


def _load_public_key() -> Ed25519PublicKey | None:
    """Load the Ed25519 public key from the env (raw 32 bytes, standard b64)."""
    raw = settings.SIGNING_PUBLIC_KEY_B64
    if not raw:
        return None
    key_bytes = base64.b64decode(raw)
    return Ed25519PublicKey.from_public_bytes(key_bytes)


def load_private_key_from_b64(b64: str) -> Ed25519PrivateKey:
    """Load an Ed25519 private key from standard-base64-encoded raw 32 bytes."""
    return Ed25519PrivateKey.from_private_bytes(base64.b64decode(b64))


def load_public_key_from_b64(b64: str) -> Ed25519PublicKey:
    """Load an Ed25519 public key from standard-base64-encoded raw 32 bytes."""
    return Ed25519PublicKey.from_public_bytes(base64.b64decode(b64))


def sign(data: bytes, private_key: Ed25519PrivateKey | None = None) -> str:
    """Sign *data* and return the base64url signature **without padding** (86 chars).

    If *private_key* is None the env-configured key is used.
    """
    if private_key is None:
        private_key = _load_private_key()
    if private_key is None:
        raise RuntimeError("No signing private key configured")
    sig_bytes = private_key.sign(data)
    # base64url, strip trailing '='
    return base64.urlsafe_b64encode(sig_bytes).rstrip(b"=").decode("ascii")


def verify(data: bytes, signature_b64url: str, public_key: Ed25519PublicKey | None = None) -> bool:
    """Verify an Ed25519 signature (base64url, no padding). Returns True/False."""
    if public_key is None:
        public_key = _load_public_key()
    if public_key is None:
        raise RuntimeError("No signing public key configured")
    # Re-pad the base64url signature
    padded = signature_b64url + "=" * (-len(signature_b64url) % 4)
    try:
        sig_bytes = base64.urlsafe_b64decode(padded)
        public_key.verify(sig_bytes, data)
        return True
    except Exception:
        return False
