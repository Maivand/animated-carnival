from pathlib import Path

import yaml

from svea_whisper.data.sources import DEFAULT_MIX_WEIGHTS, SOURCES
from svea_whisper.data.youtube import load_seeds
from svea_whisper.eval.dialects import DIALECT_REGIONS, guess_dialect_from_text
from svea_whisper.train.finetune import TrainConfig

REPO = Path(__file__).resolve().parents[1]


def test_train_configs_parse():
    for cfg_path in sorted((REPO / "configs").glob("train_*.yaml")):
        cfg = TrainConfig.from_yaml(cfg_path)
        assert cfg.base_model.startswith("KBLab/kb-whisper")
        assert 0 <= cfg.min_label_confidence <= 1
        assert cfg.train_manifests


def test_youtube_seeds_parse_and_use_known_dialects():
    seeds = load_seeds(REPO / "configs" / "youtube_seeds.yaml")
    assert len(seeds) >= 10
    for seed in seeds:
        assert seed.dialect in DIALECT_REGIONS, seed.dialect
        assert seed.max_videos > 0


def test_data_yaml_sources_exist():
    with (REPO / "configs" / "data.yaml").open() as f:
        data_cfg = yaml.safe_load(f)
    for key in data_cfg["sources"]:
        assert key in SOURCES, key


def test_mix_weights_sum_to_one():
    assert abs(sum(DEFAULT_MIX_WEIGHTS.values()) - 1.0) < 1e-9


def test_dialect_guess():
    assert guess_dialect_from_text("Intervju i Malmö centrum") == "sydsvenska"
    assert guess_dialect_from_text("Hänger i Rinkeby med grabbarna") == "fororts"
    assert guess_dialect_from_text("random video") == "unknown"
