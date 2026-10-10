from app.services.trust import (
    calculate_corroboration_score,
    calculate_history_score,
    calculate_official_match_score,
    calculate_proximity_score,
    calculate_recency_score,
    calculate_trust,
    get_trust_label,
)


def test_proximity():
    # no reporter location
    assert calculate_proximity_score(0, 0, 12.0, 80.0) == 0.0

    # exact location -> P = 1
    assert calculate_proximity_score(12.0, 80.0, 12.0, 80.0) == 1.0

    # 1000m away -> P = 0
    # ~0.009 deg is roughly 1km
    assert calculate_proximity_score(12.0, 80.0, 12.009, 80.0) < 0.1


def test_corroboration():
    assert calculate_corroboration_score(0) == 0.0
    assert calculate_corroboration_score(1) == 1.0 / 3.0
    assert calculate_corroboration_score(3) == 1.0
    assert calculate_corroboration_score(4) == 1.0


def test_official_match():
    # Empty alerts
    assert calculate_official_match_score("FL", 12.0, 80.0, []) == 0.0

    # Has matching alert code but out of radius
    alerts = [{"template_code": "FLD_WARN", "lat": 12.0, "lon": 80.0, "radius_m": 500}]
    assert calculate_official_match_score("FL", 12.1, 80.0, alerts) == 0.0

    # Has matching alert inside radius
    assert calculate_official_match_score("FL", 12.0, 80.0, alerts) == 1.0

    # Unrelated alert
    assert (
        calculate_official_match_score(
            "FL",
            12.0,
            80.0,
            [{"template_code": "SHELTER_OPEN", "lat": 12.0, "lon": 80.0, "radius_m": 500}],
        )
        == 0.0
    )


def test_history():
    assert calculate_history_score(0, 0) == 0.5
    assert calculate_history_score(10, 5) == 0.5
    assert calculate_history_score(10, 10) == 1.0


def test_recency():
    now = 1000000

    # created exactly now -> 1.0
    assert calculate_recency_score(now, now) == 1.0

    # 60 minutes old -> exp(-1) ~ 0.367
    assert abs(calculate_recency_score(now - 3600, now) - 0.3678) < 0.001

    # Future timestamp clamped (ageMinutes=0) -> 1.0
    assert calculate_recency_score(now + 3600, now) == 1.0


def test_overall_trust():
    trust = calculate_trust(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
    assert trust == 1.0
    assert get_trust_label(trust) == "VERIFIED"

    assert get_trust_label(0.70) == "VERIFIED"
    assert get_trust_label(0.40) == "LIKELY"
    assert get_trust_label(0.39) == "UNCONFIRMED"
