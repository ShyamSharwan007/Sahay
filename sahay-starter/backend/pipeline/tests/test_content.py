"""Content loading must be tolerant: missing folder/files are fine, bad rows are reported, never fatal."""
import json

from pipeline.content import load_content


def write(directory, name, data):
    (directory / name).write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")


def test_missing_folder_gives_empty_content_and_a_warning(tmp_path):
    rows = load_content(tmp_path / "nope")
    assert rows.alert_templates == rows.phrases == rows.radio == []
    assert rows.warnings


def test_empty_folder_is_fine(tmp_path):
    rows = load_content(tmp_path)
    assert not rows.alert_templates and not rows.warnings


def test_alert_templates_in_flat_and_nested_shapes(tmp_path):
    write(tmp_path, "alert_templates.json", [
        {"code": "FLD_EVAC", "lang": "en", "severity": 3, "title": "Flood: go now", "body": "Move to a shelter."},
        {"code": "TEST", "severity": 0, "title": {"en": "Test", "de": "Test DE"}, "body": {"en": "Ignore.", "de": "Ignorieren."}},
    ])
    rows = load_content(tmp_path)
    assert sorted(rows.alert_templates) == [
        ("FLD_EVAC", "en", 3, "Flood: go now", "Move to a shelter."),
        ("TEST", "de", 0, "Test DE", "Ignorieren."),
        ("TEST", "en", 0, "Test", "Ignore."),
    ]


def test_templates_keyed_by_code_and_wrapped_in_an_object(tmp_path):
    write(tmp_path, "templates.json", {"version": 1, "templates": [
        {"code": "A", "lang": "en", "severity": 1, "title": "t", "body": "b"}]})
    write(tmp_path, "alert_templates_extra.json", {"B": {"severity": 2, "title": {"en": "tb"}, "body": {"en": "bb"}}})
    rows = load_content(tmp_path)
    assert {r[0] for r in rows.alert_templates} == {"A", "B"}


def test_bad_template_rows_are_skipped_with_warnings(tmp_path):
    write(tmp_path, "alert_templates.json", [
        {"code": "X", "lang": "en", "severity": "high", "title": "t", "body": "b"},
        {"code": "Y", "lang": "en", "severity": 1, "title": "t"},
        {"code": "Z", "lang": "en", "severity": 1, "title": "t", "body": "b"},
    ])
    rows = load_content(tmp_path)
    assert [r[0] for r in rows.alert_templates] == ["Z"]
    assert len(rows.warnings) == 2


def test_keywords_are_lowercased_and_deduplicated(tmp_path):
    write(tmp_path, "alert_keywords.json", {
        "FLD_WARN": {"en": ["Flood", "FLOOD", "water level"], "ta": ["வெள்ளம்"]},
    })
    write(tmp_path, "keywords_flat.json", [{"code": "TEST", "lang": "hi", "keyword": "Pariksha"}])
    rows = load_content(tmp_path)
    assert sorted(rows.alert_keywords) == [
        ("FLD_WARN", "en", "flood"), ("FLD_WARN", "en", "water level"), ("FLD_WARN", "ta", "வெள்ளம்"),
        ("TEST", "hi", "pariksha"),
    ]


def test_phrases_flat_and_per_language(tmp_path):
    write(tmp_path, "phrases.json", [
        {"id": "yes", "lang": "en", "category": "basic", "text": "Yes", "icon": "check"},
        {"id": "no", "category": "basic", "text": {"en": "No", "ta": "இல்லை"}},
    ])
    rows = load_content(tmp_path)
    assert sorted(rows.phrases) == [
        ("no", "en", "basic", "No", None), ("no", "ta", "basic", "இல்லை", None), ("yes", "en", "basic", "Yes", "check")]


def test_embassies_and_radio(tmp_path):
    write(tmp_path, "embassies.json", [
        {"countryCode": "de", "name": "German Embassy", "phone": "+91 11", "lat": "28.6", "lon": 77.2},
        {"name": "no country"},
    ])
    write(tmp_path, "radio.json", [{"name": "AIR Chennai", "frequency": "101.4 MHz", "lang": "ta"}, {"name": "x"}])
    rows = load_content(tmp_path)
    assert rows.embassies == [("DE", "German Embassy", "+91 11", None, 28.6, 77.2, None)]
    assert rows.radio == [("AIR Chennai", "101.4 MHz", "ta")]
    assert len(rows.warnings) == 2


def test_unreadable_json_is_reported_not_fatal(tmp_path):
    (tmp_path / "phrases.json").write_text("{oops", encoding="utf-8")
    rows = load_content(tmp_path)
    assert rows.phrases == [] and "phrases.json" in rows.warnings[0]
