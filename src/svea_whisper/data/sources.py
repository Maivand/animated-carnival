"""Registry of open Swedish speech datasets.

Each entry describes a Hugging Face dataset with human transcripts. The
YouTube pipeline (youtube.py + pseudo_label.py) covers what these lack:
heavy dialects and contemporary slang.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class DataSource:
    key: str
    hf_id: str
    hf_config: str | None
    audio_column: str
    text_column: str
    description: str
    approx_hours: float
    requires_auth: bool = False
    splits: tuple = ("train", "validation", "test")
    extra_kwargs: dict = field(default_factory=dict)


SOURCES: dict[str, DataSource] = {
    s.key: s
    for s in [
        DataSource(
            key="common_voice",
            hf_id="mozilla-foundation/common_voice_17_0",
            hf_config="sv-SE",
            audio_column="audio",
            text_column="sentence",
            description="Mozilla Common Voice Swedish — crowd-read sentences, "
            "diverse speakers/accents. Requires accepting terms on HF.",
            approx_hours=45,
            requires_auth=True,
        ),
        DataSource(
            key="fleurs",
            hf_id="google/fleurs",
            hf_config="sv_se",
            audio_column="audio",
            text_column="transcription",
            description="Google FLEURS Swedish — read speech, clean eval set.",
            approx_hours=12,
        ),
        DataSource(
            key="nst",
            hf_id="KBLab/nst",
            hf_config=None,
            audio_column="audio",
            text_column="text",
            description="NST Swedish ASR database (Språkbanken) — large studio "
            "read-speech corpus, regionally balanced recruitment.",
            approx_hours=300,
        ),
        DataSource(
            key="rixvox",
            hf_id="KBLab/rixvox-v2",
            hf_config=None,
            audio_column="audio",
            text_column="text",
            description="RixVox — Swedish parliament speeches aligned with "
            "protocols. Spontaneous formal speech, many regional accents.",
            approx_hours=5500,
        ),
    ]
}

# Rough sampling weights for the training mix. Rationale: rixvox/nst give bulk
# robustness, common_voice gives speaker diversity, youtube (added at manifest
# level) gives dialect + slang and is upweighted despite pseudo-labels —
# the product's primary users are young speakers, so slang-heavy data leads.
DEFAULT_MIX_WEIGHTS = {
    "rixvox": 0.25,
    "nst": 0.22,
    "common_voice": 0.13,
    "fleurs": 0.05,
    "youtube": 0.35,
}
