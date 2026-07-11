"""Swedish text normalization.

Two modes:
- ``normalize_train``: light cleanup for training targets. Keeps casing,
  punctuation and — crucially — slang spellings intact, so the model learns to
  *write* colloquial Swedish, not sanitize it away.
- ``normalize_eval``: aggressive normalization applied to both reference and
  hypothesis before WER, so scoring doesn't punish casing/punctuation/number
  formatting differences.
"""

from __future__ import annotations

import re
import unicodedata

# Spoken-form corrections that appear in dataset transcripts as digits.
_NUMBERS = {
    "0": "noll", "1": "ett", "2": "två", "3": "tre", "4": "fyra",
    "5": "fem", "6": "sex", "7": "sju", "8": "åtta", "9": "nio",
    "10": "tio", "11": "elva", "12": "tolv", "13": "tretton", "14": "fjorton",
    "15": "femton", "16": "sexton", "17": "sjutton", "18": "arton", "19": "nitton",
    "20": "tjugo", "30": "trettio", "40": "fyrtio", "50": "femtio",
    "60": "sextio", "70": "sjuttio", "80": "åttio", "90": "nittio",
    "100": "hundra", "1000": "tusen",
}

# Common abbreviations expanded to spoken form for eval comparison.
_ABBREVIATIONS = {
    "t.ex.": "till exempel",
    "t ex": "till exempel",
    "bl.a.": "bland annat",
    "d.v.s.": "det vill säga",
    "dvs": "det vill säga",
    "osv": "och så vidare",
    "o.s.v.": "och så vidare",
    "m.m.": "med mera",
    "ca": "cirka",
    "ca.": "cirka",
    "kr": "kronor",
    "st": "stycken",
    "nr": "nummer",
}

_WHITESPACE_RE = re.compile(r"\s+")
# Keep Swedish letters; strip everything that is not word-ish for eval.
_EVAL_STRIP_RE = re.compile(r"[^a-zåäöéü0-9\s]")


def normalize_train(text: str) -> str:
    """Light cleanup: unicode NFC, collapse whitespace, strip control chars.

    Deliberately preserves slang ("asså", "bror", "len", "gitta"), casing and
    punctuation — those are part of what SveaWhisper must learn to produce.
    """
    text = unicodedata.normalize("NFC", text)
    text = "".join(ch for ch in text if unicodedata.category(ch)[0] != "C")
    # Common transcript artifacts
    text = text.replace(" ", " ")
    text = re.sub(r"\[(?:musik|skratt|applåder|ohörbart)\]", "", text, flags=re.IGNORECASE)
    return _WHITESPACE_RE.sub(" ", text).strip()


def _number_to_words(match: re.Match) -> str:
    token = match.group(0)
    if token in _NUMBERS:
        return _NUMBERS[token]
    # 21-99 composed forms: "tjugoett" etc.
    if len(token) == 2 and token[1] != "0":
        tens, ones = _NUMBERS.get(token[0] + "0"), _NUMBERS.get(token[1])
        if tens and ones:
            return tens + ones
    return token  # leave larger numbers as-is; they count as one token either way


def normalize_eval(text: str) -> str:
    """Aggressive normalization for WER scoring (both ref and hyp)."""
    text = normalize_train(text).lower()
    for abbr, expansion in _ABBREVIATIONS.items():
        text = re.sub(rf"(?<![\w.]){re.escape(abbr)}(?![\w.])", expansion, text)
    text = _EVAL_STRIP_RE.sub(" ", text)
    text = re.sub(r"\b\d{1,2}\b|\b(?:100|1000)\b", _number_to_words, text)
    return _WHITESPACE_RE.sub(" ", text).strip()
