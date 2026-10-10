import logging

from fastapi import APIRouter, HTTPException, Request

from app.limiter import limiter
from app.models import TranslateRequest, TranslateResponse
from app.services.llm import call_gemini_translation

logger = logging.getLogger(__name__)
router = APIRouter()


@router.post("/translate", response_model=TranslateResponse)
@limiter.limit("20/minute")
async def post_translate(req: TranslateRequest, request: Request):
    """POST /translate (public, 20/min per IP)"""
    target = req.target or req.target_lang
    try:
        result = await call_gemini_translation(req.text, target)

        # Ensure we return valid format (ignore extra fields if any)
        return TranslateResponse(
            detected_lang=result.get("detectedLang", "en"),
            simplified_en=result.get("simplifiedEn", req.text),
            translated=result.get("translated", req.text),
            matched_template_code=result.get("matchedTemplateCode"),
        )
    except Exception as e:
        logger.warning(f"Translation LLM failed: {e}")
        raise HTTPException(
            status_code=503,
            detail={
                "error": {
                    "code": "translate_unavailable",
                    "message": "Translation is unavailable right now.",
                }
            },
        )
