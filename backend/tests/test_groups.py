from fastapi.testclient import TestClient

import app.routers.groups as groups_module
from app.main import app

client = TestClient(app, raise_server_exceptions=False)


def test_get_groups():
    # Since DBSCAN and sqlite downloading happens, we should mock calculate_groups for quick testing
    # or just let it run. Let's mock calculate_groups to just return a dummy group.

    # We clear the cache to ensure we hit the endpoint
    groups_module._groups_response_cache.clear()

    resp = client.get("/api/v1/groups?lat=12.6&lon=80.19&radiusM=3000")
    assert resp.status_code == 200
    data = resp.json()
    assert "groups" in data
    assert "beacons" in data
    assert data["beacons"] == []
    assert data["minSize"] > 0
