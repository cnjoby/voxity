"""Personal voice routing: sourced tools, destination questions, and mutual human chat."""

import asyncio
import json
import re
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from .chat import ChatRules
from .contracts import session_capabilities
from .errors import Problem
from .models import ExchangeUtterance

YES = {
    "好",
    "好的",
    "可以",
    "愿意",
    "行",
    "嗯",
    "好啊",
    "你陪我聊",
    "由你陪聊",
    "让agent陪聊",
    "让ai陪聊",
}
NO = {"不用", "不要", "不愿意", "算了", "取消", "不聊了", "结束聊天", "先不聊了"}


def clean(text):
    text = re.sub(r"^(?:hi|hello|嗨|你好)[，,、\s]*", "", text.strip(), flags=re.I)
    text = re.sub(r"^声呐[，,、\s]*", "", text)
    return text.strip("，,。！？?!、 \n")


def intent(text):
    text = clean(text)
    if re.search(r"别发|不要公开|不公开|不要发出去|只是聊天|只是私聊", text):
        return None
    if re.search(r"(?:和|跟|让|由|请|想).*?(?:ai|agent|助手|声呐|你).*?(?:聊|说说)", text, re.I):
        return "private_chat"
    if re.search(
        r"(?:找人|找个人|找.{0,3}车友|找.{0,6}真人|跟.{0,6}车友|和.{0,6}车友|跟别人|和别人).*?(?:闲聊|聊天|聊聊)",
        text,
    ) and not re.search(r"不想|不聊", text):
        return "chat"
    if re.search(r"闲聊|聊聊天|陪我聊|你会什么|你能做什么|想听你聊", text):
        return "private_chat"
    if re.search(r"天气|下雨|气温|降雨|温度|多少度|冷不冷|热不热|带伞", text) and re.search(
        r"吗|么|怎样|怎么样|如何|多少|查|问|看看|会不会|[？?]|明天|明日|后天|明早|明晚|今晚|^天气$",
        text,
    ):
        return "weather"
    if re.search(r"^(?:请|帮我)?(?:听听|介绍|解说)", text) or (
        re.search(r"故事|停车规则|营业时间|哪个入口|公告", text)
        and re.search(r"[？?]|吗|怎么|哪个|查|听|介绍", text)
    ):
        return "knowledge"
    if not re.search(
        r"吗|么|怎样|怎么样|如何|有没有|多少|查|问|堵不堵|下雨没有|下雨没|[？?]", text
    ):
        return None
    if re.search(r"车位|停车|停得下", text):
        return "human_question"
    if re.search(r"问问|问一下|帮我问|现场的人|那边的人|那边的车友", text):
        return "human_question"
    if re.search(r"天气|下雨|气温|降雨", text):
        return "weather"
    if re.search(r"路况|堵车|堵不堵|拥堵|通勤", text):
        return "route"
    return None


WEATHER_TIME = r"大后天|后天|明天|明日|明早|明晚|今天|今日|今晚|现在|此刻"


def weather_followup(text):
    return bool(
        re.fullmatch(
            rf"(?:那|那么)?(?:{WEATHER_TIME})(?:呢|怎样|怎么样)?|(?:那)?(?:会下雨吗|会不会下雨|多少度|温度多少|要带伞吗|需要带伞吗)",
            clean(text),
        )
    )


def weather_date(text, now):
    offset = next(
        (
            n
            for pattern, n in [
                (r"大后天", 3),
                (r"后天", 2),
                (r"明天|明日|明早|明晚", 1),
                (r"今天|今日|今晚|会.{0,3}雨|带伞", 0),
            ]
            if re.search(pattern, text)
        ),
        None,
    )
    return (
        (
            datetime.fromtimestamp(now, ZoneInfo("Asia/Shanghai")).date() + timedelta(days=offset)
        ).isoformat()
        if offset is not None
        else None
    )


def weather_destination(text):
    text = re.sub(WEATHER_TIME, "", clean(text))
    text = re.sub(
        r"^(?:请|麻烦|帮我|给我)?(?:查一下|查查|查|查询|看看|看一下|问一下|问问)?[，,\s]*", "", text
    )
    text = re.split(r"天气|下雨|降雨|气温|温度|多少度|冷不冷|热不热|带伞|会不会", text)[0]
    text = re.sub(r"(?:会|将会|是不是|是否|的|要|需要|需不需要)$", "", text).strip("，, ")
    if not text or re.fullmatch(
        r"那|那么|这里|这边|附近|当前地点|我这边|我们这边|怎样|怎么样|如何|会", text
    ):
        return None
    return text


def destination_query(text, continuation=False):
    text = clean(text)
    text = re.sub(r"^(?:就是|是|我说的是|我指的是|目的地是)", "", text)
    text = re.sub(
        r"^(?:请|帮我|麻烦)?(?:查一下|查查|看看|问问|问一下|查询|了解一下)[，,\s]*", "", text
    )
    match = re.search(r"(?:我(?:想|要|准备)?去|去|前往|到)([^，,。！？?!]{2,60})", text)
    query = match[1] if match else text
    query = re.split(
        r"[，,。！？?!]|现在|今天|还有|还能|能不能|能否|可不可以|可以|有没有|有无|下雨|天气|气温|降雨|路况|堵不堵|堵车|拥堵|停车位|车位|停得下|的故事|的介绍|停车规则|营业时间|哪个入口|的公告",
        query,
    )[0]
    query = re.sub(r"(?:那边|那里|的|现在|景区还有)$", "", query).strip()
    if re.search(r"前面|前方|那个|某个|那家|附近景区|附近的景区|这里|这边|这路口|这个路口", query):
        return None
    if query in (
        "景区",
        "地方",
        "公司",
        "我公司",
        "我们公司",
        "单位",
        "家",
        "我家",
        "公园",
        "停车场",
        "",
        "那边",
        "还有",
    ):
        return query if query in ("公司", "我公司", "我们公司", "单位", "家", "我家") else None
    if not 2 <= len(query) <= 60 or (not continuation and query == clean(text) and not match):
        # A bare place is accepted only as an answer to an outstanding clarification.
        return query if len(query) >= 2 and query != clean(text) else None
    return query


def normalized_place(value):
    return re.sub(r"^(?:上海市|上海)", "", re.sub(r"\s+", "", value))


def agent_response(kind, text, sources=()):
    return {"speaker": "agent", "kind": kind, "text": text, "sources": list(sources)}


def affirmative(text):
    return text.lower() in YES or bool(
        re.fullmatch(
            r"(?:好|好的|可以|愿意|行)[，,\s]*(?:你|声呐|agent|ai)(?:来)?陪我聊(?:天|聊)?(?:吧|会儿)?",
            text.lower(),
        )
    )


def accepts_human_offer(text):
    return affirmative(text) or bool(
        re.fullmatch(
            r"(?:(?:好啊|好的|好|可以|行)[，,\s]*)?(?:你|请你|麻烦你|请)?帮我(?:问一下|问一问|问问)(?:吧|啊|呀|嘞)?",
            clean(text),
        )
    )


def original_query(pending, data):
    return json.loads(pending["metadata_json"])["transcript"] if pending else data.transcript


class AgentRules(ChatRules):
    def require_agent_permission(self, session, action, plan=None):
        """The planner proposes actions; only this trusted executor grants them."""
        capability = {
            "respond": "private_query",
            "private_chat": "private_query",
            "accept_agent": "private_query",
            "question": "request_humans",
            "request_chat": "match_chat",
            "chat_message": "match_chat",
            "scene": "publish_scene",
            "answer": "reply_thread",
            "reply": "reply_thread",
        }.get(action)
        if action == "clarify":
            kind = (plan or {}).get("intent")
            capability = {"human_question": "request_humans", "chat": "match_chat"}.get(
                kind, "private_query"
            )
            if (plan or {}).get("private_lookup"):
                capability = "private_query"
        if capability and not session_capabilities(session).permits(capability):
            legacy = session["capabilities_json"] is None
            code = (
                "NETWORK_NOT_AUTHORIZED"
                if legacy and capability in ("request_humans", "match_chat")
                else "CAPABILITY_NOT_AUTHORIZED"
            )
            raise Problem(403, code, f"本次会话未授权 {capability}")

    def pending_agent(self, db, sid):
        row = db.execute("SELECT * FROM agent_pending WHERE session_id=?", (sid,)).fetchone()
        return dict(row) if row else None

    async def plan_agent_turn(
        self, user, session, data, audio, pending, intent_override=None, public_intent=False
    ):
        text, kind = clean(data.transcript), intent_override or intent(data.transcript)
        private_lookup = (
            kind == "human_question"
            and session_capabilities(session).private_query
            and not public_intent
            and not re.search(r"问问|问一下|帮我问|现场的人|那边的人|那边的车友", text)
        )
        async with self.async_scope() as db:
            chat = self.active_chat(db, user, session["id"])
            chat = dict(chat) if chat else None
            private_default = (self.voice_policy(db, session) or {}).get("private_default", False)
            weather_context = (self.voice_policy(db, session) or {}).get("weather_context", {})
        follow_weather = (
            weather_followup(text)
            and weather_context.get("updated_at", 0) + 600 > self.clock()
            and not (chat and chat["state"] == "paired")
        )
        if follow_weather:
            kind = "weather"
        if text.lower() in NO:
            return {"action": "cancel_agent", "chat_id": chat["id"] if chat else None}
        if kind == "private_chat":
            self.require_agent_permission(session, "private_chat")
            return {"action": "private_chat", "leave_chat": True}
        if kind == "chat":
            self.require_agent_permission(session, "request_chat")
            return {"action": "request_chat"}
        if (
            chat
            and chat["state"] == "paired"
            and (kind not in ("weather", "route", "knowledge") or public_intent)
            and not re.search(r"声呐|帮我查|查询", data.transcript)
        ):
            return {"action": "chat_message", "chat_id": chat["id"]}
        if (
            pending
            and json.loads(pending["plan_json"]).get("stage") == "human_offer"
            and accepts_human_offer(text)
        ):
            old = json.loads(pending["plan_json"])
            return {"action": "question", "place": old["place"], "pending": pending}
        if pending and kind is None:
            old = json.loads(pending["plan_json"])
            if old["stage"] == "agent_offer":
                if chat and chat["state"] == "paired":
                    return {"action": "chat_message", "chat_id": chat["id"]}
                if affirmative(text):
                    return {"action": "accept_agent", "chat_id": old["chat_id"]}
                return {
                    "action": "clarify",
                    "plan": old,
                    "pending": pending,
                    "response": agent_response(
                        "chat_offer", "暂时没有匹配到愿意闲聊的车友，要我陪你聊吗？"
                    ),
                }
            if old["stage"] == "human_offer":
                if not affirmative(text):
                    return {
                        "action": "clarify",
                        "plan": old,
                        "pending": pending,
                        "response": agent_response(
                            "human_offer", "要帮你向目的地的现场参与者提问吗？"
                        ),
                    }
                return {"action": "question", "place": old["place"], "pending": pending}
            kind = old["intent"]
            private_lookup = old.get("private_lookup", False)
            choices = old.get("choices", [])
            selected = None
            numbers = {
                "第一个": 0,
                "第1个": 0,
                "1": 0,
                "第二个": 1,
                "第2个": 1,
                "2": 1,
                "第三个": 2,
                "第3个": 2,
                "3": 2,
                "第四个": 3,
                "4": 3,
                "第五个": 4,
                "5": 4,
            }
            index = numbers.get(text)
            if index is not None and index < len(choices):
                selected = choices[index]
            else:
                exact = [
                    p for p in choices if normalized_place(p["label"]) == normalized_place(text)
                ]
                if len(exact) == 1:
                    selected = exact[0]
            query = destination_query(text, continuation=True)
            alias = old.get("alias")
        else:
            if kind is None:
                if chat and chat["state"] == "paired":
                    return {"action": "chat_message", "chat_id": chat["id"]}
                if chat and chat["state"] == "agent":
                    return {"action": "private_chat"}
                return None
            pending, selected = None, None
            query, alias = destination_query(text), None
        if kind not in ("weather", "route", "human_question", "knowledge"):
            return None
        self.require_agent_permission(
            session, "question" if kind == "human_question" and not private_lookup else "respond"
        )
        from .voice_first import PARK_PLACES, PILOT_PLACES

        if not selected:
            matches = [
                (max((len(a) for a in aliases if a in text), default=0), pid)
                for pid, (_, _, _, aliases) in (PILOT_PLACES | PARK_PLACES).items()
            ]
            longest = max((n for n, _ in matches), default=0)
            chosen = [pid for n, pid in matches if n == longest and n > 0]
            if len(chosen) == 1:
                async with self.async_scope() as db:
                    selected = dict(
                        db.execute("SELECT * FROM places WHERE id=?", (chosen[0],)).fetchone()
                    )
        if not selected and re.search(r"这里|这边|当前地点", text):
            async with self.async_scope() as db:
                p = db.execute("SELECT * FROM places WHERE id=?", (session["place_id"],)).fetchone()
                selected = dict(p) if p and p["id"] != "private:unlocated" else None
        requested_date = None
        if kind == "weather":
            prior_plan = json.loads(pending["plan_json"]) if pending else {}
            requested_date = (
                (
                    weather_context.get("date")
                    if follow_weather and not re.search(WEATHER_TIME, text)
                    else None
                )
                or weather_date(text, self.clock())
                or prior_plan.get("weather_date")
            )
            query = (
                None
                if follow_weather
                else query
                if pending and intent(text) is None
                else weather_destination(text)
            )
            if not query and not selected:
                pid = weather_context.get("place_id") if follow_weather else session["place_id"]
                async with self.async_scope() as db:
                    row = db.execute("SELECT * FROM places WHERE id=?", (pid,)).fetchone()
                    selected = dict(row) if row and row["id"] != "private:unlocated" else None
        if query in ("公司", "我公司", "我们公司", "单位", "家", "我家"):
            alias = "公司" if query in ("公司", "我公司", "我们公司", "单位") else "家"
            async with self.async_scope() as db:
                row = db.execute(
                    "SELECT p.* FROM user_destinations d JOIN places p ON p.id=d.place_id "
                    "WHERE d.user_id=? AND d.alias=?",
                    (user, alias),
                ).fetchone()
                selected = dict(row) if row else None
            query = None
        if not selected and query:
            try:
                choices = await asyncio.to_thread(self.geo.search, query)
            except Problem:
                return {
                    "action": "respond",
                    "response": agent_response(
                        "unavailable", "地点查询暂不可用，这次没有发送给其他人。"
                    ),
                }
            exact = [p for p in choices if normalized_place(p["label"]) == normalized_place(query)]
            selected = exact[0] if len(exact) == 1 else None
        else:
            choices = []
        if not selected:
            question = (
                f"{alias}在哪个城市、什么地址？"
                if alias
                else "你说的是哪个地点？请说城市和具体名称。"
            )
            if re.search(r"景区|公园|停车", text) and not alias:
                question = "你说的是哪个景区？请说城市和景区名称。"
            if kind == "weather" and not choices:
                question = "你想查哪个城市的天气？"
            if choices:
                question = (
                    "找到几个地点："
                    + "；".join(f"{i + 1}，{p['label']}" for i, p in enumerate(choices))
                    + "。你指哪一个？"
                )
            return {
                "action": "clarify",
                "pending": pending,
                "plan": {
                    "stage": "destination",
                    "intent": kind,
                    "choices": choices,
                    "alias": alias,
                    "private_lookup": private_lookup,
                    "weather_date": requested_date,
                },
                "response": agent_response("clarification", question),
            }
        plan = {
            "place": selected,
            "pending": pending,
            "alias": alias,
            "tool_kind": kind,
            "weather_date": requested_date,
        }
        if private_lookup or kind in ("knowledge", "weather", "route"):
            purpose = "parking" if private_lookup else kind
            async with self.async_scope() as db:
                found = self.find_information(
                    db, user, selected["id"], original_query(pending, data), purpose
                )
            if kind == "weather" and requested_date:
                found = {"items": []}  # A current observation is not a future forecast.
            if found["items"] and kind != "route":
                return {
                    **plan,
                    "action": "respond",
                    "response": self.information_response(found),
                    "evidence_ids": [i["post_id"] for i in found["items"][:3]],
                    "evidence_purpose": purpose,
                }
            if kind == "knowledge":
                return {
                    **plan,
                    "action": "respond",
                    "response": agent_response("no_information", "这个地点暂无适用的知识或解说。"),
                }
            if private_lookup and (
                not session_capabilities(session).request_humans or private_default
            ):
                return {
                    **plan,
                    "action": "clarify",
                    "plan": {
                        "stage": "human_offer",
                        "intent": "parking",
                        "place": selected,
                    },
                    "response": agent_response(
                        "human_offer",
                        "暂无有效车位信息，要我问问在场的人吗？",
                    ),
                }
        if kind == "human_question":
            return {**plan, "action": "question"}
        try:
            response = (
                await self.query_agent_tool(user, kind, selected, forecast_date=requested_date)
                if kind == "weather" and requested_date
                else await self.query_agent_tool(user, kind, selected)
            )
        except Problem as exc:
            if kind == "weather" and requested_date:
                return {
                    **plan,
                    "action": "respond",
                    "response": agent_response(
                        "unavailable",
                        "暂时没有拿到这一天的有效天气预报。你可以换个日期，或继续和我聊。",
                    ),
                }
            if kind == "route" and found["items"]:
                response = self.information_response(found)
                response["text"] = (
                    "路线工具暂不可用，以下仅是目的地的现场报告，不代表全程路况。"
                    + response["text"]
                )
                return {
                    **plan,
                    "action": "respond",
                    "response": response,
                    "evidence_ids": [i["post_id"] for i in found["items"][:3]],
                }
            if exc.code == "LOCATION_REQUIRED":
                return {
                    **plan,
                    "action": "respond",
                    "response": agent_response(
                        "location_required", "需要你当前的有效定位，才能查询前往这个地点的路况。"
                    ),
                }
            return {
                **plan,
                "action": "clarify",
                "plan": {"stage": "human_offer", "intent": kind, "place": selected},
                "response": agent_response(
                    "human_offer", "工具暂时没有可用结果，要帮你问问目的地的现场参与者吗？"
                ),
            }
        return {**plan, "action": "respond", "response": response}

    async def query_agent_tool(self, user, kind, place, forecast_date=None):
        # External calls never hold SQLite transactions. Coordinates come only from this user.
        if kind == "weather":
            weather = (
                await asyncio.to_thread(self.geo.forecast, place, forecast_date)
                if forecast_date
                else await asyncio.to_thread(self.geo.weather, place)
            )
            max_age = 36 * 3600 if forecast_date else 10800
            try:
                report = datetime.fromisoformat(weather["reporttime"]).replace(
                    tzinfo=ZoneInfo("Asia/Shanghai")
                )
                age = self.clock() - report.timestamp()
                if not -600 <= age <= max_age:
                    raise ValueError()
            except (ValueError, TypeError, KeyError) as exc:
                raise Problem(503, "WEATHER_STALE", "天气报告已过时") from exc
            if forecast_date:
                text = f"{weather['city']}{forecast_date}的预报，白天{weather['dayweather']}、{weather['daytemp']}摄氏度，夜间{weather['nightweather']}、{weather['nighttemp']}摄氏度。这是高德区县天气预报。"
            else:
                text = f"{place['label']}所在区县{weather['city']}，{report:%H点%M分}发布的天气是{weather['weather']}，{weather['temperature']}摄氏度。这是区县天气，不能确认景区此刻是否下雨。"
            return agent_response(
                "weather",
                text,
                [
                    {
                        **weather,
                        "place_id": place["id"],
                        "place_label": place["label"],
                        "valid_until": datetime.fromtimestamp(
                            report.timestamp() + max_age, ZoneInfo("UTC")
                        ).isoformat(),
                        **self.now(),
                    }
                ],
            )
        async with self.async_scope() as db:
            p = self.profile_row(db, user)
            if (
                p["source"] not in ("android", "android_mock")
                or p["source"] == "android_mock"
                and not self.settings.allow_mock_location
                or p["sampled_at"] is None
                or not -10 <= self.clock() - p["sampled_at"] < self.settings.location_ttl
                or p["accuracy_m"] is None
                or p["accuracy_m"] > self.settings.location_accuracy_m
            ):
                raise Problem(409, "LOCATION_REQUIRED", "需要当前有效定位")
            origin = (p["latitude"], p["longitude"])
        route = await asyncio.to_thread(self.geo.driving, origin, place)
        traffic = route["traffic_m"]
        slow = sum(traffic.get(k, 0) for k in ("缓行", "拥堵", "严重拥堵"))
        detail = (
            f"其中约{round(slow / 1000, 1)}公里缓行或拥堵。"
            if slow
            else (
                "已返回的路段没有标记缓行或拥堵。"
                if traffic and not traffic.get("未知")
                else "部分路段缺少实时路况，不能确认全程畅通。"
            )
        )
        return agent_response(
            "route",
            f"高德当前规划，到{place['label']}约{round(route['distance_m'] / 1000, 1)}公里，预计{max(1, round(route['duration_seconds'] / 60))}分钟。{detail}",
            [
                {
                    **route,
                    "place_id": place["id"],
                    "place_label": place["label"],
                    "valid_until": datetime.fromtimestamp(
                        self.clock() + 60, ZoneInfo("UTC")
                    ).isoformat(),
                    **self.now(),
                }
            ],
        )

    def finish_agent_turn(self, db, user, session, turn, data, audio, plan):
        action = plan["action"]
        if action == "voice_control":
            return self.finish_voice_control(db, user, session, turn, data, plan)
        if action == "reject":
            return {"status": "deferred", "action": "defer", "reason": plan["reason"]}
        self.require_agent_permission(session, action, plan.get("plan"))
        if (
            plan.get("evidence_ids")
            and not self.validate_information(
                db, user, plan["evidence_ids"], plan.get("evidence_purpose", "all")
            )["valid"]
        ):
            raise Problem(409, "INFORMATION_CHANGED", "信息来源已更新或失效，请重新查询")
        if (
            session["capabilities_json"] is None
            and action
            in ("question", "request_chat", "chat_message", "accept_agent", "private_chat")
            and not session["network_enabled"]
        ):
            return {"status": "deferred", "action": "defer", "reason": "NETWORK_NOT_AUTHORIZED"}
        pending = plan.get("pending")
        original = (
            ExchangeUtterance.model_validate_json(pending["metadata_json"]) if pending else data
        )
        original_audio = bytes(pending["audio"]) if pending else audio
        if action in ("question", "request_chat", "chat_message") and not original_audio:
            raise Problem(422, "ORIGINAL_AUDIO_REQUIRED", "此操作需要本轮真人原声，请使用录音入口")
        if pending:
            live = self.pending_agent(db, session["id"])
            if (
                not live
                or live["turn_id"] != pending["turn_id"]
                or live["expires_at"] <= self.clock()
            ):
                raise Problem(409, "CONTEXT_CHANGED", "澄清已失效")
            first = db.execute(
                "SELECT * FROM exchange_turns WHERE id=?", (pending["turn_id"],)
            ).fetchone()
            if (
                first["session_revision"] != session["revision"]
                or first["profile_revision"] != turn["profile_revision"]
            ):
                raise Problem(409, "CONTEXT_CHANGED", "澄清授权已改变")
        if action == "clarify":
            expires = (
                pending["expires_at"]
                if pending
                else min(session["expires_at"], self.clock() + self.settings.exchange_turn_ttl)
            )
            db.execute(
                "INSERT INTO agent_pending VALUES (?,?,?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET "
                "turn_id=excluded.turn_id,metadata_json=excluded.metadata_json,audio=excluded.audio,plan_json=excluded.plan_json,expires_at=excluded.expires_at",
                (
                    session["id"],
                    pending["turn_id"] if pending else turn["id"],
                    original.model_dump_json(),
                    original_audio,
                    json.dumps(plan["plan"]),
                    expires,
                ),
            )
            return {"status": "clarifying", "action": "clarify", "agent_response": plan["response"]}
        db.execute("DELETE FROM agent_pending WHERE session_id=?", (session["id"],))
        if (
            action == "respond"
            and plan.get("tool_kind") == "weather"
            and plan.get("response", {}).get("kind") == "weather"
        ):
            policy = self.voice_policy(db, session)
            if policy is not None:
                policy["weather_context"] = {
                    "place_id": plan["place"]["id"],
                    "date": plan.get("weather_date"),
                    "updated_at": self.clock(),
                }
                self.save_voice_policy(db, session["id"], policy)
        if action == "question":
            if plan.get("replace_task"):
                task = self.task_row(db, user, plan["replace_task"])
                if task["status"] not in ("cancelled", "resolved", "timed_out", "failed"):
                    self.change_personal_task(
                        db, user, task["id"], task["revision"], data.client_request_id, "cancel"
                    )
            if self.clock() - original.recorded_at.timestamp() > self.settings.exchange_turn_ttl:
                raise Problem(410, "TURN_EXPIRED", "原提问已过期，请重新提问")
            place = plan["place"]
            self.register_agent_place(db, place)
            from .models import QuestionDraft

            draft, created = self.draft(
                db,
                user,
                QuestionDraft(
                    client_request_id=original.client_request_id,
                    transcript=original.transcript,
                    target_place_id=place["id"],
                    recorded_at=original.recorded_at,
                    kind="question",
                ),
                original_audio,
            )
            try:
                self.confirm(db, user, draft["post_id"])
            except BaseException:
                if created:
                    (self.store.audio_dir / f"{draft['post_id']}.wav").unlink(missing_ok=True)
                raise
            pid = draft["post_id"]
            db.execute(
                "UPDATE exchange_sessions SET context_post_id=? WHERE id=?", (pid, session["id"])
            )
            result = {
                "status": "published",
                "action": "question",
                "post_id": pid,
                "root_id": pid,
                "target_place": {"id": place["id"], "label": place["label"]},
            }
            if pending:
                db.execute(
                    "UPDATE exchange_turns SET status='published',post_id=?,result_json=? WHERE id=?",
                    (pid, json.dumps(result), pending["turn_id"]),
                )
        elif action == "request_chat":
            chat = self.request_chat(db, user, session, data.transcript, audio)
            if chat["state"] == "paired":
                return {"status": "chatting", "action": "human_chat", "chat_id": chat["id"]}
            if chat["state"] == "agent":
                return {"status": "responded", "action": "private_chat"}
            response = agent_response("chat_offer", "暂时没有匹配到愿意闲聊的车友，要我陪你聊吗？")
            return self.finish_agent_turn(
                db,
                user,
                session,
                turn,
                data,
                audio,
                {
                    "action": "clarify",
                    "plan": {"stage": "agent_offer", "intent": "chat", "chat_id": chat["id"]},
                    "response": response,
                },
            )
        elif action == "accept_agent":
            chat = self.active_chat(db, user, session["id"])
            if not chat or chat["id"] != plan["chat_id"]:
                raise Problem(409, "CHAT_CLOSED", "闲聊请求已结束")
            if chat["state"] == "paired":
                return {"status": "chatting", "action": "human_chat", "chat_id": chat["id"]}
            db.execute(
                "UPDATE chat_requests SET state='agent',audio=X'',expires_at=? WHERE id=?",
                (session["expires_at"], chat["id"]),
            )
            return {
                "status": "responded",
                "action": "agent_chat",
                "agent_response": agent_response("agent_chat", "好，你想聊点什么？"),
            }
        elif action == "chat_message":
            chat = self.active_chat(db, user, session["id"])
            if not chat or chat["id"] != plan["chat_id"]:
                raise Problem(409, "CHAT_CLOSED", "闲聊对象已改变")
            mid = self.chat_message(db, chat, data.transcript, audio)
            return {
                "status": "chatting",
                "action": "human_chat",
                "post_id": mid,
                "chat_id": chat["id"],
            }
        elif action == "cancel_agent":
            if plan["chat_id"]:
                self.close_chat(db, plan["chat_id"])
            return {"status": "ignored", "action": "ignore"}
        elif action == "private_chat":
            if plan.get("leave_chat"):
                chat = self.active_chat(db, user, session["id"])
                if chat:
                    self.close_chat(db, chat["id"])
            return {"status": "responded", "action": "private_chat"}
        else:
            result = {"status": "responded", "action": "agent", "agent_response": plan["response"]}
            if plan.get("place"):
                self.register_agent_place(db, plan["place"])
                result["target_place"] = {
                    "id": plan["place"]["id"],
                    "label": plan["place"]["label"],
                }
        if plan.get("alias") and plan.get("place"):
            self.register_agent_place(db, plan["place"])
            db.execute(
                "INSERT INTO user_destinations VALUES (?,?,?) ON CONFLICT(user_id,alias) DO UPDATE SET place_id=excluded.place_id",
                (user, plan["alias"], plan["place"]["id"]),
            )
        return result

    def register_agent_place(self, db, place):
        db.execute(
            "INSERT INTO places VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET label=excluded.label",
            (
                place["id"],
                place["label"],
                place["latitude"],
                place["longitude"],
                place["kind"],
                self.clock(),
            ),
        )
