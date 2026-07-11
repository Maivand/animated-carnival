# Data sources

## Open datasets (human transcripts)

| Key | Dataset | ~Hours | What it adds |
|-----|---------|--------|--------------|
| `rixvox` | KBLab/rixvox-v2 (parliament) | 5500 | spontaneous formal speech, regional accents |
| `nst` | KBLab/nst (Språkbanken studio corpus) | 300 | clean read speech, regionally balanced |
| `common_voice` | Mozilla CV 17 sv-SE | 45 | speaker diversity (needs HF auth + terms) |
| `fleurs` | google/fleurs sv_se | 12 | clean eval benchmark |

KB-Whisper was already trained on 50k+ hours (largely these families), so the
open-data pass is a light refresher; the *new* signal is below.

## YouTube collection (the dialect/slang gap)

KB-Whisper underperforms on strong dialects and on förortssvenska/youth slang
because its training data skews formal (parliament, subtitled broadcast). The
YouTube pipeline targets exactly that:

1. `svea youtube` — yt-dlp over `configs/youtube_seeds.yaml`: search-based seeds
   per dialect region + slang/youth content categories. Downloads 16 kHz mono
   WAV + metadata + manual Swedish subs when available.
2. `svea segment` — Silero VAD splits long-form audio into ≤28 s utterances.
3. `svea pseudolabel` — KB-Whisper-large transcribes each segment; segments are
   kept only above an average-token-logprob threshold and non-degeneracy checks.
   Confidence is stored in the manifest; training configs re-filter with
   `min_label_confidence`.

Segments whose video had **manual Swedish subtitles** can be aligned against
the subs instead of pseudo-labels (higher quality — future work, see roadmap).

### Scaling recipe

Aim for a fine-tune mix around: 40% dialect/slang YouTube, 60% open datasets
(see `DEFAULT_MIX_WEIGHTS` in `sources.py`). For a serious run collect
500–1000 h of YouTube audio; after VAD + confidence filtering expect ~60% yield.

## Dialect tagging

Regions are defined in `eval/dialects.py` (classic six-region model +
`fororts` for urban contemporary varieties). YouTube data inherits the region
of its seed; open datasets mostly land in `unknown` unless metadata allows
`guess_dialect_from_text`. A human-verified, dialect-stratified **test set**
is the single highest-value manual investment — see roadmap.
