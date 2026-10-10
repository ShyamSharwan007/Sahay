"""Node id remapping and two-direction edges."""
from pipeline.graph import RawEdge, build_edges, normalize_highway, remap_node_ids


def raw(u, v, length=10.0, road_class="footway"):
    return RawEdge(u, v, length, road_class, 80.0, 12.0)


def test_remap_is_dense_zero_based_and_deterministic():
    ids = [900000000123, 55, 7000000, 55, 12]  # OSM ids are huge and sparse; duplicates allowed
    mapping = remap_node_ids(ids)
    assert sorted(mapping.values()) == list(range(4))
    assert mapping == {12: 0, 55: 1, 7000000: 2, 900000000123: 3}
    assert remap_node_ids(reversed(ids)) == mapping


def test_remap_of_nothing_is_empty():
    assert remap_node_ids([]) == {}


def test_every_edge_exists_in_both_directions_with_equal_attributes():
    id_map = remap_node_ids([10, 20, 30])
    edges = build_edges([raw(10, 20, 5.0, "path"), raw(20, 30, 7.5, "residential")], id_map)

    pairs = {(e.from_id, e.to_id): e for e in edges}
    assert set(pairs) == {(0, 1), (1, 0), (1, 2), (2, 1)}
    for (a, b), edge in pairs.items():
        reverse = pairs[(b, a)]
        assert (reverse.length_m, reverse.road_class) == (edge.length_m, edge.road_class)


def test_edges_already_in_both_directions_are_not_duplicated():
    id_map = remap_node_ids([1, 2])
    edges = build_edges([raw(1, 2, 3.0), raw(2, 1, 3.0)], id_map)
    assert [(e.from_id, e.to_id) for e in edges] == [(0, 1), (1, 0)]


def test_edges_use_remapped_ids_only():
    id_map = remap_node_ids([1000, 2000, 3000])
    edges = build_edges([raw(1000, 3000)], id_map)
    assert {e.from_id for e in edges} | {e.to_id for e in edges} == {0, 2}


def test_parallel_edges_keep_the_shortest():
    edges = build_edges([raw(1, 2, 50.0), raw(1, 2, 20.0), raw(1, 2, 30.0)], remap_node_ids([1, 2]))
    assert sorted(e.length_m for e in edges) == [20.0, 20.0]


def test_loops_unknown_nodes_and_bad_lengths_are_dropped():
    id_map = remap_node_ids([1, 2])
    edges = build_edges(
        [raw(1, 1), raw(1, 99), raw(1, 2, float("nan")), raw(1, 2, -4.0), raw(1, 2, float("inf")), raw(1, 2, 8.0)],
        id_map,
    )
    assert [(e.from_id, e.to_id, e.length_m) for e in edges] == [(0, 1, 8.0), (1, 0, 8.0)]


def test_output_is_sorted_for_reproducible_builds():
    edges = build_edges([raw(3, 1), raw(2, 1)], remap_node_ids([1, 2, 3]))
    keys = [(e.from_id, e.to_id) for e in edges]
    assert keys == sorted(keys)


def test_normalize_highway():
    assert normalize_highway("residential") == "residential"
    assert normalize_highway(["footway", "path"]) == "footway"
    assert normalize_highway([]) is None
    assert normalize_highway(None) is None
    assert normalize_highway("  ") is None


def test_pois_far_from_graph_flags_only_pois_beyond_the_limit():
    from pipeline.graph import pois_far_from_graph

    nodes = [(12.8400, 80.1500), (12.8410, 80.1500)]
    near = ("near", "Near", 12.84003, 80.1500)    # ~3 m from a node
    far = ("far", "Far", 12.8500, 80.1600)        # >1 km away
    result = pois_far_from_graph([near, far], nodes, 50.0)
    assert [r[0] for r in result] == ["far"]
    assert result[0][2] > 1000


def test_pois_far_from_graph_with_empty_graph_flags_everything():
    from pipeline.graph import pois_far_from_graph

    assert pois_far_from_graph([("a", "A", 1.0, 1.0)], [], 50.0) == [("a", "A", None)]
