# REVIEW.md — Human Spot-Check Checklist

This document lists everything a human reviewer must verify before deploying
`backend/app/data/content/` to production.

> [!IMPORTANT]
> Start with **German (de)** and **Tamil (ta)** — they are the most likely to
> contain errors from automated translation. Then spot-check Russian and Arabic
> (right-to-left and script differences).

---

## Priority 1: German (de) — check every file

German requires grammatical gender, case inflection and compound words that
automated systems frequently get wrong.

| File | What to check |
|---|---|
| `templates.json` | All 20 `title` and `body` fields. Titles must be ≤ 6 German words. Bodies ≤ 25 words. Verify FLD_EVAC, CYC_LANDFALL, SURGE are urgent-sounding. |
| `phrases.json` | `tourist_need_help`: "Ich bin Tourist" should flow naturally for a local reader. `call_police` / `call_ambulance` numbers (112, 108) must appear verbatim. |
| `precautions.json` | `always_*` and `monsoon_season_*` entries — these are the ones shown year-round; must read naturally, not robotically. |
| `embassies.json` | German Consulate address: **No. 9, Boat Club Road, R.A. Puram, Chennai 600028**. Verify the phone `+91-7397392250` is still active (alternative numbers are listed on the consulate site). |

---

## Priority 2: Tamil (ta) — phrases.json only

Tamil is written in a Dravidian script. Automated romanisation or incorrect
Unicode codepoints are the main risk.

| File | What to check |
|---|---|
| `phrases.json` | All 17 phrases. Show the Tamil text to a native speaker. Pay special attention to `tourist_need_help`, `take_me_to_shelter`, `call_police`, `call_ambulance`. |
| `keywords.json` | The `ta` field for every alert code. Keywords must match words that actually appear in Tamil-language official SMS alerts from DMO / NDMA. |

---

## Priority 3: Arabic (ar) — layout

Arabic text is right-to-left. Verify that:
- Sentences are complete and grammatically correct.
- Numbers (112, 108) appear in the correct position inside sentences.
- The `templates.json` `body` fields for FLD_EVAC and CYC_LANDFALL convey
  urgency appropriately.

---

## Unverified / Needs Human Confirmation

### Embassy data

| Country | Issue |
|---|---|
| **AE (UAE)** | No UAE consulate in Chennai. The entry points to the Embassy in New Delhi. A tourist in Chennai would need to travel ~2200 km or contact them remotely. Confirm this is still the correct nearest mission, or add the Mumbai consulate as a fallback. |
| **CN (China)** | No Chinese consulate in Chennai. Entry points to the Embassy in New Delhi. Same caveat as AE. The Mumbai consulate may be geographically closer for some travellers. |
| **ES (Spain)** | Listed as an **Honorary Consulate** with limited services. The competent Consulate General is in Mumbai. Consider adding a note field or second entry. |
| **GB (UK)** | No phone listed — the British DHC Chennai only takes enquiries via an online form. The 24/7 emergency number for British nationals worldwide is **+44-20-7008-5000** (FCDO). Consider adding this. |
| **FR (France)** | The Bureau de France in Chennai operates under the Consulate General in Pondicherry. Phone not confirmed from official site — verify. |
| **DE (Germany)** | Primary landlines may be temporarily unavailable per the consulate website. Alternative numbers `+91-7397392250` and `+91-9566633058` are listed. Emergency only: `+91-7358-799550`. Verify which is most reliably answered. |

### GPS coordinates

All latitude/longitude pairs for embassies were estimated from address
geocoding; they were **not** individually verified on a map.
A human must open each address in Google Maps and confirm the coordinates
are within the correct building before these values are served to the app.

### Radio stations

Frequencies are based on Wikipedia and secondary sources. Verify against the
official AIR schedule at **https://allindiaradio.gov.in** to confirm current
frequencies and confirm there are no additional Tamil-language AM/SW stations
relevant to emergency broadcasting in the Mahabalipuram / Kancheepuram area.

### Incident sourceUrls

All incidents use Wikipedia URLs. The Wikipedia articles were live as of the
date this file was generated. A human should open each URL and confirm the
article still exists and the facts cited match the content in the JSON.

| File | URL to verify |
|---|---|
| `incidents/mahabalipuram.json` | https://en.wikipedia.org/wiki/Cyclone_Michaung |
| `incidents/mahabalipuram.json` | https://en.wikipedia.org/wiki/Cyclone_Nivar |
| `incidents/mahabalipuram.json` | https://en.wikipedia.org/wiki/Cyclone_Vardah |
| `incidents/mahabalipuram.json` | https://en.wikipedia.org/wiki/2015_South_India_floods |
| `incidents/iiitdm-kancheepuram.json` | same three cyclone + flood articles |

### Keywords (hi, ta)

Hindi and Tamil keywords were written by the author based on domain knowledge.
They have **not** been verified against actual NDMA/DMO SMS archives.
A native speaker with access to historical government SMS alerts should review
and extend the `hi` and `ta` keyword lists for each alert code.

---

## Validation Script

Run this at any time to check all files:

```bash
cd backend
uv run --with httpx python pipeline/translate_content.py
```

This will:
1. Check all required languages are present in every entry.
2. Check title ≤ 6 words, body ≤ 25 words.
3. Call Gemini to fill any missing translations (idempotent — cached in `backend/cache/`).
4. Exit 0 if everything passes, 1 if there are issues.
