from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app, raise_server_exceptions=False)


@patch("app.routers.translate.call_gemini_translation", new_callable=AsyncMock)
def test_post_translate_success(mock_gemini):
    mock_gemini.return_value = {
        "detectedLang": "en",
        "simplifiedEn": "Flood warning",
        "translated": "Hochwasserwarnung",
        "matchedTemplateCode": "FLD_WARN",
    }

    resp = client.post(
        "/api/v1/translate", json={"text": "There is a flood coming!", "targetLang": "de"}
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["detectedLang"] == "en"
    assert data["simplifiedEn"] == "Flood warning"
    assert data["translated"] == "Hochwasserwarnung"
    assert data["matchedTemplateCode"] == "FLD_WARN"


@patch("app.routers.translate.call_gemini_translation", new_callable=AsyncMock)
def test_post_translate_fallback(mock_gemini):
    mock_gemini.side_effect = Exception("LLM timeout")

    resp = client.post("/api/v1/translate", json={"text": "flood", "targetLang": "de"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["detectedLang"] == "en"
    assert data["simplifiedEn"] == "flood"
    assert data["translated"] == "flood"
    # Could match FLD_WARN if keyword.json is read, but since it doesn't exist it returns None
    assert data["matchedTemplateCode"] is None
