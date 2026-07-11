# Mixture-of-experts architecture (dialect routing)

## What it is

`svea_whisper/moe/` implements a router + experts ensemble on top of the
fine-tuned models:

1. **Language gate** — Whisper-native language ID on the router's tiny model
   (~one encoder pass, tens of ms on CPU). Non-Swedish audio skips dialect
   routing entirely.
2. **Dialect router** (`router.py`) — a logistic head on mean-pooled
   kb-whisper-tiny encoder states, trained on our dialect-tagged manifests
   (`DialectRouter.train_head`). Returns a ranked list of dialect experts.
3. **Dual decode** (`ensemble.py`) — the generalist **and** the top expert
   transcribe in parallel ("main + expert at the same time").
4. **Arbitration** (`arbitration.py`) — length-normalized average token
   log-probability picks the winner.
5. **Re-route** — if even the winner looks unreliable, decode the router's
   next-ranked expert and re-arbitrate ("rearrange and find the optimal one").

All policies are flags on `MoETranscriber` (`always_dual`, `expert_bias`,
`reroute_threshold`, `max_reroutes`), so pure routing, dual decode, and
adaptive re-routing can be A/B-tested on the same eval set.

## Does it lower WER? — simulation results

We can't measure real WER until experts are trained (needs GPU + data), but
the *policy* question is testable now: `scripts/moe_experiment.py` Monte-Carlo
simulates utterances with per-dialect WER structure, imperfect routing and
noisy confidence, and drives the **exact same arbitration code** the real
ensemble uses. Assumptions: experts are 25% (relative) better on their own
dialect, 35% worse off-dialect; 30 000 utterances per cell.

Mean WER (decodes per utterance in parens):

| router acc | conf noise | generalist only | route hard | dual arbitrate | dual+reroute | oracle router | oracle arbitration |
|---|---|---|---|---|---|---|---|
| 0.70 | low    | 0.0900 (1) | 0.0834 (1) | **0.0759** (2) | **0.0758** (2) | 0.0674 | 0.0739 |
| 0.70 | medium | 0.0900 (1) | 0.0834 (1) | **0.0799** (2) | **0.0798** (2) | 0.0674 | 0.0739 |
| 0.70 | high   | 0.0900 (1) | 0.0834 (1) | **0.0828** (2) | **0.0827** (2) | 0.0675 | 0.0740 |
| 0.85 | low    | 0.0901 (1) | 0.0758 (1) | **0.0730** (2) | **0.0729** (2) | 0.0676 | 0.0710 |
| 0.85 | medium | 0.0902 (1) | **0.0758** (1) | 0.0768 (2) | 0.0768 (2) | 0.0677 | 0.0710 |
| 0.85 | high   | 0.0900 (1) | **0.0757** (1) | 0.0793 (2) | 0.0793 (2) | 0.0676 | 0.0709 |
| 0.95 | low    | 0.0900 (1) | **0.0702** (1) | 0.0706 (2) | 0.0706 (2) | 0.0675 | 0.0686 |
| 0.95 | medium | 0.0900 (1) | **0.0702** (1) | 0.0744 (2) | 0.0744 (2) | 0.0675 | 0.0686 |
| 0.95 | high   | 0.0898 (1) | **0.0701** (1) | 0.0767 (2) | 0.0768 (2) | 0.0674 | 0.0684 |

### Findings

1. **Every MoE variant beats the single generalist** — 7–22% relative WER
   reduction under these assumptions. The concept is sound.
2. **Dual decode is a hedge against a weak router.** With a mediocre router
   (70%), running generalist+expert in parallel and arbitrating wins clearly.
   With a strong router (95%), hard routing alone is better *and* half the
   compute — arbitration then mostly adds noise.
3. **Confidence calibration is the whole game for arbitration.** The
   crossover between "dual wins" and "hard routing wins" is driven almost
   entirely by confidence noise. Average logprob is a decent but imperfect
   proxy; if we invest here (e.g. temperature-scaled calibration on held-out
   data), oracle-arbitration numbers show the headroom (0.0710 vs 0.0758 at
   85% router).
4. **Re-routing ("rearrange and retry") adds little on average** — it only
   fires in the low-confidence tail. It's still worth keeping (cheap, helps
   worst-case utterances, never hurts mean WER in any cell), but it is not
   where the gains live.
5. **The router is the highest-leverage component**: oracle routing (0.0675)
   beats everything else in every cell. Improving router accuracy pays more
   than any arbitration cleverness.

### Recommendation

Ship a **strong single generalist first** (it's the baseline everything must
beat), then add experts only for dialects where per-dialect eval shows a real
gap (likely `fororts`, `ostsvenska`, `dalmal`). Start in dual-decode mode
while the router is unproven; once measured router accuracy exceeds ~90%,
switch to confident-routing mode (`always_dual=False`) to halve inference
cost. Re-run `scripts/moe_experiment.py` with measured values for router
accuracy, expert gain, and confidence noise to keep the policy choice honest.

### Validation status

- `tests/test_moe.py` — 12 unit tests over arbitration/routing policy and
  simulation sanity (oracle bounds, bad-router regimes).
- `scripts/moe_smoke_test.py` — the real ensemble (two fixture Whisper
  models + router) runs language gate → route → dual decode → arbitrate
  end-to-end offline. PASSES on CPU.
- Real-WER A/B on trained experts: blocked on GPU training run.
