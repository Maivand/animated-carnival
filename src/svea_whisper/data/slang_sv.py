"""Slang- and colloquialism-aware scoring support.

Young speakers say "asså", "oxå", "nåt", "dom" — and our models are trained to
WRITE those forms when spoken (normalize_train preserves them). But when
*scoring*, a hypothesis "alltså" against a reference "asså" is not a real
error. ``LENIENT_EQUIVALENTS`` maps colloquial variants and their standard
forms to one canonical token so lenient WER measures meaning, not spelling
convention.

Scope: spoken-Swedish reductions and de-facto standard informal spellings.
Chat-speak abbreviations (kmr, fr, lr) are deliberately excluded — they don't
occur in speech transcripts.
"""

from __future__ import annotations

# variant -> canonical scoring form. Multi-word expansions are allowed.
LENIENT_EQUIVALENTS = {
    # discourse particles / reductions
    "asså": "alltså",
    "ba": "bara",
    "va": "vad",
    "sen": "sedan",
    "sån": "sådan",
    "sånt": "sådant",
    "såna": "sådana",
    "nåt": "något",
    "nått": "något",
    "nån": "någon",
    "nånting": "någonting",
    "nånstans": "någonstans",
    "oxå": "också",
    "oxo": "också",
    "iaf": "i alla fall",
    "tex": "till exempel",
    # de/dem/dom — Swedish ASR evals conventionally collapse these
    "de": "dom",
    "dem": "dom",
    # common verb reductions
    "ska": "skall",
    "sa": "sade",
    "la": "lade",
    "mej": "mig",
    "dej": "dig",
    "sej": "sig",
    "dan": "dagen",
    "stan": "staden",
}


def apply_lenient(text: str) -> str:
    """Collapse equivalent colloquial/standard forms (input: already
    normalize_eval'd, i.e. lowercase, punctuation stripped)."""
    return " ".join(LENIENT_EQUIVALENTS.get(token, token) for token in text.split())
