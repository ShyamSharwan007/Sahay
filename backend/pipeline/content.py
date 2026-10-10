"""Loads alert templates, keywords, phrases, embassies and radio stations from backend/app/data/content/*.json.

Those files are written on another laptop and may not exist yet; every table is allowed to be empty. Because the
exact shapes were not available when this was written, the loader accepts the obvious variants and reports (never
crashes on) rows it cannot read. Files are matched by name: "*template*", "*keyword*", "*phrase*", "*embass*",
"*radio*".

Accepted shapes (lang codes are the 9 user languages, plus 'ta' for phrases, 'hi'/'ta' for keywords):
  templates : [{code, lang, severity, title, body}]            one row per language, or
              [{code, severity, title:{lang:..}, body:{lang:..}}], or {CODE: {severity, title:{..}, body:{..}}}
  keywords  : [{code, lang, keyword}] | [{code, lang, keywords:[..]}] | [{code, keywords:{lang:[..]}}]
              | {CODE: {lang: [..]}}
  phrases   : [{id, lang, category, text, icon}] | [{id, category, icon, text:{lang:..}}] | {id: {category, icon, text:{..}}}
  embassies : [{country_code|countryCode|code, name, phone, address, lat, lon, url}]  (or keyed by country code)
  radio     : [{name, frequency, lang}]
A list may also be wrapped in a single-key object such as {"templates": [...]}.
"""
import json
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class ContentRows:
    alert_templates: list[tuple] = field(default_factory=list)  # code, lang, severity, title, body
    alert_keywords: list[tuple] = field(default_factory=list)   # code, lang, keyword
    phrases: list[tuple] = field(default_factory=list)          # id, lang, category, text, icon
    embassies: list[tuple] = field(default_factory=list)        # country_code, name, phone, address, lat, lon, url
    radio: list[tuple] = field(default_factory=list)            # name, frequency, lang
    warnings: list[str] = field(default_factory=list)


def load_content(content_dir: Path) -> ContentRows:
    rows = ContentRows()
    if not content_dir.is_dir():
        rows.warnings.append(f"{content_dir} not found: content tables will be empty")
        return rows
    for token, parser in (
        ("template", _parse_templates), ("keyword", _parse_keywords), ("phrase", _parse_phrases),
        ("embass", _parse_embassies), ("radio", _parse_radio),
    ):
        for path in sorted(p for p in content_dir.glob("*.json") if token in p.stem.lower()):
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError) as exc:
                rows.warnings.append(f"{path.name}: unreadable ({exc}), skipped")
                continue
            parser(_records(data, _KEY_FIELD[token]), rows, path.name)
    return rows


_KEY_FIELD = {"template": "code", "keyword": "code", "phrase": "id", "embass": "country_code", "radio": "name"}


# ---- generic helpers -------------------------------------------------------------------------------------------

def _records(data, key_field: str) -> list[dict]:
    """Normalise list / {"wrapper": [...]} / {key: {...}} into a list of dicts (the key goes into `key_field`)."""
    if isinstance(data, dict):
        lists = [v for v in data.values() if isinstance(v, list)]
        if len(lists) == 1 and not any(isinstance(v, dict) for v in data.values()):
            data = lists[0]  # {"templates": [...]} (other keys such as "version" are scalars)
        else:
            return [{key_field: key, **value} for key, value in data.items() if isinstance(value, dict)]
    return [item for item in data if isinstance(item, dict)] if isinstance(data, list) else []


def _first(record: dict, *names):
    for name in names:
        if record.get(name) is not None:
            return record[name]
    return None


def _text(value) -> str | None:
    text = str(value).strip() if value is not None else ""
    return text or None


def _int(value) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _float(value) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _per_language(record: dict, *fields: str) -> dict[str, dict[str, str]]:
    """{lang: {field: text}} from either a flat record (has 'lang') or per-field {lang: text} maps."""
    lang = _text(record.get("lang"))
    if lang:
        return {lang: {f: _text(record.get(f)) for f in fields}}
    result: dict[str, dict[str, str]] = {}
    for f in fields:
        mapping = record.get(f)
        if isinstance(mapping, dict):
            for lang_code, text in mapping.items():
                result.setdefault(str(lang_code), {})[f] = _text(text)
    return result


def _dedupe(rows: list[tuple], key_len: int) -> list[tuple]:
    """Later rows win on a duplicate primary key."""
    return list({row[:key_len]: row for row in rows}.values())


# ---- per-table parsers -----------------------------------------------------------------------------------------

def _parse_templates(records: list[dict], rows: ContentRows, source: str) -> None:
    parsed = []
    for rec in records:
        code, severity = _text(_first(rec, "code", "id")), _int(_first(rec, "severity", "sev"))
        if not code or severity is None:
            rows.warnings.append(f"{source}: template without code/severity skipped")
            continue
        for lang, parts in _per_language(rec, "title", "body").items():
            if parts.get("title") and parts.get("body"):
                parsed.append((code, lang, severity, parts["title"], parts["body"]))
            else:
                rows.warnings.append(f"{source}: {code}/{lang} missing title or body, skipped")
    rows.alert_templates = _dedupe(rows.alert_templates + parsed, 2)


def _parse_keywords(records: list[dict], rows: ContentRows, source: str) -> None:
    parsed = []
    for rec in records:
        code = _text(_first(rec, "code", "id"))
        if not code:
            rows.warnings.append(f"{source}: keyword entry without code skipped")
            continue
        by_lang: dict[str, list] = {}
        keywords = rec.get("keywords", rec.get("keyword"))
        if isinstance(keywords, dict):
            by_lang = {str(k): v for k, v in keywords.items()}
        elif _text(rec.get("lang")):
            by_lang = {_text(rec["lang"]): keywords}
        else:  # {CODE: {lang: [..]}} keyed form
            by_lang = {k: v for k, v in rec.items() if k not in ("code", "keywords", "keyword") and isinstance(v, (list, str))}
        if not by_lang:
            rows.warnings.append(f"{source}: keywords for {code} have no language, skipped")
        for lang, values in by_lang.items():
            for value in values if isinstance(values, list) else [values]:
                keyword = _text(value)
                if keyword:
                    parsed.append((code, lang, keyword.lower()))
    rows.alert_keywords = list(dict.fromkeys(rows.alert_keywords + parsed))


def _parse_phrases(records: list[dict], rows: ContentRows, source: str) -> None:
    parsed = []
    for rec in records:
        phrase_id, category = _text(_first(rec, "id", "phrase_id", "phraseId")), _text(rec.get("category"))
        if not phrase_id or not category:
            rows.warnings.append(f"{source}: phrase without id/category skipped")
            continue
        icon = _text(rec.get("icon"))
        for lang, parts in _per_language(rec, "text").items():
            if parts.get("text"):
                parsed.append((phrase_id, lang, category, parts["text"], icon))
    rows.phrases = _dedupe(rows.phrases + parsed, 2)


def _parse_embassies(records: list[dict], rows: ContentRows, source: str) -> None:
    parsed = []
    for rec in records:
        country = _text(_first(rec, "country_code", "countryCode", "code", "country"))
        name = _text(rec.get("name"))
        if not country or not name:
            rows.warnings.append(f"{source}: embassy without country code/name skipped")
            continue
        parsed.append((country.upper(), name, _text(rec.get("phone")), _text(rec.get("address")),
                       _float(rec.get("lat")), _float(rec.get("lon")), _text(rec.get("url"))))
    rows.embassies = _dedupe(rows.embassies + parsed, 1)


def _parse_radio(records: list[dict], rows: ContentRows, source: str) -> None:
    for rec in records:
        name, frequency = _text(rec.get("name")), _text(rec.get("frequency"))
        if not name or not frequency:
            rows.warnings.append(f"{source}: radio station without name/frequency skipped")
            continue
        rows.radio.append((name, frequency, _text(rec.get("lang"))))
