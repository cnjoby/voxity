"""AMap adapter. Network I/O is outside SQLite write transactions."""

import math
from datetime import UTC

import httpx

from .errors import Problem


def distance_m(lat1, lon1, lat2, lon2):
    a, b = math.radians(lat1), math.radians(lat2)
    h = (
        math.sin((b - a) / 2) ** 2
        + math.cos(a) * math.cos(b) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    )
    return 6371008.8 * 2 * math.asin(math.sqrt(min(1, h)))


def sampled_epoch(fix):
    return fix.sampled_at.astimezone(UTC).timestamp()


class AMap:
    def __init__(self, key, transport=None):
        self.key = key
        self.transport = transport

    def get(self, path, **params):
        if not self.key:
            raise Problem(503, "POI_NOT_CONFIGURED", "地点服务还未开通，请稍后重试")
        try:
            with httpx.Client(
                transport=self.transport, timeout=5, follow_redirects=False
            ) as client:
                r = client.get(
                    "https://restapi.amap.com/v3/" + path, params={"key": self.key, **params}
                )
                r.raise_for_status()
                data = r.json()
            if not isinstance(data, dict) or data.get("status") != "1":
                raise ValueError("provider rejected request")
            return data
        except (httpx.HTTPError, ValueError, TypeError) as exc:
            # Never forward provider URLs, keys or raw messages to logs/clients.
            raise Problem(503, "POI_UNAVAILABLE", "地点服务暂不可用，请稍后重试", True) from exc

    def resolve(self, fix):
        lon, lat = fix.longitude, fix.latitude
        if fix.coordinate_system == "wgs84":
            data = self.get(
                "assistant/coordinate/convert",
                locations=f"{lon:.6f},{lat:.6f}",
                coordsys="gps",
                output="json",
            )
            try:
                lon, lat = map(float, data["locations"].split(","))
                if not (-180 <= lon <= 180 and -90 <= lat <= 90):
                    raise ValueError()
            except (KeyError, TypeError, ValueError) as exc:
                raise Problem(503, "POI_UNAVAILABLE", "无法解析地点坐标", True) from exc
        data = self.get(
            "geocode/regeo",
            location=f"{lon:.6f},{lat:.6f}",
            extensions="all",
            radius=300,
            roadlevel=0,
        )
        geo = data.get("regeocode")
        if not isinstance(geo, dict):
            raise Problem(503, "POI_UNAVAILABLE", "无法解析附近地点", True)
        # The default is a small geographical area, never the nearest shop.
        lat_cell, lon_cell = round(lat, 3), round(lon, 3)
        roads = geo.get("roads") or []
        road = roads[0].get("name", "") if roads else ""
        label = f"{road}附近" if road else geo.get("formatted_address") or "当前位置附近"
        area = {
            "id": f"area:{lat_cell:.3f}:{lon_cell:.3f}",
            "label": str(label)[:160],
            "latitude": lat_cell,
            "longitude": lon_cell,
            "kind": "area",
        }
        choices = [area]
        for poi in (geo.get("pois") or [])[:5]:
            try:
                x, y = map(float, poi["location"].split(","))
                if not (-180 <= x <= 180 and -90 <= y <= 90):
                    continue
                choices.append(
                    {
                        "id": str(poi["id"]),
                        "label": str(poi["name"])[:160],
                        "latitude": y,
                        "longitude": x,
                        "kind": "poi",
                    }
                )
            except (KeyError, TypeError, ValueError):
                continue
        return {"latitude": lat, "longitude": lon, "places": choices}

    def search(self, query):
        data = self.get("place/text", keywords=query, offset=5, page=1, extensions="base")
        results = []
        for poi in (data.get("pois") or [])[:5]:
            try:
                lon, lat = map(float, poi["location"].split(","))
                if not (-180 <= lon <= 180 and -90 <= lat <= 90):
                    continue
                results.append(
                    {
                        "id": str(poi["id"]),
                        "label": str(poi["name"])[:160],
                        "latitude": lat,
                        "longitude": lon,
                        "kind": "poi",
                    }
                )
            except (KeyError, TypeError, ValueError):
                continue
        return results

    def weather(self, place):
        """District observations are not a rain sensor at this exact POI."""
        try:
            geo = self.get(
                "geocode/regeo",
                location=f"{place['longitude']:.6f},{place['latitude']:.6f}",
                extensions="base",
            )["regeocode"]["addressComponent"]
            code = str(geo["adcode"])
            if not code.isdigit() or len(code) != 6:
                raise ValueError()
            live = self.get("weather/weatherInfo", city=code, extensions="base")["lives"][0]
            fields = {
                key: str(live[key])[:80] for key in ("city", "weather", "temperature", "reporttime")
            }
            if any(not value for value in fields.values()):
                raise ValueError()
            return {**fields, "adcode": code, "scope": "district", "provider": "amap"}
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise Problem(503, "WEATHER_UNAVAILABLE", "暂时没有可用的区县天气数据", True) from exc

    def forecast(self, place, target_date):
        """Match an actual forecast date, never substitute a current observation."""
        try:
            geo = self.get(
                "geocode/regeo",
                location=f"{place['longitude']:.6f},{place['latitude']:.6f}",
                extensions="base",
            )["regeocode"]["addressComponent"]
            code = str(geo["adcode"])
            if not code.isdigit() or len(code) != 6:
                raise ValueError()
            result = self.get("weather/weatherInfo", city=code, extensions="all")["forecasts"][0]
            day = next((d for d in result["casts"] if d["date"] == target_date), None)
            if not day:
                raise ValueError()
            fields = {
                k: str(day[k])[:80]
                for k in ("date", "dayweather", "nightweather", "daytemp", "nighttemp")
            }
            if any(not v for v in fields.values()) or any(
                not -90 <= float(fields[k]) <= 70 for k in ("daytemp", "nighttemp")
            ):
                raise ValueError()
            return {
                **fields,
                "city": str(result["city"])[:80],
                "reporttime": str(result["reporttime"])[:80],
                "adcode": code,
                "scope": "district",
                "provider": "amap",
                "weather_type": "forecast",
            }
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise Problem(
                503, "FORECAST_UNAVAILABLE", "暂时没有这一天的有效天气预报", True
            ) from exc

    def driving(self, origin, place):
        try:
            path = self.get(
                "direction/driving",
                origin=f"{origin[1]:.6f},{origin[0]:.6f}",
                destination=f"{place['longitude']:.6f},{place['latitude']:.6f}",
                extensions="all",
                strategy=0,
            )["route"]["paths"][0]
            duration, distance = int(path["duration"]), int(path["distance"])
            if duration <= 0 or distance <= 0:
                raise ValueError()
            congestion = {}
            for step in path.get("steps", []):
                for tmc in step.get("tmcs", []):
                    status = tmc.get("status")
                    if status not in ("畅通", "缓行", "拥堵", "严重拥堵", "未知"):
                        status = "未知"
                    length = int(tmc["distance"])
                    if length < 0:
                        raise ValueError()
                    congestion[status] = congestion.get(status, 0) + length
            return {
                "duration_seconds": duration,
                "distance_m": distance,
                "traffic_m": congestion,
                "provider": "amap",
                "scope": "driving_route",
            }
        except (KeyError, IndexError, TypeError, ValueError, OverflowError) as exc:
            raise Problem(503, "ROUTE_UNAVAILABLE", "暂时没有可用的驾车路线数据", True) from exc
