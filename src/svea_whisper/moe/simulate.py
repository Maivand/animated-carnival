"""Monte-Carlo simulation of MoE routing/arbitration policies.

We can't measure real WER without trained experts, but the *policy* question —
"does dual-decode + confidence arbitration beat hard routing or a single
generalist, and when?" — is a decision-theory question we can answer by
simulation. The simulation drives the exact same arbitration code the real
ensemble uses (arbitration.py).

Model of the world (all parameters overridable):
- Each utterance has a true dialect and a difficulty multiplier shared by all
  models (hard audio is hard for everyone).
- A generalist has per-dialect WER; an expert improves on its own dialect by
  ``expert_gain`` (relative) and degrades elsewhere by ``expert_off_penalty``.
- Confidence (avg logprob proxy) is an affine function of true WER plus
  Gaussian noise ``conf_noise`` — the knob for "how trustworthy is
  arbitration".
- The router ranks dialects; top-1 is correct with prob ``router_acc``; when
  wrong, the true dialect lands at rank 2 with prob ``rank2_recall``.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field

from .arbitration import Candidate, arbitrate, next_expert, plan_decodes, should_reroute

# Plausible post-fine-tune generalist WER per dialect. Absolute values matter
# less than the structure (some dialects much harder than others).
DEFAULT_GENERALIST_WER = {
    "sveamal": 0.07,
    "gotamal": 0.08,
    "sydsvenska": 0.10,
    "norrlandska": 0.10,
    "gotlandska": 0.11,
    "ostsvenska": 0.12,
    "fororts": 0.13,
    "dalmal": 0.15,
}

DEFAULT_PRIORS = {
    "sveamal": 0.40, "gotamal": 0.17, "sydsvenska": 0.13, "norrlandska": 0.10,
    "fororts": 0.10, "ostsvenska": 0.05, "gotlandska": 0.03, "dalmal": 0.02,
}


@dataclass
class SimConfig:
    router_acc: float = 0.85
    rank2_recall: float = 0.6
    expert_gain: float = 0.25  # expert is 25% (relative) better on own dialect
    expert_off_penalty: float = 0.35  # and 35% worse off-dialect
    conf_noise: float = 0.05  # std of confidence noise, logprob units
    conf_slope: float = 1.5  # conf = -(0.2 + slope * wer) + noise
    reroute_threshold: float = -0.55
    difficulty_std: float = 0.35  # lognormal-ish spread of utterance difficulty
    n_utterances: int = 20000
    seed: int = 7
    generalist_wer: dict = field(default_factory=lambda: dict(DEFAULT_GENERALIST_WER))
    priors: dict = field(default_factory=lambda: dict(DEFAULT_PRIORS))
    experts: tuple = tuple(DEFAULT_GENERALIST_WER)  # which dialects have experts

    def model_wer(self, model_key: str, dialect: str) -> float:
        base = self.generalist_wer[dialect]
        if model_key == "generalist":
            return base
        if model_key == dialect:
            return base * (1 - self.expert_gain)
        return base * (1 + self.expert_off_penalty)


class _World:
    """Samples one utterance and provides decode results for any model."""

    def __init__(self, cfg: SimConfig, rng: random.Random):
        self.cfg = cfg
        self.rng = rng
        dialects = list(cfg.priors)
        self.dialect = rng.choices(dialects, weights=[cfg.priors[d] for d in dialects])[0]
        self.difficulty = max(0.2, rng.gauss(1.0, cfg.difficulty_std))
        self._cache: dict = {}
        # Router ranking
        others = [d for d in dialects if d != self.dialect]
        rng.shuffle(others)
        if rng.random() < cfg.router_acc:
            ranking = [self.dialect] + others
            top_prob = rng.uniform(0.7, 0.99)
        else:
            wrong = others.pop()
            rest = others
            if rng.random() < cfg.rank2_recall:
                ranking = [wrong, self.dialect] + rest
            else:
                pos = rng.randrange(1, len(rest) + 2)
                ranking = [wrong] + rest
                ranking.insert(min(pos, len(ranking)), self.dialect)
            top_prob = rng.uniform(0.3, 0.8)
        self.ranked = [(d, top_prob if i == 0 else (1 - top_prob) / (len(ranking) - 1))
                       for i, d in enumerate(ranking)]

    def decode(self, model_key: str) -> tuple:
        """(Candidate, true_wer) — cached so strategies share randomness."""
        if model_key not in self._cache:
            cfg = self.cfg
            wer = min(1.0, cfg.model_wer(model_key, self.dialect) * self.difficulty
                      * max(0.5, self.rng.gauss(1.0, 0.1)))
            conf = -(0.2 + cfg.conf_slope * wer) + self.rng.gauss(0.0, cfg.conf_noise)
            self._cache[model_key] = (
                Candidate(text=f"<{model_key}>", avg_logprob=conf, model_key=model_key),
                wer,
            )
        return self._cache[model_key]


def _run_strategies(world: _World, cfg: SimConfig) -> dict:
    """Evaluate every strategy on the same sampled utterance.

    Returns {strategy: (wer, n_decodes)}.
    """
    experts = set(cfg.experts)
    results = {}

    # 1. Generalist only
    _, wer = world.decode("generalist")
    results["generalist_only"] = (wer, 1)

    # 2. Hard routing: trust the router's top-1 expert, no arbitration
    top = world.ranked[0][0]
    key = top if top in experts else "generalist"
    _, wer = world.decode(key)
    results["route_hard"] = (wer, 1)

    # 3. Dual decode + arbitration (the "main + expert at the same time" idea)
    plan = plan_decodes(world.ranked, experts, world.ranked[0][1], always_dual=True)
    cands = [world.decode(k)[0] for k in plan]
    best = arbitrate(cands)
    results["dual_arbitrate"] = (world.decode(best.model_key)[1], len(plan))

    # 4. Dual + re-route on low confidence (the "rearrange and retry" idea)
    tried = set(plan)
    n = len(plan)
    while should_reroute(best, cfg.reroute_threshold):
        fallback = next_expert(world.ranked, experts, tried)
        if fallback is None:
            break
        tried.add(fallback)
        cands.append(world.decode(fallback)[0])
        best = arbitrate(cands)
        n += 1
    results["dual_reroute"] = (world.decode(best.model_key)[1], n)

    # 5. Oracle router (upper bound of routing quality)
    key = world.dialect if world.dialect in experts else "generalist"
    results["oracle_router"] = (world.decode(key)[1], 1)

    # 6. Oracle arbitration (upper bound of dual decode: always picks truly
    #    better transcript among generalist + routed expert)
    wers = [world.decode(k)[1] for k in plan]
    results["oracle_arbitration"] = (min(wers), len(plan))

    return results


def run(cfg: SimConfig) -> dict:
    """Mean WER and mean decodes-per-utterance for every strategy."""
    rng = random.Random(cfg.seed)
    totals: dict = {}
    for _ in range(cfg.n_utterances):
        world = _World(cfg, rng)
        for strategy, (wer, n) in _run_strategies(world, cfg).items():
            acc = totals.setdefault(strategy, [0.0, 0.0])
            acc[0] += wer
            acc[1] += n
    return {
        strategy: {
            "wer": round(acc[0] / cfg.n_utterances, 4),
            "decodes": round(acc[1] / cfg.n_utterances, 2),
        }
        for strategy, acc in totals.items()
    }
