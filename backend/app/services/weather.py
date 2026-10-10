import datetime
import json
import logging
from pathlib import Path

import httpx

logger = logging.getLogger(__name__)

# Very simple in-memory caches
_FORECAST_CACHE: dict[str, dict] = {}
_HISTORY_CACHE: dict[str, dict] = {}

CONTENT_DIR = Path(__file__).resolve().parent.parent / "data" / "content"

def get_risk_level(rain_mm: float, wind_kmh: float) -> str:
    """IMD thresholds: heavy >= 64.5, very heavy >= 115.6, extremely heavy >= 204.5 or wind >= 62"""
    if rain_mm >= 204.5 or wind_kmh >= 62:
        return "SEVERE"
    if rain_mm >= 115.6:
        return "HIGH"
    if rain_mm >= 64.5:
        return "MODERATE"
    return "LOW"

async def fetch_forecast(lat: float, lon: float, start_date: str, end_date: str) -> list[dict]:
    cache_key = f"{lat},{lon},{start_date},{end_date}"
    now = datetime.datetime.now(datetime.timezone.utc).timestamp()

    if cache_key in _FORECAST_CACHE:
        cached_time, data = _FORECAST_CACHE[cache_key]
        if now - cached_time < 3600:  # 1 hour cache
            return data

    start_dt = datetime.datetime.strptime(start_date, "%Y-%m-%d")
    end_dt = datetime.datetime.strptime(end_date, "%Y-%m-%d")
    today = datetime.datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)

    clipped_start = max(start_dt, today)
    clipped_end = min(end_dt, today + datetime.timedelta(days=15))

    if clipped_start > clipped_end:
        return []

    url = (
        f"https://api.open-meteo.com/v1/forecast"
        f"?latitude={lat}&longitude={lon}"
        f"&start_date={clipped_start.strftime('%Y-%m-%d')}&end_date={clipped_end.strftime('%Y-%m-%d')}"
        f"&daily=precipitation_sum,wind_speed_10m_max,temperature_2m_max"
        f"&timezone=Asia/Kolkata"
        f"&forecast_days=16"
    )

    try:
        async with httpx.AsyncClient() as client:
            resp = await client.get(url, timeout=5.0)
            resp.raise_for_status()
            data = resp.json()

            daily = data.get("daily", {})
            times = daily.get("time", [])
            rain = daily.get("precipitation_sum", [])
            wind = daily.get("wind_speed_10m_max", [])
            temp = daily.get("temperature_2m_max", [])

            result = []
            for i, d in enumerate(times):
                d_dt = datetime.datetime.strptime(d, "%Y-%m-%d")
                if not (start_dt <= d_dt <= end_dt):
                    continue
                r = float(rain[i]) if rain[i] is not None else 0.0
                w = float(wind[i]) if wind[i] is not None else 0.0
                t = float(temp[i]) if temp[i] is not None else 0.0

                result.append({
                    "date": d,
                    "rain_mm": r,
                    "wind_kmh": w,
                    "max_temp_c": t,
                    "risk_level": get_risk_level(r, w)
                })

            _FORECAST_CACHE[cache_key] = (now, result)
            return result
    except Exception as e:
        logger.warning(f"Forecast API failed: {e}")
        return []

async def fetch_history(lat: float, lon: float, start_date: str, end_date: str) -> dict:
    cache_key = f"{lat},{lon},{start_date},{end_date}"
    now = datetime.datetime.now(datetime.timezone.utc).timestamp()

    if cache_key in _HISTORY_CACHE:
        cached_time, data = _HISTORY_CACHE[cache_key]
        if now - cached_time < 86400:  # 24 hour cache
            return data

    # Calculate previous 5 years
    start_dt = datetime.datetime.strptime(start_date, "%Y-%m-%d")
    end_dt = datetime.datetime.strptime(end_date, "%Y-%m-%d")
    current_year = start_dt.year
    years = [current_year - i for i in range(1, 6)]

    total_rain = 0.0
    heavy_days = 0
    days_count = 0

    try:
        async with httpx.AsyncClient() as client:
            for y in years:
                y_start = start_dt.replace(year=y).strftime("%Y-%m-%d")
                y_end = end_dt.replace(year=y).strftime("%Y-%m-%d")

                url = (
                    f"https://archive-api.open-meteo.com/v1/archive"
                    f"?latitude={lat}&longitude={lon}"
                    f"&start_date={y_start}&end_date={y_end}"
                    f"&daily=precipitation_sum"
                    f"&timezone=Asia/Kolkata"
                )

                resp = await client.get(url, timeout=5.0)
                if resp.status_code != 200:
                    continue

                data = resp.json()
                daily_rain = data.get("daily", {}).get("precipitation_sum", [])

                for r in daily_rain:
                    if r is not None:
                        total_rain += float(r)
                        if float(r) >= 64.5:
                            heavy_days += 1
                        days_count += 1

        if days_count == 0:
            return {}

        avg_rain = round(total_rain / days_count, 1)  # Average DAILY rain

        # Load history templates
        templates_file = CONTENT_DIR / "history_templates.json"
        summary = {"en": "Historically {avgRain} mm/day average rain, {heavyDays} heavy rain days over these dates in the past {years} years."}
        if templates_file.exists():
            try:
                summary = json.loads(templates_file.read_text())
            except Exception:
                pass

        # Replace placeholders
        filled_summary = {}
        for lang, text in summary.items():
            filled_summary[lang] = text.replace("{avgRain}", str(avg_rain)) \
                                       .replace("{heavyDays}", str(heavy_days)) \
                                       .replace("{years}", "5")

        result = {
            "years": sorted(years),
            "avg_rain_mm": avg_rain,
            "heavy_rain_days": heavy_days,
            "summary": filled_summary
        }
        _HISTORY_CACHE[cache_key] = (now, result)
        return result
    except Exception as e:
        logger.warning(f"History API failed: {e}")
        return {}
