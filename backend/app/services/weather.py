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
    """Risk levels: LOW <20 mm, MEDIUM 20-64, HIGH >=65"""
    if rain_mm >= 65:
        return "HIGH"
    if rain_mm >= 20:
        return "MEDIUM"
    return "LOW"


from zoneinfo import ZoneInfo


async def fetch_forecast_met(lat: float, lon: float, start_dt, end_dt) -> list[dict] | None:
    url = f"https://api.met.no/weatherapi/locationforecast/2.0/compact?lat={lat}&lon={lon}"
    headers = {"User-Agent": "Sahay/1.0 github.com/ShyamSharwan007/Sahay"}
    try:
        async with httpx.AsyncClient() as client:
            logger.info(f"MET Norway request URL: {url}")
            resp = await client.get(url, headers=headers, timeout=10.0)
            logger.info(f"MET Norway status: {resp.status_code}")
            if resp.status_code != 200:
                logger.warning(f"MET Norway returned {resp.status_code}: {resp.text}")
                return None
            data = resp.json()

            timeseries = data.get("properties", {}).get("timeseries", [])
            tz = ZoneInfo("Asia/Kolkata")
            daily_data = {}
            for ts in timeseries:
                time_str = ts.get("time", "")
                if not time_str:
                    continue
                dt_utc = datetime.datetime.fromisoformat(time_str.replace("Z", "+00:00"))
                dt_ist = dt_utc.astimezone(tz)
                date_str = dt_ist.strftime("%Y-%m-%d")
                d_dt = dt_ist.date()

                if not (start_dt.date() <= d_dt <= end_dt.date()):
                    continue

                if date_str not in daily_data:
                    daily_data[date_str] = {
                        "rain_sum": 0.0,
                        "max_temp": -999.0,
                        "max_wind": -1.0,
                    }

                details = ts.get("data", {}).get("instant", {}).get("details", {})
                temp = details.get("air_temperature")
                if temp is not None:
                    daily_data[date_str]["max_temp"] = max(daily_data[date_str]["max_temp"], temp)
                wind = details.get("wind_speed")
                if wind is not None:
                    daily_data[date_str]["max_wind"] = max(daily_data[date_str]["max_wind"], wind)

                next_1 = (
                    ts.get("data", {})
                    .get("next_1_hours", {})
                    .get("details", {})
                    .get("precipitation_amount")
                )
                if next_1 is not None:
                    daily_data[date_str]["rain_sum"] += next_1
                else:
                    next_6 = (
                        ts.get("data", {})
                        .get("next_6_hours", {})
                        .get("details", {})
                        .get("precipitation_amount")
                    )
                    if next_6 is not None:
                        daily_data[date_str]["rain_sum"] += next_6

            result = []
            for date_str in sorted(daily_data.keys()):
                d_data = daily_data[date_str]
                r = d_data["rain_sum"]
                w = round(max(0.0, d_data["max_wind"]) * 3.6, 1) if d_data["max_wind"] >= 0 else 0.0
                t = d_data["max_temp"] if d_data["max_temp"] > -999 else 0.0

                result.append(
                    {
                        "date": date_str,
                        "rainMm": round(r, 1),
                        "windKmh": w,
                        "maxTempC": t,
                        "riskLevel": get_risk_level(r, w),
                    }
                )
            logger.info("Successfully fetched forecast from MET Norway")
            return result
    except Exception as e:
        logger.error(f"MET Norway API failed: {e}", exc_info=True)
        return None


async def fetch_forecast_openmeteo(lat: float, lon: float, start_dt, end_dt) -> list[dict] | None:
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
    )

    try:
        async with httpx.AsyncClient() as client:
            logger.info(f"Open-Meteo request URL: {url}")
            resp = await client.get(url, timeout=5.0)
            logger.info(f"Open-Meteo status: {resp.status_code}")
            if resp.status_code != 200:
                logger.warning(f"Open-Meteo returned {resp.status_code}: {resp.text}")
                return None
            data = resp.json()

            daily = data.get("daily", {})
            times = daily.get("time", [])
            rain = daily.get("precipitation_sum", [])
            wind = daily.get("wind_speed_10m_max", [])
            temp = daily.get("temperature_2m_max", [])

            result = []
            for i, d in enumerate(times):
                d_dt = datetime.datetime.strptime(d, "%Y-%m-%d").date()
                if not (start_dt.date() <= d_dt <= end_dt.date()):
                    continue
                r = float(rain[i]) if rain[i] is not None else 0.0
                w = float(wind[i]) if wind[i] is not None else 0.0
                t = float(temp[i]) if temp[i] is not None else 0.0

                result.append(
                    {
                        "date": d,
                        "rainMm": r,
                        "windKmh": w,
                        "maxTempC": t,
                        "riskLevel": get_risk_level(r, w),
                    }
                )
            logger.info("Successfully fetched forecast from Open-Meteo")
            return result
    except Exception as e:
        logger.error(f"Open-Meteo API failed: {e}", exc_info=True)
        return None


async def fetch_forecast(lat: float, lon: float, start_date: str, end_date: str) -> list[dict]:
    cache_key = f"{lat},{lon},{start_date},{end_date}"
    now = datetime.datetime.now(datetime.timezone.utc).timestamp()

    if cache_key in _FORECAST_CACHE:
        cached_time, data = _FORECAST_CACHE[cache_key]
        if now - cached_time < 3600:  # 1 hour cache
            return data

    start_dt = datetime.datetime.strptime(start_date, "%Y-%m-%d")
    end_dt = datetime.datetime.strptime(end_date, "%Y-%m-%d")

    result = await fetch_forecast_met(lat, lon, start_dt, end_dt)
    if result is None:
        logger.warning("MET Norway failed, falling back to Open-Meteo")
        result = await fetch_forecast_openmeteo(lat, lon, start_dt, end_dt)

    if result is None:
        result = []

    if result:
        _FORECAST_CACHE[cache_key] = (now, result)
    else:
        _FORECAST_CACHE.pop(cache_key, None)
    return result


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
        summary = {
            "en": "Historically {avgRain} mm/day average rain, {heavyDays} heavy rain days over these dates in the past {years} years."
        }
        if templates_file.exists():
            try:
                summary = json.loads(templates_file.read_text())
            except Exception:
                pass

        # Replace placeholders
        filled_summary = {}
        for lang, text in summary.items():
            filled_summary[lang] = (
                text.replace("{avgRain}", str(avg_rain))
                .replace("{heavyDays}", str(heavy_days))
                .replace("{years}", "5")
            )

        result = {
            "years": sorted(years),
            "avg_rain_mm": avg_rain,
            "heavy_rain_days": heavy_days,
            "summary": filled_summary,
        }
        _HISTORY_CACHE[cache_key] = (now, result)
        return result
    except Exception as e:
        logger.warning(f"History API failed: {e}")
        return {}
