"""risk_cost = clamp(3*high + 1.5*medium + elev + 1.0*nearRiverOrCoast, 0, 5); elev 1.5 (<3 m) / 0.8 (3-6 m) / 0."""

import pytest
from shapely.geometry import box

from pipeline.graph import Edge
from pipeline.risk import (
    compute_edge_risks,
    edge_elevation,
    elevation_term,
    format_histogram,
    risk_cost,
    risk_histogram,
)


@pytest.mark.parametrize(
    "elevation, expected",
    [
        (None, 1.5),
        (float("nan"), 1.5),
        (-2.0, 1.5),
        (0.0, 1.5),
        (2.99, 1.5),
        (3.0, 0.8),
        (5.99, 0.8),
        (6.0, 0.0),
        (40.0, 0.0),
    ],
)
def test_elevation_term(elevation, expected):
    assert elevation_term(elevation) == expected


@pytest.mark.parametrize(
    "high, medium, elev, near, expected",
    [
        (False, False, 20.0, False, 0.0),
        (False, False, 20.0, True, 1.0),
        (False, True, 20.0, False, 1.5),
        (True, False, 20.0, False, 3.0),
        (False, False, 4.0, False, 0.8),
        (False, False, 1.0, False, 1.5),
        (False, False, None, False, 1.5),  # NULL elevation counts as 0 m
        (False, True, 1.0, True, 4.0),  # 1.5 + 1.5 + 1.0
        (True, True, 20.0, False, 4.5),
        (True, True, 4.0, False, 5.0),  # 3 + 1.5 + 0.8 = 5.3 -> clamped
        (True, True, 0.0, True, 5.0),  # 7.0 -> clamped
    ],
)
def test_risk_cost_formula(high, medium, elev, near, expected):
    assert (
        risk_cost(in_high=high, in_medium=medium, elevation_m=elev, near_river_or_coast=near)
        == expected
    )


def test_risk_cost_is_always_within_0_to_5():
    for high in (False, True):
        for medium in (False, True):
            for near in (False, True):
                for elev in (None, -5, 0, 4, 100):
                    value = risk_cost(
                        in_high=high, in_medium=medium, elevation_m=elev, near_river_or_coast=near
                    )
                    assert 0 <= value <= 5


def test_edge_elevation_uses_mean_or_the_known_end():
    assert edge_elevation(2.0, 4.0) == 3.0
    assert edge_elevation(None, 4.0) == 4.0
    assert edge_elevation(None, None) is None


def test_compute_edge_risks_uses_the_edge_midpoint():
    edges = [
        Edge(0, 1, 100, "path", 0.5, 0.5),  # midpoint in HIGH and MEDIUM zones
        Edge(1, 2, 100, "path", 1.5, 0.5),  # in MEDIUM only
        Edge(2, 3, 100, "path", 9.5, 0.5),  # nowhere, high ground
    ]
    high, medium, near = box(0, 0, 1, 1), box(0, 0, 2, 2), box(1, 0, 2, 1)
    elevation = [20.0, 20.0, 20.0, 20.0]

    assert compute_edge_risks(edges, elevation, high, medium, near) == [4.5, 2.5, 0.0]


def test_compute_edge_risks_without_any_zones():
    edges = [Edge(0, 1, 100, "path", 0.5, 0.5)]
    assert compute_edge_risks(edges, [10.0, 10.0], None, None, None) == [0.0]
    assert compute_edge_risks(edges, [None, None], None, None, None) == [1.5]


def test_histogram_buckets_and_formatting():
    histogram = dict(risk_histogram([0, 0, 0.8, 1.0, 1.5, 2.5, 3.0, 4.5, 5.0]))
    assert histogram == {"0": 2, "0-1": 2, "1-2": 1, "2-3": 2, "3-4": 0, "4-5": 2}
    text = format_histogram(list(histogram.items()))
    assert "4-5" in text and "#" in text
    assert format_histogram(risk_histogram([]))  # empty input must not divide by zero
