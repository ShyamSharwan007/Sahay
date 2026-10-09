"""Open-Meteo client: batching, cache, retry/backoff, NULL on permanent failure. No network is used."""
import requests

from pipeline.elevation import BATCH_SIZE, ElevationCache, ElevationClient, cache_key


class FakeResponse:
    def __init__(self, status=200, payload=None, headers=None):
        self.status_code = status
        self._payload = payload
        self.headers = headers or {}

    def json(self):
        if self._payload is None:
            raise ValueError("no json")
        return self._payload


class FakeSession:
    """Answers with the next scripted response (or raises it if it is an exception); echoes elevation = lat*10."""

    def __init__(self, script=None):
        self.script = list(script or [])
        self.calls = []

    def get(self, url, params, timeout):
        self.calls.append(params)
        if self.script:
            item = self.script.pop(0)
            if isinstance(item, Exception):
                raise item
            return item
        count = len(params["latitude"].split(","))
        return FakeResponse(200, {"elevation": [float(lat) * 10 for lat in params["latitude"].split(",")][:count]})


def make_client(tmp_path, session, **kwargs):
    sleeps = []
    cache = ElevationCache(tmp_path / "cache" / "elevation.sqlite")
    client = ElevationClient(cache, session=session, sleep=sleeps.append, log=lambda _: None, **kwargs)
    return client, cache, sleeps


def points(n):
    return [(12.0 + i * 0.001, 80.0 + i * 0.001) for i in range(n)]


def test_batches_of_100_and_results_for_every_point(tmp_path):
    session = FakeSession()
    client, _, _ = make_client(tmp_path, session)
    result = client.lookup(points(250))

    assert [len(c["latitude"].split(",")) for c in session.calls] == [100, 100, 50]
    assert len(result) == 250 and BATCH_SIZE == 100
    assert result[(12.0, 80.0)] == 120.0


def test_cached_points_are_not_requested_again(tmp_path):
    session = FakeSession()
    client, cache, _ = make_client(tmp_path, session)
    client.lookup(points(10))
    first_calls = len(session.calls)

    client.lookup(points(10))
    assert len(session.calls) == first_calls
    assert cache.get_many([cache_key(*points(1)[0])]) != {}


def test_polite_delay_between_batches(tmp_path):
    client, _, sleeps = make_client(tmp_path, FakeSession(), delay_s=0.4)
    client.lookup(points(150))
    assert sleeps.count(0.4) == 2


def test_retries_with_backoff_then_succeeds(tmp_path):
    session = FakeSession([FakeResponse(429, headers={"Retry-After": "7"}), requests.ConnectionError(), FakeResponse(503)])
    client, _, sleeps = make_client(tmp_path, session)
    result = client.lookup(points(3))

    assert all(v is not None for v in result.values())
    assert len(session.calls) == 4
    backoffs = [s for s in sleeps if s > 1]
    assert len(backoffs) == 3 and backoffs[0] >= 7, "Retry-After is honoured"
    assert backoffs[1] >= 4 and backoffs[2] >= 8, "backoff doubles"


def test_permanent_failure_gives_none_and_is_not_cached(tmp_path):
    session = FakeSession([FakeResponse(500)] * 5)
    client, cache, _ = make_client(tmp_path, session, max_retries=5)
    result = client.lookup(points(3))

    assert set(result.values()) == {None}
    assert cache.get_many([cache_key(*p) for p in points(3)]) == {}


def test_client_error_is_not_retried(tmp_path):
    session = FakeSession([FakeResponse(400, {"error": True})])
    client, _, _ = make_client(tmp_path, session)
    client.lookup(points(3))
    assert len(session.calls) == 1


def test_malformed_or_wrong_length_response_is_treated_as_failure(tmp_path):
    session = FakeSession([FakeResponse(200, {"elevation": [1.0]})])
    client, _, _ = make_client(tmp_path, session)
    assert set(client.lookup(points(3)).values()) == {None}


def test_api_nulls_become_none_and_are_cached(tmp_path):
    session = FakeSession([FakeResponse(200, {"elevation": [5.0, None, float("nan")]})])
    client, cache, _ = make_client(tmp_path, session)
    result = client.lookup(points(3))

    assert list(result.values()) == [5.0, None, None]
    assert len(cache.get_many([cache_key(*p) for p in points(3)])) == 3


def test_gives_up_after_consecutive_failed_batches(tmp_path):
    session = FakeSession([FakeResponse(429)] * 1000)
    client, _, _ = make_client(tmp_path, session, max_retries=1, max_failed_batches=3)
    result = client.lookup(points(1000))  # 10 batches

    assert len(session.calls) == 3
    assert set(result.values()) == {None}
