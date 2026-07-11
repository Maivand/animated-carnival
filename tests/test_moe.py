from svea_whisper.moe.arbitration import (
    Candidate,
    arbitrate,
    next_expert,
    plan_decodes,
    should_reroute,
)
from svea_whisper.moe.simulate import SimConfig, run


def cand(key, conf):
    return Candidate(text=f"<{key}>", avg_logprob=conf, model_key=key)


class TestArbitration:
    def test_picks_highest_confidence(self):
        best = arbitrate([cand("generalist", -0.4), cand("fororts", -0.2)])
        assert best.model_key == "fororts"

    def test_expert_bias_breaks_ties_toward_expert(self):
        cands = [cand("generalist", -0.30), cand("sydsvenska", -0.33)]
        assert arbitrate(cands).model_key == "generalist"
        assert arbitrate(cands, expert_bias=0.05).model_key == "sydsvenska"

    def test_should_reroute_only_below_threshold(self):
        assert should_reroute(cand("x", -0.9), reroute_threshold=-0.6)
        assert not should_reroute(cand("x", -0.3), reroute_threshold=-0.6)

    def test_plan_always_dual_includes_generalist_and_expert(self):
        plan = plan_decodes([("fororts", 0.95), ("sveamal", 0.05)],
                            {"fororts"}, 0.95, always_dual=True)
        assert plan == ["generalist", "fororts"]

    def test_plan_confident_pure_routing_skips_generalist(self):
        plan = plan_decodes([("fororts", 0.95), ("sveamal", 0.05)],
                            {"fororts"}, 0.95, always_dual=False)
        assert plan == ["fororts"]

    def test_plan_falls_back_to_generalist_without_expert(self):
        plan = plan_decodes([("gotlandska", 0.9)], set(), 0.9, always_dual=False)
        assert plan == ["generalist"]

    def test_next_expert_skips_tried(self):
        ranked = [("fororts", 0.5), ("sydsvenska", 0.3), ("dalmal", 0.2)]
        experts = {"fororts", "dalmal"}
        assert next_expert(ranked, experts, {"fororts"}) == "dalmal"
        assert next_expert(ranked, experts, {"fororts", "dalmal"}) is None


class TestSimulation:
    def test_oracle_router_beats_generalist(self):
        results = run(SimConfig(n_utterances=4000))
        assert results["oracle_router"]["wer"] < results["generalist_only"]["wer"]

    def test_dual_arbitration_beats_hard_routing_with_imperfect_router(self):
        results = run(SimConfig(n_utterances=4000, router_acc=0.75))
        assert results["dual_arbitrate"]["wer"] < results["route_hard"]["wer"]

    def test_hard_routing_hurts_when_router_is_bad(self):
        results = run(SimConfig(n_utterances=4000, router_acc=0.5))
        assert results["route_hard"]["wer"] > results["generalist_only"]["wer"]

    def test_oracle_arbitration_bounds_dual(self):
        results = run(SimConfig(n_utterances=4000))
        assert results["oracle_arbitration"]["wer"] <= results["dual_arbitrate"]["wer"]

    def test_reroute_never_more_decodes_than_experts(self):
        results = run(SimConfig(n_utterances=2000))
        assert results["dual_reroute"]["decodes"] >= results["dual_arbitrate"]["decodes"]
