from svea_whisper.data.subs import merge_cues, parse_vtt

VTT = """WEBVTT
Kind: captions
Language: sv

00:00:01.000 --> 00:00:03.500
Tja bror, <b>läget</b>?

00:00:03.500 --> 00:00:04.200
Tja bror, läget?

00:00:04.500 --> 00:00:06.000
Det var helt sjukt asså

00:01:00.000 --> 00:01:02.000
Ny mening långt senare
"""


def test_parse_vtt_strips_tags_and_dedupes_karaoke():
    cues = parse_vtt(VTT)
    assert [c.text for c in cues] == [
        "Tja bror, läget?",
        "Det var helt sjukt asså",
        "Ny mening långt senare",
    ]
    # the karaoke repeat extended the first cue's end time
    assert cues[0].end == 4.2


def test_parse_vtt_hour_timestamps():
    cues = parse_vtt("WEBVTT\n\n01:02:03.000 --> 01:02:05.000\nhej\n")
    assert cues[0].start == 3723.0


def test_merge_cues_joins_adjacent_and_respects_max():
    cues = parse_vtt(VTT)
    merged = merge_cues(cues, max_s=28.0, max_gap_s=1.0)
    # first two cues merge (0.3s gap); the far-away cue stays separate
    assert len(merged) == 2
    assert merged[0].text == "Tja bror, läget? Det var helt sjukt asså"
    assert merged[1].text == "Ny mening långt senare"


def test_merge_cues_drops_too_short():
    cues = parse_vtt("WEBVTT\n\n00:00:01.000 --> 00:00:01.400\nkort\n")
    assert merge_cues(cues) == []
