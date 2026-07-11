"""Pure decision logic for the mixture-of-experts transcriber.

Kept free of torch/transformers so the policy is unit-testable anywhere and
the same code drives both the real ensemble and the simulation.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class Candidate:
    """One decode attempt by one model."""

    text: str
    avg_logprob: float  # length-normalized token log-probability
    model_key: str  # "generalist" or expert dialect key


def arbitrate(candidates: list, expert_bias: float = 0.0) -> Candidate:
    """Pick the winning transcript among parallel decodes.

    Comparison is on length-normalized avg token logprob — the standard
    Whisper confidence proxy. ``expert_bias`` (in logprob units) breaks
    near-ties toward experts, useful when the router is known to be accurate.
    """
    if not candidates:
        raise ValueError("no candidates to arbitrate")

    def score(c: Candidate) -> float:
        bonus = expert_bias if c.model_key != "generalist" else 0.0
        return c.avg_logprob + bonus

    return max(candidates, key=score)


def should_reroute(best: Candidate, reroute_threshold: float = -0.6) -> bool:
    """True when even the winning decode looks unreliable and it's worth
    spending another decode on the router's next-ranked expert."""
    return best.avg_logprob < reroute_threshold


def plan_decodes(
    ranked_dialects: list,
    experts: set,
    router_top_prob: float,
    always_dual: bool = True,
    confident_route_prob: float = 0.9,
) -> list:
    """Which models to run in the first decode wave.

    Returns model keys. The generalist is always included unless the router
    is extremely confident AND ``always_dual`` is off (pure-routing mode).
    The first ranked dialect that actually has an expert is included.
    """
    plan = []
    expert = next((d for d, _ in ranked_dialects if d in experts), None)
    if expert is not None:
        plan.append(expert)
    if always_dual or router_top_prob < confident_route_prob or expert is None:
        plan.insert(0, "generalist")
    return plan


def next_expert(ranked_dialects: list, experts: set, tried: set) -> str | None:
    """Router's next-best expert that hasn't been decoded yet."""
    for dialect, _ in ranked_dialects:
        if dialect in experts and dialect not in tried:
            return dialect
    return None
