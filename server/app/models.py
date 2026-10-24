from typing import Annotated, Literal
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, StringConstraints

Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)]
Place = Literal["zhonghuan_jinke", "demo_approach"]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Presence(StrictModel):
    place_id: Place
    direction: Literal["demo_exit"]
    source: Literal["simulated"]


class Context(StrictModel):
    expected_revision: int = Field(ge=1)
    presence: Presence | None = None
    assist_enabled: bool = Field(strict=True)
    mode: Literal["listen", "quiet"]
    focus: Text


class LocationFix(StrictModel):
    latitude: float = Field(ge=-90, le=90, allow_inf_nan=False)
    longitude: float = Field(ge=-180, le=180, allow_inf_nan=False)
    coordinate_system: Literal["wgs84", "gcj02"] = "wgs84"
    accuracy_m: float = Field(gt=0, le=100000, allow_inf_nan=False)
    sampled_at: AwareDatetime
    source: Literal["android", "android_mock"] = "android"


class LocationUpdate(StrictModel):
    expected_revision: int = Field(ge=1)
    location: LocationFix | None


class QuestionDraft(StrictModel):
    channel_id: str | None = Field(default=None, min_length=1, max_length=100)
    recorded_at: AwareDatetime | None = None
    client_request_id: UUID
    kind: Literal["question"]
    transcript: Text
    target_place_id: str = Field(min_length=1, max_length=100)
    direction: Literal["demo_exit", "unknown"] = "unknown"
    recorded_location: LocationFix | None = None


class AnswerDraft(StrictModel):
    channel_id: str | None = Field(default=None, min_length=1, max_length=100)
    recorded_at: AwareDatetime | None = None
    client_request_id: UUID
    kind: Literal["answer"]
    transcript: Text
    reply_to: str = Field(min_length=1, max_length=100)
    invitation_id: str = Field(min_length=1, max_length=100)
    recorded_location: LocationFix | None = None


class Ack(StrictModel):
    event: Literal["alerted", "declined", "played", "skipped", "failed", "received", "seen"]
    request_id: str = Field(min_length=1, max_length=100)


class Empty(StrictModel):
    pass


class Listening(StrictModel):
    expected_revision: int = Field(ge=1)
    scene_enabled: bool = Field(strict=True)
    channel_enabled: bool = Field(strict=True)
    replies_enabled: bool = Field(strict=True)
    interests: list[Text] = Field(default_factory=list, max_length=20)
    subscribed_places: list[Text] = Field(default_factory=list, max_length=20)
    muted_topics: list[Text] = Field(default_factory=list, max_length=20)
    muted_channels: list[Text] = Field(default_factory=list, max_length=20)


class VoiceDraft(StrictModel):
    client_request_id: UUID
    kind: Literal["scene", "knowledge", "announcement"]
    transcript: Text
    target_place_id: str = Field(min_length=1, max_length=100)
    purpose: Literal["reflection", "experience", "explanation", "observation", "notice"]
    visibility: Literal["public"] = "public"
    recorded_at: AwareDatetime | None = None
    channel_id: str | None = Field(default=None, min_length=1, max_length=100)
    scope: Literal["poi", "entrance", "floor", "area"] = "poi"
    scope_label: Text | None = None
    valid_from: AwareDatetime | None = None
    valid_until: AwareDatetime | None = None
    applicability: Literal["unconditional", "unknown"] = "unconditional"
    source_note: Text | None = None
    topics: list[Text] = Field(default_factory=list, max_length=10)
    replaces: str | None = Field(default=None, min_length=1, max_length=100)
    recorded_location: LocationFix | None = None


class ReplyDraft(StrictModel):
    channel_id: str | None = Field(default=None, min_length=1, max_length=100)
    client_request_id: UUID
    kind: Literal["reply"]
    transcript: Text
    reply_to: str = Field(min_length=1, max_length=100)
    root_question_id: str = Field(min_length=1, max_length=100)
    recipient_id: str = Field(min_length=1, max_length=100)
    visibility: Literal["public"] = "public"
    recorded_at: AwareDatetime | None = None
    recorded_location: LocationFix | None = None


Draft = Annotated[
    QuestionDraft | AnswerDraft | VoiceDraft | ReplyDraft, Field(discriminator="kind")
]


class VoiceQueue(StrictModel):
    client_request_id: UUID | None = None
