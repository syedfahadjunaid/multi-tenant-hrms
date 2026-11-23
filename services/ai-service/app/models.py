"""Request/response schemas. JSON uses camelCase to match the Java API."""

from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

Role = Literal["HR_ADMIN", "MANAGER", "EMPLOYEE"]
ALL_ROLES: tuple[Role, ...] = ("HR_ADMIN", "MANAGER", "EMPLOYEE")


class CamelModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        validate_by_name=True,
        validate_by_alias=True,
        extra="forbid",  # unknown fields are an error, e.g. a client trying to send tenantId
    )


class IngestRequest(CamelModel):
    title: str = Field(min_length=1, max_length=200)
    content: str = Field(min_length=1, max_length=200_000)
    allowed_roles: list[Role] = Field(default_factory=lambda: list(ALL_ROLES), min_length=1)


class IngestResponse(CamelModel):
    document_id: UUID
    chunks: int


class AskRequest(CamelModel):
    question: str = Field(min_length=1, max_length=2000)


class Source(CamelModel):
    document_id: UUID
    title: str
    score: float


class AskResponse(CamelModel):
    answer: str
    sources: list[Source]
    model: str
