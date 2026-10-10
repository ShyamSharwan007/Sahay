import logging

from fastapi import APIRouter, Request

from app.limiter import limiter
from app.models import TranslateRequest, TranslateResponse
from app.services.llm import call_gemini_translation, fallback_keyword_match

logger = logging.getLogger(__name__)
router = APIRouter()

@router.post("/translate", response_model=TranslateResponse)
@limiter.limit("20/minute")
async def post_translate(req: TranslateRequest, request: Request):
    """POST /translate (public, 20/min per IP)"""
    try:
        # LLM has an 8s timeout configured in call_gemini_translation
        result = await call_gemini_translation(req.text, req.target_lang)

        # Ensure we return valid format (ignore extra fields if any)
        return TranslateResponse(
            detected_lang=result.get("detectedLang", "en"),
            simplified_en=result.get("simplifiedEn", req.text),
            translated=result.get("translated", req.text),
            matched_template_code=result.get("matchedTemplateCode")
        )
    except Exception as e:
        logger.warning(f"Translation LLM failed, falling back to keyword match. Error: {e}")
        fallback = fallback_keyword_match(req.text, req.target_lang)
        return TranslateResponse(
            detected_lang=fallback["detectedLang"],
            simplified_en=fallback["simplifiedEn"],
            translated=fallback["translated"],
            matched_template_code=fallback["matchedTemplateCode"]
        )
