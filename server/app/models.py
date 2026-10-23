from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

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
    presence: Presence
    assist_enabled: bool = Field(strict=True)
    mode: Literal["listen", "quiet"]
    focus: Text


class QuestionDraft(StrictModel):
    client_request_id: UUID
    kind: Literal["question"]
    transcript: Text
    target_place_id: Literal["zhonghuan_jinke"]
    direction: Literal["demo_exit"]


class AnswerDraft(StrictModel):
    client_request_id: UUID
    kind: Literal["answer"]
    transcript: Text
    reply_to: str = Field(min_length=1, max_length=100)
    invitation_id: str = Field(min_length=1, max_length=100)


Draft = Annotated[QuestionDraft | AnswerDraft, Field(discriminator="kind")]


class Ack(StrictModel):
    event: Literal["alerted", "declined", "played", "skipped", "failed"]
    request_id: str = Field(min_length=1, max_length=100)


class Empty(StrictModel):
    pass
