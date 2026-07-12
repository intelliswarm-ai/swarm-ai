# RL Algorithm Benchmarking Methodology

## 1. Purpose

This document defines a rigorous benchmarking framework for evaluating and comparing the reinforcement learning algorithms used in SwarmAI's self-improving workflow. The benchmarks serve two goals:

1. **Evaluate current algorithms** — Measure convergence speed, cumulative regret, throughput, and robustness of LinUCB, Thompson Sampling, Bayesian Weight Optimizer, and DQN under controlled synthetic environments.
2. **Guide algorithm improvement** — Identify which algorithms outperform under which conditions, find optimal hyperparameter configurations, and determine whether alternative approaches (Monte Carlo methods, UCB1, EXP3, Softmax, PPO) would be superior replacements.

Results are saved as CSV under `docs/benchmarks/results/` and can be consumed by any analysis tool.

---

## 2. Algorithms Under Test

### 2.1 Current Implementations

| ID | Algorithm | Module | Decision Domain | State Dim | Actions |
|----|-----------|--------|-----------------|-----------|---------|
| `LINUCB` | LinUCB Contextual Bandit | swarmai-core | Skill generation | 8 | 4 |
| `THOMPSON` | Thompson Sampling (Beta) | swarmai-core | Convergence | 0 (context-free) | 2 |
| `BAYESIAN_EVO` | Bayesian Weight Optimizer (mu+lambda) | swarmai-core | Selection weights | 3 continuous | N/A |
| `DQN` | Deep Q-Network (Double DQN + PER) | swarmai-enterprise | Skill gen + Convergence | 8 / 6 | 4 / 2 |
| `HEURISTIC` | Hand-crafted thresholds | swarmai-core | All | N/A | N/A |

### 2.2 Alternative Baselines for Comparison

| ID | Algorithm | Type | Why Compare |
|----|-----------|------|-------------|
| `RANDOM` | Uniform random | Baseline | Lower bound — any learning algorithm must beat this |
| `UCB1` | Upper Confidence Bound 1 | Context-free bandit | Classic non-contextual bandit — tests whether context features help |
| `MONTE_CARLO` | Monte Carlo averaging | Model-free RL | Tests whether simple sample-mean estimation is competitive |
| `MC_TREE_SEARCH` | Monte Carlo Tree Search (flat) | Planning | Tests look-ahead planning vs reactive bandits |
| `EXP3` | Exponential-weight for Exploration and Exploitation | Adversarial bandit | Tests robustness under adversarial/non-stationary rewards |
| `SOFTMAX` | Boltzmann exploration | Bandit | Tests temperature-based exploration vs UCB-based |
| `CONTEXTUAL_TS` | Contextual Thompson Sampling (linear) | Contextual bandit | Direct alternative to LinUCB with Bayesian exploration |

---

## 3. Benchmark Environments

Each environment simulates a reward function the algorithms must learn. Environments are parameterized to control difficulty.

### 3.1 Stationary Contextual Bandit (SCB)

Simulates the skill-generation decision where reward distributions are fixed.

- **State space**: 8-dimensional (matching `SkillGenerationContext`)
- **Action space**: 4 actions
- **Reward function**: `r(s, a) = s^T w_a + noise`, where `w_a` is a fixed weight vector per action
- **Noise**: Gaussian N(0, sigma), sigma in {0.0, 0.1, 0.3, 0.5}
- **Optimal policy**: `a* = argmax_a(s^T w_a)`
- **Difficulty lever**: noise level, state dimensionality, number of actions

### 3.2 Non-Stationary Bandit (NSB)

Simulates reward drift — e.g., skill effectiveness changes as the system evolves.

- **Regime changes**: Every T steps (T in {100, 250, 500}), the optimal action shifts
- **Drift type**: Abrupt (swap optimal actions) or gradual (linear interpolation over D steps)
- **Tests**: Adaptation speed after regime change, forgetting of stale knowledge

### 3.3 Adversarial Bandit (AB)

Worst-case environment where an adversary selects rewards to maximize regret.

- **Reward assignment**: At each step, the adversary gives reward 1.0 to all actions except the one the algorithm chose, and 0.0 to the chosen action
- **Stochastic adversary variant**: Adversary with probability p selects adversarial rewards, (1-p) selects from stationary distribution
- **Tests**: Robustness, worst-case regret guarantees

### 3.4 Binary Convergence (BC)

Simulates the CONTINUE/STOP decision for the self-improving loop.

- **True optimal**: CONTINUE has success rate p_continue, STOP has success rate p_stop
- **Variants**: p_continue >> p_stop (should keep going), p_continue << p_stop (should stop early), p_continue ~ p_stop (ambiguous)
- **Tests**: How quickly the algorithm identifies the correct action under varying signal strength

### 3.5 Weight Optimization Surface (WOS)

Simulates the selection weight optimization problem.

- **True optimal weights**: w* = [w1, w2, w3] summing to 1
- **Fitness function**: `f(w) = -||w - w*||^2 + noise`
- **Surface variants**: Unimodal (easy), multimodal with local optima (hard), ridge (deceptive)
- **Tests**: Convergence to global optimum, escape from local optima, population diversity

### 3.6 Realistic Swarm Simulation (RSS)

End-to-end simulation using real feature distributions sampled from the codebase.

- **State distribution**: Sample states from recorded `ExperienceBuffer` data or generate from empirical distributions matching production feature ranges
- **Reward model**: Learned from historical skill effectiveness data, or use a parameterized model calibrated to observed reward statistics
- **Tests**: Real-world performance prediction

---

## 4. Metrics

### 4.1 Primary Metrics

| Metric | Definition | Unit | Lower is Better? |
|--------|-----------|------|-------------------|
| **Cumulative Regret** | `R_T = sum_{t=1}^{T} [r*(s_t) - r(s_t, a_t)]` where `r*` is the reward of the optimal action | reward units | Yes |
| **Simple Regret** | `r* - E[r(s_T, a_T)]` at the final step (quality of the final policy) | reward units | Yes |
| **Convergence Step** | First step t where the algorithm selects the optimal action for 95% of the next 100 steps | steps | Yes |
| **Reward Accumulation** | `sum_{t=1}^{T} r(s_t, a_t)` total reward collected | reward units | No |
| **Optimal Action Rate** | Fraction of steps where the selected action matches the true optimal | ratio [0,1] | No (higher is better) |

### 4.2 Computational Metrics

| Metric | Definition | Unit |
|--------|-----------|------|
| **Decision Latency** | Wall-clock time for `selectAction()` per call | microseconds |
| **Update Latency** | Wall-clock time for `update()` per call | microseconds |
| **Memory Footprint** | Heap allocation of algorithm state | bytes |
| **Throughput** | Decisions per second under sustained load | decisions/sec |

### 4.3 Robustness Metrics

| Metric | Definition |
|--------|-----------|
| **Adaptation Delay** | Steps to recover optimal performance after a regime change (NSB) |
| **Worst-Case Regret** | Maximum single-step regret observed across all runs |
| **Variance of Regret** | Standard deviation of cumulative regret across seeds |
| **Adversarial Regret Ratio** | Regret under adversarial env / regret under stationary env |

### 4.4 Hyperparameter Sensitivity

| Metric | Definition |
|--------|-----------|
| **Sensitivity Index** | Coefficient of variation of cumulative regret across hyperparameter values |
| **Pareto Frontier** | Set of (hyperparameter, regret) pairs that are not dominated |
| **Robustness Range** | Range of hyperparameter values within 10% of optimal regret |

---

## 5. Statistical Methodology

### 5.1 Repetitions and Seeds

- **Minimum runs per configuration**: 30 (for CLT-based confidence intervals)
- **Recommended runs**: 100 (for bootstrap analysis)
- **Seed strategy**: Fixed seed array `[42, 137, 256, ..., 42+N]` for reproducibility
- **Reported values**: Mean +/- 95% confidence interval via bootstrap

### 5.2 Hypothesis Testing

To answer "Is algorithm A better than algorithm B?":

1. **Paired test**: Both algorithms run on the same seed sequence
2. **Test statistic**: Wilcoxon signed-rank test (non-parametric, no normality assumption)
3. **Significance level**: alpha = 0.05 with Bonferroni correction for multiple comparisons
4. **Effect size**: Report Cohen's d alongside p-values

### 5.3 Reporting

- **Tables**: Mean +/- CI for each (algorithm, environment, metric) triple
- **Plots**: Regret curves over time, convergence trajectories, hyperparameter heat maps
- **CSV output**: One row per (algorithm, environment, seed, timestep) for post-hoc analysis

---

## 6. Hyperparameter Sweep Protocol

### 6.1 LinUCB

| Parameter | Range | Steps | Default |
|-----------|-------|-------|---------|
| `alpha` (exploration) | [0.01, 5.0] | log-spaced 20 points | 1.0 |

### 6.2 Thompson Sampling

| Parameter | Range | Steps | Default |
|-----------|-------|-------|---------|
| `prior_alpha` | [0.5, 5.0] | 10 points | 1.0 |
| `prior_beta` | [0.5, 5.0] | 10 points | 1.0 |

### 6.3 Bayesian Weight Optimizer

| Parameter | Range | Steps | Default |
|-----------|-------|-------|---------|
| `populationSize` | [5, 50] | 10 points | 10 |
| `mutationSigma` | [0.01, 0.5] | 10 points | 0.1 |

### 6.4 DQN (Enterprise)

| Parameter | Range | Steps | Default |
|-----------|-------|-------|---------|
| `learningRate` | [0.0001, 0.01] | log-spaced 10 | 0.001 |
| `epsilonDecaySteps` | [100, 2000] | 10 points | 500 |
| `targetUpdateInterval` | [10, 200] | 10 points | 50 |
| `hiddenSize` | [16, 128] | {16, 32, 64, 128} | 64 |
| `gamma` | [0.9, 0.999] | 5 points | 0.99 |

### 6.5 Cold-Start Threshold (Cross-Cutting)

| Parameter | Range | Steps | Default |
|-----------|-------|-------|---------|
| `coldStartDecisions` | [10, 200] | 10 points | 50 |

---

## 7. Alternative Algorithm Investigation

### 7.1 Monte Carlo Methods

**Question**: Are simple Monte Carlo sample-mean estimators competitive with LinUCB and Thompson Sampling?

**Monte Carlo Averaging Bandit**:
- Maintains per-action running mean: `Q(a) = mean(rewards for action a)`
- Selection: epsilon-greedy with `a* = argmax_a Q(a)`
- No context sensitivity — tests if context features add value

**Monte Carlo Tree Search (Flat)**:
- Simulates N rollouts per action using the known reward model
- Selects action with highest average simulated return
- Tests the value of look-ahead planning

**Expected findings**: Monte Carlo methods lack context awareness. LinUCB should dominate when the state vector is informative. Monte Carlo should be competitive when features are noisy or irrelevant.

### 7.2 UCB1

- Classic `UCB(a) = Q(a) + c * sqrt(ln(t) / N(a))`
- Tests whether the LinUCB contextual features justify the computational overhead of matrix inversion

### 7.3 EXP3

- Adversarial bandit algorithm with exponential weighting
- Tests worst-case guarantees — EXP3 has `O(sqrt(T*K*ln(K)))` regret bound regardless of reward sequence
- Current algorithms have no adversarial guarantees

### 7.4 Contextual Thompson Sampling

- Linear Thompson Sampling: sample `theta ~ N(mu_a, Sigma_a)`, select `argmax_a(x^T theta_a)`
- Direct Bayesian alternative to LinUCB — compares frequentist vs Bayesian contextual exploration

### 7.5 Softmax / Boltzmann Exploration

- `P(a) = exp(Q(a)/tau) / sum_a' exp(Q(a')/tau)`
- Temperature `tau` controls exploration-exploitation
- Tests smooth probabilistic exploration vs hard UCB boundary

---

## 8. Benchmark Execution

### 8.1 Running Benchmarks

```bash
# Run all benchmarks (~20 minutes)
mvn test -pl swarmai-core \
  -Dtest="ConvergenceBenchmark,RegretAnalysisBenchmark,HyperparameterSweepBenchmark,FullBenchmarkSuite"

# Run specific suite
mvn test -pl swarmai-core -Dtest=ConvergenceBenchmark

# Run only the Monte Carlo comparison
mvn test -pl swarmai-core -Dtest="FullBenchmarkSuite#monteCarloVsCurrentAlgorithms"
```

### 8.2 Output Structure

```
docs/benchmarks/results/
  convergence/
    convergence_stationary.csv
    convergence_nonstationary.csv
  regret/
    cumulative_regret_by_algorithm.csv
    simple_regret_by_algorithm.csv
  hyperparameters/
    linucb_alpha_sweep.csv
    thompson_prior_sweep.csv
    bayesian_evo_sweep.csv
  comparison/
    algorithm_comparison_summary.csv
    statistical_tests.csv
  computational/
    latency_by_algorithm.csv
    memory_by_algorithm.csv
  summary_report.json
```

### 8.3 CSV Schema

**Regret CSV**:
```csv
algorithm,environment,seed,timestep,action,reward,optimal_reward,cumulative_regret,optimal_action_rate
LINUCB,SCB_noise_0.1,42,1,2,0.85,0.92,0.07,0.0
...
```

**Hyperparameter CSV**:
```csv
algorithm,parameter_name,parameter_value,environment,seed,cumulative_regret,convergence_step,final_optimal_rate
LINUCB,alpha,0.01,SCB_noise_0.1,42,145.3,87,0.94
...
```

---

## 9. Decision Framework

Use benchmark results to answer these configuration questions:

| Question | Benchmark Suite | Key Metric |
|----------|----------------|------------|
| Should we use LinUCB or Monte Carlo for skill generation? | Convergence + Regret on SCB | Cumulative regret, convergence step |
| Is the 8-dim feature vector helping or hurting? | LinUCB vs UCB1 on SCB | Regret difference with/without context |
| What alpha value should LinUCB use? | Hyperparameter sweep | Pareto frontier of (alpha, regret) |
| Does DQN justify its complexity over LinUCB? | Algorithm comparison on SCB | Regret + decision latency |
| Should we switch convergence detection to a different algorithm? | Binary convergence suite | Convergence step, optimal action rate |
| Is Thompson Sampling robust to non-stationary rewards? | NSB suite | Adaptation delay |
| Does EXP3 provide meaningful adversarial guarantees? | Adversarial suite | Worst-case regret |
| What cold-start threshold minimizes overall regret? | Cold-start sweep | Total regret including cold-start phase |
| Are the evolutionary weight optimizer parameters optimal? | WOS suite with sweeps | Convergence to known optimal |

---

## 10. Extensibility

Adding a new algorithm to the benchmark:

1. Implement the `BanditAlgorithm` interface (see `benchmark/BanditAlgorithm.java`)
2. Register it in `AlgorithmComparisonBenchmark.createAlgorithms()`
3. Run the full suite — results automatically include the new algorithm
4. Compare via the CSV output and statistical tests

Adding a new environment:

1. Implement the `BenchmarkEnvironment` interface
2. Add it to the environment list in each benchmark class
3. Results are keyed by environment name — analysis scripts pick it up automatically
