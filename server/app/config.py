from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SONAR_", env_file=".env", extra="ignore")
    data_dir: Path = Path("data")
    alice_token: str = ""
    bob_token: str = ""
    alice_device_id: str = "device_alice_demo"
    bob_device_id: str = "device_bob_demo"
    alice_agent_id: str = ""
    bob_agent_id: str = ""
    mcp_enabled: bool = False
    mcp_secret: str = ""
    question_ttl: int = Field(default=120, ge=1)
    invitation_ttl: int = Field(default=30, ge=1)
    presence_ttl: int = Field(default=600, ge=1)
    audio_ttl: int = Field(default=86400, ge=1)
