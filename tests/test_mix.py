import pytest

from svea_whisper.data.manifest import Utterance, write_manifest
from svea_whisper.data.mix import mix_manifests


def _mk(tmp_path, name, source, n, dur=60.0, **kw):
    path = tmp_path / f"{name}.jsonl"
    write_manifest(
        [Utterance(f"/{name}{i}.wav", f"text {i}", dur, source, **kw) for i in range(n)],
        path,
    )
    return path


def test_mix_respects_weights(tmp_path):
    # 10h of each source available; weights should shape the output
    inputs = [
        _mk(tmp_path, "rixvox", "rixvox", 600),
        _mk(tmp_path, "nst", "nst", 600),
        _mk(tmp_path, "cv", "common_voice", 600),
        _mk(tmp_path, "fleurs", "fleurs", 600),
        _mk(tmp_path, "yt", "youtube", 600, pseudo_labeled=True, label_confidence=0.9),
    ]
    stats = mix_manifests(inputs, tmp_path / "mix.jsonl", seed=1)
    h = stats["hours_by_source"]
    # youtube has the largest weight (0.35): it must be the biggest slice
    assert h["youtube"] == max(h.values())
    # ratios approximately follow weights (youtube/fleurs = 0.35/0.05 = 7)
    assert h["youtube"] / h["fleurs"] == pytest.approx(7, rel=0.15)


def test_mix_prefers_human_labels_within_youtube(tmp_path):
    subs = _mk(tmp_path, "subs", "youtube_subs", 30)  # human
    pseudo = _mk(tmp_path, "yt", "youtube", 30, pseudo_labeled=True, label_confidence=0.8)
    rix = _mk(tmp_path, "rixvox", "rixvox", 600)
    out = tmp_path / "mix.jsonl"
    mix_manifests([subs, pseudo, rix], out, seed=1)
    from svea_whisper.data.manifest import read_manifest

    yt_rows = [u for u in read_manifest(out) if u.source.startswith("youtube")]
    human = [u for u in yt_rows if not u.pseudo_labeled]
    # every human-labeled utterance made the cut before any pseudo one did
    assert len(human) == 30


def test_mix_drops_unweighted_sources(tmp_path):
    inputs = [
        _mk(tmp_path, "rixvox", "rixvox", 60),
        _mk(tmp_path, "mystery", "mystery_corpus", 60),
    ]
    stats = mix_manifests(inputs, tmp_path / "mix.jsonl", seed=1)
    assert "mystery_corpus" not in stats["hours_by_source"]


def test_mix_errors_on_no_match(tmp_path):
    inputs = [_mk(tmp_path, "mystery", "mystery_corpus", 5)]
    with pytest.raises(ValueError):
        mix_manifests(inputs, tmp_path / "mix.jsonl")
