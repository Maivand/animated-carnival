import pytest

from svea_whisper.data.manifest import Utterance, manifest_stats, read_manifest, write_manifest


def _utt(**kw):
    base = dict(audio_path="/x/a.wav", text="hej på dig", duration=2.5, source="test")
    base.update(kw)
    return Utterance(**base)


def test_roundtrip(tmp_path):
    path = tmp_path / "m.jsonl"
    utts = [
        _utt(),
        _utt(audio_path="/x/b.wav", text="tjena bror, läget?", dialect="fororts",
             pseudo_labeled=True, label_confidence=0.82),
    ]
    assert write_manifest(utts, path) == 2
    back = list(read_manifest(path))
    assert back == utts
    assert back[1].dialect == "fororts"


def test_validation_rejects_bad_rows(tmp_path):
    with pytest.raises(ValueError):
        write_manifest([_utt(duration=0)], tmp_path / "m.jsonl")
    with pytest.raises(ValueError):
        write_manifest([_utt(label_confidence=1.5)], tmp_path / "m.jsonl")


def test_stats(tmp_path):
    path = tmp_path / "m.jsonl"
    write_manifest(
        [_utt(duration=3600), _utt(duration=1800, dialect="sydsvenska", source="youtube")],
        path,
    )
    stats = manifest_stats(path)
    assert stats["utterances"] == 2
    assert stats["hours"] == 1.5
    assert stats["hours_by_dialect"]["sydsvenska"] == 0.5
    assert stats["hours_by_source"]["youtube"] == 0.5
