#!/usr/bin/env python3
"""
translate_content.py -- Sahay content pipeline (Person C)
==========================================================
Generates / updates backend/app/data/content/*.json from English sources
using the Gemini API. Results are disk-cached so re-runs are free unless
you delete backend/cache/.

Usage (from the backend/ directory):
    uv run --with httpx python pipeline/translate_content.py
    -- or without uv --
    pip install httpx
    python pipeline/translate_content.py

Env vars required:
    LLM_API_KEY   -- Gemini API key
    LLM_MODEL     -- Gemini model name (e.g. gemini-2.5-flash)

What this script does:
1. Validates all pre-written JSON files in backend/app/data/content/
   (language completeness, word/title length limits).
2. Optionally extends translations via Gemini if any language is missing.
3. Prints a summary of any issues for the human reviewer.

All content JSON files are pre-generated with human-authored data.
This script validates them and can fill in gaps via Gemini calls.
"""

import hashlib
import json
import os
import sys
from pathlib import Path

import httpx

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
HERE = Path(__file__).parent  # backend/pipeline/
BACKEND = HERE.parent  # backend/
CONTENT_DIR = BACKEND / "app" / "data" / "content"
CACHE_DIR = BACKEND / "cache"
CACHE_DIR.mkdir(parents=True, exist_ok=True)

REQUIRED_LANGS = ["en", "de", "fr", "es", "ru", "ja", "ko", "zh", "ar"]
PHRASE_LANGS = REQUIRED_LANGS + ["ta"]  # phrases also need Tamil

LLM_API_KEY = os.environ.get("LLM_API_KEY", "")
LLM_MODEL = os.environ.get("LLM_MODEL", "gemini-2.5-flash")
GEMINI_URL = (
    "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}"
)


# ---------------------------------------------------------------------------
# Disk cache helpers
# ---------------------------------------------------------------------------


def _cache_key(prompt: str) -> str:
    """SHA-256 of the prompt -> cache filename."""
    return hashlib.sha256(prompt.encode()).hexdigest()


def _cache_get(prompt: str) -> str | None:
    path = CACHE_DIR / (_cache_key(prompt) + ".txt")
    if path.exists():
        return path.read_text(encoding="utf-8")
    return None


def _cache_set(prompt: str, result: str) -> None:
    path = CACHE_DIR / (_cache_key(prompt) + ".txt")
    path.write_text(result, encoding="utf-8")


# ---------------------------------------------------------------------------
# Gemini call
# ---------------------------------------------------------------------------


def gemini(prompt: str) -> str:
    """Call Gemini; return the text response. Uses disk cache to avoid re-hits."""
    cached = _cache_get(prompt)
    if cached is not None:
        return cached

    if not LLM_API_KEY:
        raise RuntimeError("LLM_API_KEY is not set. Export it before running this script.")

    url = GEMINI_URL.format(model=LLM_MODEL, key=LLM_API_KEY)
    payload = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.2, "maxOutputTokens": 2048},
    }
    resp = httpx.post(url, json=payload, timeout=60)
    resp.raise_for_status()
    data = resp.json()
    text = data["candidates"][0]["content"]["parts"][0]["text"]
    _cache_set(prompt, text)
    return text


def translate_text(text: str, target_lang: str, context: str = "") -> str:
    """Translate *text* to *target_lang* using Gemini. Returns translated string."""
    context_note = f" Context: {context}." if context else ""
    prompt = (
        f"Translate the following text into language code '{target_lang}'.{context_note}\n"
        "Return ONLY the translated text, no explanation, no quotes.\n\n"
        f"Text: {text}"
    )
    return gemini(prompt).strip()


# ---------------------------------------------------------------------------
# Validation helpers
# ---------------------------------------------------------------------------


def word_count(text: str) -> int:
    return len(text.split())


def validate_lang_map(obj: dict, required: list, label: str, errors: list):
    """Check that all required languages are present in a dict."""
    for lang in required:
        if lang not in obj:
            errors.append(f"  MISSING lang '{lang}' in {label}")


def validate_templates(data: dict) -> list:
    errors = []
    for code, entry in data.items():
        for field in ("title", "body"):
            validate_lang_map(entry[field], REQUIRED_LANGS, f"templates[{code}].{field}", errors)
            # Check title <= 6 words, body <= 25 words
            limit = 6 if field == "title" else 25
            for lang, text in entry[field].items():
                wc = word_count(text)
                if wc > limit:
                    errors.append(
                        f"  LENGTH: templates[{code}].{field}[{lang}] = {wc} words "
                        f"(limit {limit}): {text!r}"
                    )
    return errors


def validate_keywords(data: dict) -> list:
    errors = []
    for code, langs in data.items():
        for lang in ("en", "hi", "ta"):
            if lang not in langs:
                errors.append(f"  MISSING lang '{lang}' in keywords[{code}]")
            else:
                kws = langs[lang]
                if not (5 <= len(kws) <= 10):
                    errors.append(f"  keywords[{code}][{lang}] has {len(kws)} keywords (need 5-10)")
    return errors


def validate_phrases(data: list) -> list:
    errors = []
    for item in data:
        pid = item.get("id", "<unknown>")
        validate_lang_map(item.get("text", {}), PHRASE_LANGS, f"phrases[{pid}].text", errors)
    return errors


def validate_precautions(data: list) -> list:
    errors = []
    valid_triggers = {
        "heavy_rain",
        "very_heavy_rain",
        "extreme_rain",
        "cyclone_wind",
        "monsoon_season",
        "always",
    }
    for item in data:
        pid = item.get("id", "<unknown>")
        if item.get("trigger") not in valid_triggers:
            errors.append(f"  INVALID trigger in precautions[{pid}]: {item.get('trigger')!r}")
        for field in ("title", "body"):
            validate_lang_map(
                item.get(field, {}), REQUIRED_LANGS, f"precautions[{pid}].{field}", errors
            )
            limit = 6 if field == "title" else 25
            for lang, text in item.get(field, {}).items():
                wc = word_count(text)
                if wc > limit:
                    errors.append(
                        f"  LENGTH: precautions[{pid}].{field}[{lang}] = {wc} words "
                        f"(limit {limit}): {text!r}"
                    )
    return errors


def validate_history_templates(data: dict) -> list:
    errors = []
    validate_lang_map(data, REQUIRED_LANGS, "history_templates", errors)
    for lang, template in data.items():
        for placeholder in ("{avgRain}", "{heavyDays}", "{years}"):
            if placeholder not in template:
                errors.append(f"  history_templates[{lang}] missing placeholder {placeholder}")
    return errors


def validate_incidents(data: list, region: str) -> list:
    errors = []
    for item in data:
        date = item.get("date", "<unknown>")
        label = f"incidents/{region}[{date}]"
        for field in ("title", "summary"):
            validate_lang_map(item.get(field, {}), REQUIRED_LANGS, f"{label}.{field}", errors)
        if not item.get("sourceUrl"):
            errors.append(f"  MISSING sourceUrl in {label}")
    return errors


def validate_embassies(data: list) -> list:
    errors = []
    required_codes = {"DE", "FR", "US", "GB", "JP", "KR", "RU", "AE", "CN", "ES"}
    found_codes = {e.get("countryCode") for e in data}
    for code in required_codes:
        if code not in found_codes:
            errors.append(f"  MISSING embassy for countryCode '{code}'")
    for e in data:
        if not e.get("source"):
            errors.append(f"  MISSING source for embassy {e.get('countryCode')}")
    return errors


def validate_radio(data: list) -> list:
    errors = []
    if not data:
        errors.append("  radio.json is empty -- need at least one station")
    for station in data:
        for field in ("name", "frequency", "lang", "source"):
            if not station.get(field):
                errors.append(f"  MISSING field '{field}' in radio station {station}")
    return errors


# ---------------------------------------------------------------------------
# Gap-filler: add missing translations via Gemini
# ---------------------------------------------------------------------------


def fill_lang_gaps_in_map(lang_map: dict, required: list, context: str) -> bool:
    """Translate missing languages using Gemini. Returns True if anything was added."""
    changed = False
    en_text = lang_map.get("en", "")
    if not en_text:
        return False
    for lang in required:
        if lang not in lang_map or not lang_map[lang]:
            print(f"    -> translating to '{lang}' (context: {context[:60]})")
            lang_map[lang] = translate_text(en_text, lang, context)
            changed = True
    return changed


def fill_templates(data: dict) -> bool:
    changed = False
    for code, entry in data.items():
        for field in ("title", "body"):
            if fill_lang_gaps_in_map(
                entry[field], REQUIRED_LANGS, f"disaster alert {code} {field}"
            ):
                changed = True
    return changed


def fill_phrases(data: list) -> bool:
    changed = False
    for item in data:
        if fill_lang_gaps_in_map(item["text"], PHRASE_LANGS, f"tourist phrase: {item['id']}"):
            changed = True
    return changed


def fill_precautions(data: list) -> bool:
    changed = False
    for item in data:
        for field in ("title", "body"):
            if fill_lang_gaps_in_map(
                item[field], REQUIRED_LANGS, f"precaution {item['id']} {field}"
            ):
                changed = True
    return changed


def fill_incidents(data: list, region: str) -> bool:
    changed = False
    for item in data:
        for field in ("title", "summary"):
            if fill_lang_gaps_in_map(
                item[field], REQUIRED_LANGS, f"incident {region} {item.get('date')} {field}"
            ):
                changed = True
    return changed


# ---------------------------------------------------------------------------
# Load / save helpers
# ---------------------------------------------------------------------------


def load_json(path: Path) -> object:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save_json(path: Path, data: object) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"  [SAVED] {path.relative_to(BACKEND)}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> int:
    print("=" * 60)
    print("Sahay Content Pipeline -- validation + gap-fill")
    print(f"Model : {LLM_MODEL}")
    print(f"Cache : {CACHE_DIR}")
    print("=" * 60)

    all_errors = []
    changed_files = []

    # -- templates.json -------------------------------------------------------
    print("\n[1/8] templates.json")
    tpl_path = CONTENT_DIR / "templates.json"
    templates = load_json(tpl_path)
    if fill_templates(templates):
        changed_files.append(tpl_path)
        save_json(tpl_path, templates)
    errs = validate_templates(templates)
    if errs:
        print("  [WARN] Validation issues:")
        for e in errs:
            print(e)
    else:
        print("  [OK]")
    all_errors.extend(errs)

    # -- keywords.json --------------------------------------------------------
    print("\n[2/8] keywords.json")
    kw_path = CONTENT_DIR / "keywords.json"
    keywords = load_json(kw_path)
    errs = validate_keywords(keywords)
    if errs:
        print("  [WARN] Validation issues:")
        for e in errs:
            print(e)
    else:
        print("  [OK]")
    all_errors.extend(errs)

    # -- phrases.json ---------------------------------------------------------
    print("\n[3/8] phrases.json")
    ph_path = CONTENT_DIR / "phrases.json"
    phrases = load_json(ph_path)
    if fill_phrases(phrases):
        changed_files.append(ph_path)
        save_json(ph_path, phrases)
    errs = validate_phrases(phrases)
    if errs:
        print("  [WARN] Validation issues:")
        for e in errs:
            print(e)
    else:
        print("  [OK]")
    all_errors.extend(errs)

    # -- precautions.json -----------------------------------------------------
    print("\n[4/8] precautions.json")
    pr_path = CONTENT_DIR / "precautions.json"
    precautions = load_json(pr_path)
    if fill_precautions(precautions):
        changed_files.append(pr_path)
        save_json(pr_path, precautions)
    errs = validate_precautions(precautions)
    if errs:
        print("  [WARN] Validation issues:")
        for e in errs:
            print(e)
    else:
        print("  [OK]")
    all_errors.extend(errs)

    # -- history_templates.json -----------------------------------------------
    print("\n[5/8] history_templates.json")
    ht_path = CONTENT_DIR / "history_templates.json"
    history_tpl = load_json(ht_path)
    errs = validate_history_templates(history_tpl)
    if errs:
        print("  [WARN] Validation issues:")
        for e in errs:
            print(e)
    else:
        print("  [OK]")
    all_errors.extend(errs)

    # -- incidents/*.json -----------------------------------------------------
    for region in ("mahabalipuram", "iiitdm-kancheepuram"):
        print(f"\n[6/{region}] incidents/{region}.json")
        inc_path = CONTENT_DIR / "incidents" / f"{region}.json"
        if not inc_path.exists():
            all_errors.append(f"  MISSING file: {inc_path}")
            print(f"  [ERROR] File not found: {inc_path}")
            continue
        incidents = load_json(inc_path)
        if fill_incidents(incidents, region):
            changed_files.append(inc_path)
            save_json(inc_path, incidents)
        errs = validate_incidents(incidents, region)
        if errs:
            print("  [WARN] Validation issues:")
            for e in errs:
                print(e)
        else:
            print("  [OK]")
        all_errors.extend(errs)

    # -- embassies.json -------------------------------------------------------
    print("\n[7/8] embassies.json")
    emb_path = CONTENT_DIR / "embassies.json"
    embassies = load_json(emb_path)
    errs = validate_embassies(embassies)
    if errs:
        print("  [WARN] Validation issues:")
        for e in errs:
            print(e)
    else:
        print("  [OK]")
    all_errors.extend(errs)

    # -- radio.json -----------------------------------------------------------
    print("\n[8/8] radio.json")
    rad_path = CONTENT_DIR / "radio.json"
    radio = load_json(rad_path)
    errs = validate_radio(radio)
    if errs:
        print("  [WARN] Validation issues:")
        for e in errs:
            print(e)
    else:
        print("  [OK]")
    all_errors.extend(errs)

    # -- Summary --------------------------------------------------------------
    print("\n" + "=" * 60)
    if all_errors:
        print(f"DONE -- {len(all_errors)} issue(s) found:")
        for e in all_errors:
            print(e)
        return 1
    else:
        print("DONE -- All files validated successfully. No issues found.")
        return 0


if __name__ == "__main__":
    sys.exit(main())
