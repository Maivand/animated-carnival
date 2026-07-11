from svea_whisper.data.normalize_sv import normalize_eval, normalize_train


def test_train_preserves_slang_and_case():
    text = "Asså bror, det var HELT sjukt, len!"
    assert normalize_train(text) == text


def test_train_strips_artifacts():
    assert normalize_train("hej  [musik]   där​") == "hej där"


def test_eval_case_and_punct_insensitive():
    assert normalize_eval("Hej, på dig!") == normalize_eval("hej på dig")


def test_eval_expands_abbreviations():
    assert normalize_eval("t.ex. hundra kr") == "till exempel hundra kronor"


def test_eval_numbers_to_words():
    assert normalize_eval("jag har 2 katter") == "jag har två katter"
    assert normalize_eval("han är 25") == "han är tjugofem"


def test_eval_keeps_swedish_letters():
    assert normalize_eval("Åsa äter öl på ön") == "åsa äter öl på ön"
