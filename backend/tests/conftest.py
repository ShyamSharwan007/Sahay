"""Shared test fixtures."""

from __future__ import annotations

import base64

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


@pytest.fixture()
def test_keypair():
    """Generate a fresh Ed25519 keypair for tests.

    Returns (private_key, public_key, priv_b64, pub_b64).
    """
    priv = Ed25519PrivateKey.generate()
    pub = priv.public_key()
    priv_b64 = base64.b64encode(priv.private_bytes_raw()).decode()
    pub_b64 = base64.b64encode(pub.public_bytes_raw()).decode()
    return priv, pub, priv_b64, pub_b64
