"""Pipeline configuration (pydantic + YAML)."""

from __future__ import annotations

from pathlib import Path
from typing import List

import yaml
from pydantic import BaseModel, Field, field_validator


class SourceConfig(BaseModel):
    name: str
    url: str
    kind: str = "linear"
    max_days: int = 7

    @field_validator("kind")
    @classmethod
    def _kind(cls, v: str) -> str:
        if v not in ("linear", "fast"):
            raise ValueError("kind must be 'linear' or 'fast'")
        return v


class RetryConfig(BaseModel):
    attempts: int = 3
    backoff_seconds: float = 2.0
    max_backoff_seconds: float = 30.0


class QualityConfig(BaseModel):
    min_sources_ok: int = 1
    max_failed_ratio: float = 0.05


class PipelineConfig(BaseModel):
    sources: List[SourceConfig]
    output_dir: Path
    raw_dir: Path
    max_days: int = 7
    retry: RetryConfig = Field(default_factory=RetryConfig)
    quality: QualityConfig = Field(default_factory=QualityConfig)

    @classmethod
    def from_yaml(cls, path: str | Path) -> "PipelineConfig":
        with open(path, encoding="utf-8") as fh:
            data = yaml.safe_load(fh)
        return cls(**data)

    @classmethod
    def from_dict(cls, data: dict) -> "PipelineConfig":
        return cls(**data)