# RFC 001 — Federated Policy Improvement

| | |
|---|---|
| **Status** | Draft — design-stage, not implemented |
| **Author** | @intelliswarm-ai maintainers |
| **Created** | 2026-04-21 |
| **Targets** | SwarmAI 1.2.x (earliest) |
| **Supersedes** | — |
| **Discussion** | (open GitHub issue to be linked) |

## 1. Summary

SwarmAI already ships a local RL-based policy engine (`swarmai-core/src/main/java/ai/intelliswarm/swarmai/rl/`) that learns per-deployment decisions inside the self-improving process. This RFC proposes extending it to **federated policy improvement**: deployments periodically export anonymized policy updates (not data, not prompts, not outputs) to an aggregator, which returns a merged policy that every deployment can optionally adopt.

The bet: orchestration decisions (*when to stop iterating, whether to generate a skill, how to weight skill selection*) generalize across deployments even though the underlying tasks do not. If that bet holds, deployments get better policies without any deployment ever seeing another deployment's data.

This document is deliberately posted at design stage — before code — because the privacy guarantees and aggregation topology are the hardest parts and we would rather argue them in public than ship the wrong shape.

## 2. Motivation

Three observations drove this:

1. **The local loop already works.** `LearningPolicy` (core/rl/LearningPolicy.java) composes LinUCB + Thompson Sampling + a Bayesian weight optimizer over an `ExperienceBuffer`. It converges locally in hundreds to low-thousands of decisions. Every deployment reinvents that curve from scratch.
2. **Decisions are more portable than data.** The *state* our bandits see is shape-stable across tenants — e.g., `(clarity, novelty, coverage, gap_count, cost_so_far, iteration_index, …)` — while the prompts, tools, and outputs around those states are not. Policy parameters over those features should transfer far better than few-shot examples or prompt templates.
3. **Privacy-preserving aggregation is a solved research area.** Federated Averaging (McMahan 2017), secure aggregation (Bonawitz 2017), and differential privacy for bandits (Tossou 2016, Shariff 2018) give us off-the-shelf primitives.

The non-goal here is "collect customer data to make our model better." The *only* thing that crosses the network is a numerical parameter delta.

## 3. Current state (shipped in 1.0.8)

| Component | File | Purpose |
|---|---|---|
| `PolicyEngine` interface | `rl/PolicyEngine.java` | Decision API |
| `HeuristicPolicy` | `rl/HeuristicPolicy.java` | Rules-only default |
| `LearningPolicy` | `rl/LearningPolicy.java` | Bandit-based learned policy |
| `LinUCBBandit` | `rl/bandit/LinUCBBandit.java` | Contextual bandit over 8-dim state |
| `NeuralLinUCBBandit` | `rl/bandit/NeuralLinUCBBandit.java` | Neural extension |
| `ThompsonSampling` | `rl/bandit/ThompsonSampling.java` | Convergence CONTINUE/STOP |
| `BayesianWeightOptimizer` | `rl/bandit/BayesianWeightOptimizer.java` | Selection-weight posterior |
| `ExperienceBuffer` | `rl/ExperienceBuffer.java` | Local (Decision, Outcome) log |
| `RewardTracker` | `rl/RewardTracker.java` | Reward shaping |

All local, all per-JVM. Nothing leaves the process.

## 4. Proposal

### 4.1 What crosses the wire

A *policy update* is a tuple of numeric arrays:

```
PolicyUpdate {
  deployment_id:        uuid           // opaque, rotated per round
  round:                int
  skill_gen_theta:      float[8][4]    // LinUCB weights per action
  skill_gen_a_inv:      float[8][8]    // LinUCB precision matrix
  convergence_alpha:    float[2]       // Thompson α per action
  convergence_beta:     float[2]       // Thompson β per action
  selection_posterior:  float[3][2]    // (mean, var) per dim
  num_local_decisions:  int            // weighting signal
  schema_version:       int
}
```

**Nothing prompt-shaped, tool-shaped, or output-shaped is in that payload.** An adversary with the payload cannot reconstruct a single original task. (That claim is testable; §7 lists the adversarial analysis we owe.)

### 4.2 Aggregation

Federated Averaging over the numeric arrays, weighted by `num_local_decisions`. Aggregator is a stateless service that:

1. Collects `PolicyUpdate`s from opted-in deployments across a round window (default: 24h).
2. Drops outliers (Byzantine-robust aggregation — trimmed mean over per-dim values).
3. Emits a `FederatedPolicy` snapshot with a signature.
4. Each deployment pulls the snapshot on its next heartbeat and *optionally* merges it into the local policy (EWMA, configurable blend factor default 0.3).

Deployments can always run pure-local (the current behavior is the blend=0.0 case).

### 4.3 Privacy model

Three layers, stackable:

1. **Baseline (opt-in, default blend=0):** nothing crosses the wire.
2. **Opt-in local-DP:** deployments add calibrated Gaussian noise (ε, δ) to each field before upload. Default ε=4, δ=1e-5 per round. Privacy budget tracked per tenant.
3. **Opt-in secure aggregation:** updates are masked with pairwise seeds so the aggregator only sees the *sum*, not individual contributions. Based on Bonawitz 2017 over TLS.

Enterprise tier is the natural home for (3) because it requires the RBAC + tenant-isolation primitives that already exist in `swarmai-enterprise`.

### 4.4 Opt-in model

Default is **off**. Enabling federated updates requires:

```yaml
swarmai.federation.enabled: true
swarmai.federation.privacy: local-dp | secure-agg | none
swarmai.federation.aggregator-url: https://federation.intelliswarm.ai
swarmai.federation.blend-factor: 0.3
```

No implicit enablement. No "we'll switch it on in a later release." Explicit flag per deployment, per environment.

### 4.5 Reward signal

Bandit rewards are constructed locally from signals already in the process:

- **Skill generation:** +1 if generated skill is reused ≥2× in next N tasks; −1 if rejected by `SkillValidator`; 0 otherwise.
- **Convergence:** +1 if STOP on a round whose output was accepted; −1 if STOP but user re-ran; reward shaped by `RewardTracker`.
- **Selection weights:** regret vs. oracle-retrospective best of the candidate skills.

These are *already* what `LearningPolicy` optimizes locally; federation changes nothing about reward construction.

## 5. Non-goals

- Federated fine-tuning of LLM weights. Out of scope. Frameworks do orchestration; model improvement belongs to the model vendor.
- Sharing prompts, tool outputs, retrievals, or any user-generated content across deployments. Ever.
- Centralized orchestration or a control plane. The aggregator is a stateless averager; deployments remain autonomous.
- Automatic policy adoption. Every federated policy is opt-in and can be rolled back by setting `blend-factor: 0`.

## 6. Alternatives considered

| Alternative | Why not |
|---|---|
| **Ship-time policy baked into releases** (maintainer curates priors from internal benchmarks) | This is what we already do via `HeuristicPolicy` defaults. It doesn't compound across deployments — every user's learning dies at their process boundary. |
| **Opt-in telemetry of raw experience tuples** | Leaks task-shaped info. Violates the privacy frame we want to defend publicly. |
| **Gossip / P2P aggregation** | Lower central trust, much higher operational cost for users. Revisit if aggregator trust becomes a live concern. |
| **Per-customer federated deployments** (fleet of agents inside one tenant) | Interesting for later; strictly a subset of this design (aggregator scope = tenant). |

## 7. Open questions (pushback wanted)

These are the things we genuinely don't have a confident answer for:

1. **Does orchestration-policy transfer actually hold?** Our prior is yes — the state features are shape-stable — but it is an empirical bet. Pre-registered experiment: run 20 synthetic deployments on disjoint task distributions, measure regret reduction from federated blending vs. local-only. If regret reduction < 15% at round 10, we shelve this.
2. **Is DP ε=4 the right calibration?** Tighter ε degrades utility fast with our update sizes. Looser ε makes membership inference easier. We would like an adversarial review before picking a default.
3. **Byzantine robustness.** Trimmed mean handles occasional bad actors. What about a coordinated attack (20%+ malicious deployments pushing bias)? Is median-of-means worth the utility hit?
4. **Aggregation topology.** Single aggregator = single point of trust + single point of failure. Multi-aggregator cross-validation is the obvious hedge but doubles the ops surface. Needed at what scale?
5. **Staleness model.** 24h rounds feel right for slow-moving orchestration decisions. Is there a case for per-hour rounds for deployments that generate high-volume decisions?
6. **License implications.** The aggregator service will live in `swarmai-enterprise`. Is that the right split, or does the aggregator belong in Apache-licensed OSS with only *hosted* aggregation being enterprise?

## 8. Implementation sketch (not committing to dates)

Phase breakdown, purely for discussion shape:

- **Phase 0 — interfaces:** `FederatedPolicyExporter` + `FederatedPolicyImporter` behind feature flag. No network. Export/import round-trips through a local file for testing. *~1 week.*
- **Phase 1 — plaintext aggregator:** stateless Spring Boot service. Federated averaging, no DP, no secure aggregation. Self-hostable Docker image. Opt-in `blend-factor`. *~2 weeks.*
- **Phase 2 — local DP:** Gaussian mechanism on export. Privacy budget tracking in `swarmai-enterprise`. *~2 weeks.*
- **Phase 3 — secure aggregation:** Bonawitz-style pairwise masking. *~3–4 weeks, gated on Phase 1 learnings.*
- **Phase 4 — adversarial review + public report:** independent audit before default-on semantics are ever considered. Indefinite.

None of the above is on a release-commitment timeline. The sequence is a shape, not a schedule.

## 9. References

- McMahan et al., *Communication-Efficient Learning of Deep Networks from Decentralized Data* (2017).
- Bonawitz et al., *Practical Secure Aggregation for Privacy-Preserving Machine Learning* (2017).
- Tossou & Dimitrakakis, *Differentially Private Exploration in Bandits* (2016).
- Shariff & Sheffet, *Differentially Private Contextual Linear Bandits* (2018).
- Dwork & Roth, *The Algorithmic Foundations of Differential Privacy* (2014).

## 10. Changelog

- **2026-04-21** — Initial draft published for public review.
