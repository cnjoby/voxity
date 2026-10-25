"""Versioned public read contracts and fail-closed session capabilities."""

from typing import Literal

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SessionCapabilities(Contract):
    schema_version: Literal["1"] = "1"
    private_query: bool = Field(default=False, strict=True)
    request_humans: bool = Field(default=False, strict=True)
    publish_scene: bool = Field(default=False, strict=True)
    reply_thread: bool = Field(default=False, strict=True)
    match_chat: bool = Field(default=False, strict=True)
    auto_play: bool = Field(default=False, strict=True)

    def permits(self, name):
        if name not in type(self).model_fields or name == "schema_version":
            raise ValueError("Unknown capability")
        return getattr(self, name)

    def any_enabled(self):
        return any(v for k, v in self.model_dump().items() if k != "schema_version")


def session_capabilities(row):
    # NULL means a pre-capabilities session. Never backfill it with new grants.
    if row["capabilities_json"] is not None:
        return SessionCapabilities.model_validate_json(row["capabilities_json"])
    publish = bool(row["auto_publish"])
    network = publish and bool(row["network_enabled"])
    return SessionCapabilities(
        private_query=publish,
        request_humans=network,
        publish_scene=publish,
        reply_thread=publish,
        match_chat=network,
        auto_play=bool(row["auto_play"]),
    )


class MessageTime(Contract):
    observed_at: AwareDatetime | None = None
    recorded_at: AwareDatetime | None = None
    published_at: AwareDatetime | None = None
    collected_at: AwareDatetime
    valid_from: AwareDatetime | None = None
    valid_until: AwareDatetime | None = None


class MessagePlace(Contract):
    about_place_id: str | None = None
    label: str | None = None
    scope: Literal["poi", "entrance", "floor", "area", "unknown"] = "unknown"
    scope_label: str | None = None
    direction: str = "unknown"
    # Location snapshots remain private. This is an opaque owner-only reference.
    recorded_location_ref: str | None = None


class MessageSubject(Contract):
    author_id: str
    principal_id: str
    acting_agent_id: str | None = None
    source_type: Literal["personal", "poi_operator", "external_tool"]
    channel_id: str | None = None
    mentioned_entities: list[str] = Field(default_factory=list)


class MessageEvent(Contract):
    event_id: str | None = None
    event_type: str | None = None
    claim: str | None = None
    relation: str | None = None


class MessageBody(Contract):
    audio_ref: str | None = None
    transcript: str
    transcript_revision: int = Field(default=1, ge=1)
    content_origin: Literal["human_original", "agent_synthesized", "derived_summary"]
    source_refs: list[str] = Field(default_factory=list)


class MessageState(Contract):
    publication: Literal["draft", "published", "withdrawn", "replaced"]
    applicability: Literal["not_started", "valid", "expired", "unknown"]
    event: str | None = None
    task: str | None = None
    delivery: str | None = None
    # Existing content has not passed a new moderation workflow by implication.
    moderation: Literal["not_evaluated", "allowed", "blocked", "review"] = "not_evaluated"


class VoiceMessageView(Contract):
    schema_version: Literal["1"] = "1"
    message_id: str
    kind: Literal["question", "answer", "scene", "knowledge", "announcement", "reply", "chat"]
    time: MessageTime
    place: MessagePlace
    subject: MessageSubject
    event: MessageEvent = Field(default_factory=MessageEvent)
    body: MessageBody
    state: MessageState
    visibility: Literal["public", "participants"]
    recipient_ids: list[str] = Field(default_factory=list)
    conversation_id: str | None = None
    reply_to_message_id: str | None = None
    original_request_id: str | None = None
    replaces_message_id: str | None = None

    @model_validator(mode="after")
    def participant_scope(self):
        if self.kind == "chat" and self.visibility != "participants":
            raise ValueError("Chat is never public")
        if self.visibility == "participants" and not self.recipient_ids:
            raise ValueError("Participant messages require recipients")
        return self


class AgentResponse(Contract):
    speaker: Literal["agent"] = "agent"
    kind: str
    text: str
    sources: list[dict] = Field(default_factory=list)


class AgentTurnResult(Contract):
    schema_version: Literal["1"] = "1"
    turn_id: str
    outcome: Literal[
        "processing",
        "information",
        "clarification",
        "human_request",
        "published",
        "private_chat",
        "human_chat",
        "ignored",
        "deferred",
        "unknown",
    ]
    reason: str | None = None
    message_id: str | None = None
    conversation_id: str | None = None
    chat_id: str | None = None
    response: AgentResponse | None = None
    target_place: dict | None = None
    server_time: AwareDatetime

    @model_validator(mode="after")
    def result_references(self):
        if self.outcome in ("published", "human_request") and not self.message_id:
            raise ValueError("Published results require a real message reference")
        if self.outcome in ("information", "clarification") and self.response is None:
            raise ValueError("Information and clarification require an agent response")
        return self


def turn_result(legacy):
    status, action = legacy.get("status"), legacy.get("action")
    outcome = {
        "deciding": "processing",
        "clarifying": "clarification",
        "ignored": "ignored",
        "deferred": "deferred",
        "chatting": "human_chat",
    }.get(status, "unknown")
    if status == "published":
        outcome = "human_request" if action == "question" else "published"
    elif status == "responded":
        outcome = "private_chat" if action in ("private_chat", "agent_chat") else "information"
    return AgentTurnResult(
        turn_id=legacy["turn_id"],
        outcome=outcome,
        reason=legacy.get("reason"),
        message_id=legacy.get("post_id"),
        conversation_id=legacy.get("root_id"),
        chat_id=legacy.get("chat_id"),
        response=legacy.get("agent_response"),
        target_place=legacy.get("target_place"),
        server_time=legacy["server_time"],
    ).model_dump(mode="json")
