"""AMap adapter. Network I/O is outside SQLite write transactions."""

import math
from datetime import UTC

import httpx

from .errors import Problem
from .storage import digest


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
                        "id": "amap:" + digest(str(poi["id"]))[:24],
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
                        "id": "amap:" + digest(str(poi["id"]))[:24],
                        "label": str(poi["name"])[:160],
                        "latitude": lat,
                        "longitude": lon,
                        "kind": "poi",
                    }
                )
            except (KeyError, TypeError, ValueError):
                continue
        return results
