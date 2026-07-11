from svea_whisper.data.manifest import Utterance, filter_manifest, read_manifest, write_manifest
from svea_whisper.data.normalize_sv import normalize_eval
from svea_whisper.data.slang_sv import LENIENT_EQUIVALENTS, apply_lenient


def test_no_duplicate_or_self_mappings():
    assert len(LENIENT_EQUIVALENTS) == len(set(LENIENT_EQUIVALENTS))
    for variant, canonical in LENIENT_EQUIVALENTS.items():
        assert variant != canonical


def test_slang_and_standard_score_equal():
    assert normalize_eval("Asså det var nåt sjukt", lenient=True) == \
        normalize_eval("alltså det var något sjukt", lenient=True)


def test_de_dem_dom_collapse():
    assert normalize_eval("de kommer sen", lenient=True) == \
        normalize_eval("dom kommer sedan", lenient=True)


def test_strict_mode_keeps_slang_distinct():
    assert normalize_eval("asså nåt") != normalize_eval("alltså något")


def test_multiword_expansion():
    assert apply_lenient("iaf bra") == "i alla fall bra"


def test_real_slang_words_untouched():
    # actual slang vocabulary must never be "corrected" — it's real content
    for word in ("bror", "len", "gitta", "para", "aina", "chilla"):
        assert apply_lenient(word) == word


def test_filter_manifest(tmp_path):
    src = tmp_path / "all.jsonl"
    write_manifest(
        [
            Utterance("/a.wav", "tja", 1.0, "youtube", dialect="fororts",
                      pseudo_labeled=True, label_confidence=0.9),
            Utterance("/b.wav", "hej", 1.0, "youtube", dialect="fororts",
                      pseudo_labeled=True, label_confidence=0.5),
            Utterance("/c.wav", "hejsan", 1.0, "nst", dialect="sveamal"),
        ],
        src,
    )
    out = tmp_path / "slang.jsonl"
    n = filter_manifest(src, out, dialects=["fororts"], min_confidence=0.7)
    kept = list(read_manifest(out))
    assert n == 1 and kept[0].audio_path == "/a.wav"