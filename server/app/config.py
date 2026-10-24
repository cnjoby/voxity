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
    amap_key: str = ""
    location_ttl: int = Field(default=60, ge=1)
    location_accuracy_m: float = Field(default=100, gt=0)
    nearby_radius_m: float = Field(default=300, gt=0)
    allow_mock_location: bool = False
    bamboo_api_key: str = ""
    bamboo_base_url: str = "https://api.bamboonode.dev/v1"
    decision_enabled: bool = False
    decision_timeout: float = Field(default=4.5, ge=0.05, le=10)
    exchange_session_ttl: int = Field(default=3600, ge=60, le=14400)
    exchange_turn_ttl: int = Field(default=60, ge=5, le=60)
    exchange_decision_timeout: float = Field(default=20, ge=0.05, le=30)
    exchange_listen_confidence: float = Field(default=0.65, ge=0.5, le=1)
    exchange_observation_ttl: int = Field(default=300, ge=10, le=3600)

    voice_radius_m: float = Field(default=150, gt=0)
    visit_exit_grace: int = Field(default=120, ge=1)
    scene_prompt_limit: int = Field(default=1, ge=0, le=10)
    scene_prompt_interval: int = Field(default=300, ge=1)
    observation_max_ttl: int = Field(default=3600, ge=1)
    announcement_max_ttl: int = Field(default=86400, ge=1)
