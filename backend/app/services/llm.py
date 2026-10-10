import json
import logging
from pathlib import Path

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

KEYWORDS_JSON = Path(__file__).resolve().parent.parent / "data" / "content" / "keywords.json"

async def call_gemini_translation(text: str, target_lang: str) -> dict:
    """
    Calls Gemini API to perform translation, simplification, and template matching.
    Returns: {"detectedLang": str, "simplifiedEn": str, "translated": str, "matchedTemplateCode": str | null}
    """
    if not settings.LLM_API_KEY:
        raise ValueError("Missing LLM_API_KEY")

    model = getattr(settings, "LLM_MODEL", "gemini-1.5-flash")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={settings.LLM_API_KEY}"

    prompt = f"""
    You are an emergency translation assistant.
    Input text: "{text}"
    Target language code: {target_lang}
    
    Tasks:
    1. Detect the language of the input text (return as a 2-letter code, e.g., 'ta' for Tamil, 'en' for English).
    2. Write a simplified plain-English version of the text. Keep it ≤ 25 words. Crucially, retain any numbers, places, and times.
    3. Translate the simplified English version into the target language.
    4. Pick the closest template code from this list: RAIN_HVY, RAIN_XHVY, FLD_WATCH, FLD_WARN, FLD_EVAC, CYC_WATCH, CYC_WARN, CYC_LANDFALL, WIND_HIGH, SEA_ROUGH, COAST_EVAC, SURGE, LIGHTNING, ROAD_CLOSED, POWER_OUT, STAY_INDOORS, SHELTER_OPEN, BOIL_WATER, ALL_CLEAR. If none apply, return null.

    Output strictly as JSON (no markdown wrapping, just the raw JSON object):
    {{
        "detectedLang": "...",
        "simplifiedEn": "...",
        "translated": "...",
        "matchedTemplateCode": "..."
    }}
    """

    payload = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "responseMimeType": "application/json",
            "temperature": 0.1
        }
    }

    async with httpx.AsyncClient() as client:
        resp = await client.post(url, json=payload, timeout=8.0)
        resp.raise_for_status()
        data = resp.json()

        try:
            content = data["candidates"][0]["content"]["parts"][0]["text"]
            return json.loads(content)
        except (KeyError, IndexError, json.JSONDecodeError) as e:
            logger.error(f"Failed to parse LLM response: {e}, Data: {data}")
            raise ValueError("Invalid LLM output format")

def fallback_keyword_match(text: str, target_lang: str) -> dict:
    """Fallback translation using keyword matching."""
    text_lower = text.lower()

    # Very simple matching based on keywords.json if it exists.
    matched_code = None
    if KEYWORDS_JSON.exists():
        try:
            keywords_data = json.loads(KEYWORDS_JSON.read_text())
            # Simple linear search over the keywords
            for item in keywords_data:
                code = item.get("code")
                kw = item.get("keyword", "")
                if kw and kw in text_lower:
                    matched_code = code
                    break
        except Exception as e:
            logger.error(f"Keyword match error: {e}")

    return {
        "detectedLang": "en",  # guess
        "simplifiedEn": text,
        "translated": text,
        "matchedTemplateCode": matched_code
    }
