"""Tests for app.signing — Ed25519 round-trip."""

from __future__ import annotations

from app.signing import load_private_key_from_b64, load_public_key_from_b64, sign, verify


def test_sign_and_verify_roundtrip(test_keypair):
    """Sign data, verify it returns True; tampered data returns False."""
    priv, pub, _, _ = test_keypair
    data = b"SH1*A*test*FLD_EVAC*3*12.62080,80.19450*2000*S*1760000000*"
    sig = sign(data, priv)

    # Signature is base64url without padding → exactly 86 chars
    assert len(sig) == 86, f"Signature length should be 86, got {len(sig)}"

    # Valid verification
    assert verify(data, sig, pub) is True

    # Tampered data should fail
    tampered = data + b"x"
    assert verify(tampered, sig, pub) is False


def test_sign_produces_no_padding(test_keypair):
    """Signature must not contain '=' padding chars."""
    priv, _, _, _ = test_keypair
    sig = sign(b"hello", priv)
    assert "=" not in sig


def test_load_keys_from_b64(test_keypair):
    """Round-trip: export keys to b64, reload, sign+verify."""
    _, _, priv_b64, pub_b64 = test_keypair
    priv = load_private_key_from_b64(priv_b64)
    pub = load_public_key_from_b64(pub_b64)

    data = b"round-trip test"
    sig = sign(data, priv)
    assert verify(data, sig, pub) is True
