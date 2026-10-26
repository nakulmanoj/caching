#!/usr/bin/env python3
"""
collect_metrics.py
==================
Parses the experiment output from run_experiment.sh and generates:

  1. Summary CSV  : results/summary_table.csv   — one row per (strategy, ttl, regime)
  2. Figure 1     : results/figures/fig1_latency.png      — P50/P99 latency bar chart
  3. Figure 2     : results/figures/fig2_hit_ratio.png    — cache hit ratio heatmap
  4. Figure 3     : results/figures/fig3_estale_timeseries.png — E_stale vs time
  5. Figure 4     : results/figures/fig4_epct_cdf.png     — E_% CDF
  6. Figure 5     : results/figures/fig5_api_cost.png     — API query volume vs TTL
  7. Figure 6     : results/figures/fig6_pareto.png       — Pareto frontier

Usage:
    pip install pandas matplotlib seaborn numpy scipy
    python scripts/collect_metrics.py --results-dir ./results
"""

import argparse
import os
import re
import sys
import warnings
from pathlib import Path

import pandas as pd
import matplotlib
matplotlib.use("Agg")  # headless rendering
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np
import seaborn as sns
from scipy import stats

# ── Styling ──────────────────────────────────────────────────────────────────
sns.set_theme(style="whitegrid", palette="muted", font_scale=1.1)
STRATEGY_COLORS = {
    "cache-aside":  "#4C72B0",
    "write-through":"#DD8452",
    "swr":          "#55A868",
}
TTL_ORDER = ["static-5", "static-30", "static-60", "dynamic", "event"]
REGIME_ORDER = ["low", "medium", "high"]

# ── CLI ───────────────────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--results-dir", default="./results",
                   help="Directory containing staleness.csv and jmeter/ subdirectory")
    return p.parse_args()

# ── Data Loading ──────────────────────────────────────────────────────────────

def load_staleness(results_dir: Path) -> pd.DataFrame:
    dfs = []
    sigma_to_regime = {"0.05": "low", "0.30": "medium", "0.80": "high"}
    for p in results_dir.glob("staleness_*.csv"):
        if p.name == "staleness.csv":
            continue
        if p.stat().st_size > 110:
            try:
                sub_df = pd.read_csv(p, parse_dates=["timestamp"])
                m = re.search(r"staleness_[^_]+_[^_]+_(0\.\d+)\.csv", p.name)
                if m:
                    regime = sigma_to_regime.get(m.group(1))
                    if regime:
                        sub_df["volatility_regime"] = regime
                dfs.append(sub_df)
            except Exception:
                pass

    if not dfs:
        master_path = results_dir / "staleness.csv"
        if master_path.exists() and master_path.stat().st_size > 110:
            dfs.append(pd.read_csv(master_path, parse_dates=["timestamp"]))

    if not dfs:
        print(f"ERROR: No staleness data in {results_dir}", file=sys.stderr)
        return pd.DataFrame()

    df = pd.concat(dfs, ignore_index=True).drop_duplicates()
    df["strategy"] = pd.Categorical(df["strategy"],
                                    categories=["cache-aside","write-through","swr"],
                                    ordered=True)
    df["volatility_regime"] = pd.Categorical(df["volatility_regime"],
                                              categories=REGIME_ORDER, ordered=True)
    return df


def load_jmeter_summaries(results_dir: Path) -> pd.DataFrame:
    """
    Parse JMeter .csv result files.
    Each file is named:  <strategy>__<ttl>__<regime>.csv
    JMeter columns: timeStamp, elapsed, label, responseCode, ...
    """
    rows = []
    jmeter_dir = results_dir / "jmeter"
    if not jmeter_dir.exists():
        warnings.warn(f"JMeter directory not found at {jmeter_dir}. Skipping latency figures.")
        return pd.DataFrame()

    for csv_file in sorted(jmeter_dir.glob("*_measure.csv")):
        # Parse filename
        stem = csv_file.stem.replace("_measure", "")
        parts = stem.split("__")
        if len(parts) != 3:
            continue
        strategy, ttl, regime = parts

        jdf = pd.read_csv(csv_file)
        if "elapsed" not in jdf.columns:
            continue

        elapsed = jdf["elapsed"].dropna()
        rows.append({
            "strategy":          strategy,
            "ttl_policy":        ttl,
            "volatility_regime": regime,
            "p50_ms":            elapsed.quantile(0.50),
            "p99_ms":            elapsed.quantile(0.99),
            "rps":               len(elapsed) / elapsed.count() * 1000,  # rough RPS
            "n_requests":        len(elapsed),
        })

    return pd.DataFrame(rows) if rows else pd.DataFrame()


def load_redis_stats(results_dir: Path) -> pd.DataFrame:
    """
    Parse Redis INFO stats files to extract keyspace_hits and keyspace_misses.
    """
    rows = []
    redis_dir = results_dir / "redis"
    if not redis_dir.exists():
        return pd.DataFrame()

    for txt_file in sorted(redis_dir.glob("*.txt")):
        stem = txt_file.stem
        parts = stem.split("__")
        if len(parts) != 3:
            continue
        strategy, ttl, regime = parts
        content = txt_file.read_text()

        hits_m = re.search(r"keyspace_hits:(\d+)", content)
        miss_m = re.search(r"keyspace_misses:(\d+)", content)
        if hits_m and miss_m:
            hits = int(hits_m.group(1))
            misses = int(miss_m.group(1))
            total = hits + misses
            rows.append({
                "strategy":          strategy,
                "ttl_policy":        ttl,
                "volatility_regime": regime,
                "hits":              hits,
                "misses":            misses,
                "hit_ratio":         hits / total if total > 0 else 0.0,
            })

    return pd.DataFrame(rows) if rows else pd.DataFrame()

# ── Summary Table ─────────────────────────────────────────────────────────────

def build_summary(staleness: pd.DataFrame) -> pd.DataFrame:
    summary = staleness.groupby(
        ["strategy","ttl_policy","volatility_regime"], observed=True
    ).agg(
        avg_e_stale=("e_stale", "mean"),
        p95_e_stale=("e_stale", lambda x: x.quantile(0.95)),
        avg_e_pct=("e_pct", "mean"),
        avg_latency_ms=("latency_ms", "mean"),
        cache_hit_ratio=("cache_hit", "mean"),
        n_samples=("e_stale", "count"),
    ).reset_index()
    return summary

# ── Figures ───────────────────────────────────────────────────────────────────

def fig1_latency(jmeter: pd.DataFrame, out: Path):
    if jmeter.empty:
        print("Skipping Fig1 (no JMeter data)")
        return
    fig, axes = plt.subplots(1, 3, figsize=(15, 5), sharey=True)
    for ax, regime in zip(axes, REGIME_ORDER):
        subset = jmeter[jmeter["volatility_regime"] == regime]
        x = np.arange(len(TTL_ORDER))
        width = 0.25
        for i, strategy in enumerate(["cache-aside","write-through","swr"]):
            s = subset[subset["strategy"] == strategy].set_index("ttl_policy")
            p50 = [s.loc[t, "p50_ms"] if t in s.index else 0 for t in TTL_ORDER]
            p99 = [s.loc[t, "p99_ms"] if t in s.index else 0 for t in TTL_ORDER]
            ax.bar(x + i*width, p50, width, label=f"{strategy} P50",
                   color=STRATEGY_COLORS[strategy], alpha=0.7)
            ax.bar(x + i*width, p99, width, label=f"{strategy} P99",
                   color=STRATEGY_COLORS[strategy], alpha=0.4, hatch="//")
        ax.set_title(f"Regime: {regime.upper()}")
        ax.set_xticks(x + width)
        ax.set_xticklabels(TTL_ORDER, rotation=30, ha="right")
        ax.set_xlabel("TTL Policy")
    axes[0].set_ylabel("Latency (ms)")
    axes[0].legend(fontsize=7, loc="upper right")
    fig.suptitle("Figure 1: P50 / P99 Latency by Strategy and TTL Policy", fontsize=13)
    plt.tight_layout()
    plt.savefig(out / "fig1_latency.png", dpi=150)
    plt.close()
    print(f"Saved fig1_latency.png")


def fig2_hit_ratio(redis: pd.DataFrame, out: Path):
    if redis.empty:
        print("Skipping Fig2 (no Redis stats)")
        return
    for regime in REGIME_ORDER:
        subset = redis[redis["volatility_regime"] == regime].copy()
        pivot = subset.pivot(index="strategy", columns="ttl_policy", values="hit_ratio")
        pivot = pivot.reindex(columns=TTL_ORDER)
        fig, ax = plt.subplots(figsize=(8, 4))
        sns.heatmap(pivot, annot=True, fmt=".2f", cmap="YlGn", vmin=0, vmax=1, ax=ax)
        ax.set_title(f"Figure 2: Cache Hit Ratio — {regime.upper()} volatility")
        plt.tight_layout()
        plt.savefig(out / f"fig2_hit_ratio_{regime}.png", dpi=150)
        plt.close()
    print("Saved fig2_hit_ratio_*.png")


def fig3_estale_timeseries(staleness: pd.DataFrame, out: Path):
    """E_stale time series under HIGH volatility: Static-5s vs. Dynamic TTL."""
    high = staleness[
        (staleness["volatility_regime"] == "high") &
        (staleness["ttl_policy"].isin(["static-5", "static-30", "dynamic"]))
    ].copy()
    if high.empty:
        print("Skipping Fig3 (no high-volatility data)")
        return
    high = high.sort_values("timestamp")
    high["elapsed_s"] = (high["timestamp"] - high["timestamp"].min()).dt.total_seconds()

    fig, ax = plt.subplots(figsize=(12, 5))
    for (strategy, ttl), grp in high.groupby(["strategy","ttl_policy"], observed=True):
        ax.plot(grp["elapsed_s"], grp["e_stale"],
                label=f"{strategy} / {ttl}", alpha=0.7, linewidth=1)

    ax.set_xlabel("Elapsed time (s)")
    ax.set_ylabel("E_stale (USD)")
    ax.set_title("Figure 3: E_stale Time Series - HIGH Volatility Regime")
    ax.legend(fontsize=8)
    plt.tight_layout()
    plt.savefig(out / "fig3_estale_timeseries.png", dpi=150)
    plt.close()
    print("Saved fig3_estale_timeseries.png")


def fig4_epct_cdf(staleness: pd.DataFrame, out: Path):
    fig, axes = plt.subplots(1, 3, figsize=(15, 5), sharey=True)
    for ax, regime in zip(axes, REGIME_ORDER):
        subset = staleness[staleness["volatility_regime"] == regime]
        for strategy in ["cache-aside","write-through","swr"]:
            s = subset[subset["strategy"] == strategy]["e_pct"].dropna()
            if s.empty:
                continue
            sorted_s = np.sort(s)
            cdf = np.arange(1, len(sorted_s)+1) / len(sorted_s)
            ax.plot(sorted_s, cdf, label=strategy, color=STRATEGY_COLORS[strategy])
        ax.axvline(x=0.5, color="red", linestyle="--", linewidth=0.8, label="0.5% threshold")
        ax.set_title(f"Regime: {regime.upper()}")
        ax.set_xlabel("E_% valuation error")
        ax.xaxis.set_major_formatter(mticker.PercentFormatter())
    axes[0].set_ylabel("CDF")
    axes[0].legend(fontsize=8)
    fig.suptitle("Figure 4: CDF of E_% Portfolio Valuation Error", fontsize=13)
    plt.tight_layout()
    plt.savefig(out / "fig4_epct_cdf.png", dpi=150)
    plt.close()
    print("Saved fig4_epct_cdf.png")


def fig5_api_cost(staleness: pd.DataFrame, out: Path):
    """
    Proxy API cost = number of cache misses (each miss triggers an upstream API call).
    """
    miss_subset = staleness[~staleness["cache_hit"]] if "cache_hit" in staleness.columns else pd.DataFrame()
    if not miss_subset.empty:
        miss_counts = miss_subset.groupby(
            ["strategy","ttl_policy"], observed=True
        ).size().reset_index(name="api_calls")
    else:
        miss_counts = pd.DataFrame(columns=["strategy", "ttl_policy", "api_calls"])

    fig, ax = plt.subplots(figsize=(10, 5))
    x = np.arange(len(TTL_ORDER))
    width = 0.25
    for i, strategy in enumerate(["cache-aside","write-through","swr"]):
        s = miss_counts[miss_counts["strategy"] == strategy].set_index("ttl_policy")
        vals = [s.loc[t, "api_calls"] if t in s.index else 0 for t in TTL_ORDER]
        ax.bar(x + i*width, vals, width, label=strategy, color=STRATEGY_COLORS[strategy])
    ax.set_xticks(x + width)
    ax.set_xticklabels(TTL_ORDER, rotation=30, ha="right")
    ax.set_xlabel("TTL Policy")
    ax.set_ylabel("API Calls (cache misses proxy)")
    ax.set_title("Figure 5: API Query Volume vs. TTL Policy (Cost Proxy)")
    ax.legend()
    plt.tight_layout()
    plt.savefig(out / "fig5_api_cost.png", dpi=150)
    plt.close()
    print("Saved fig5_api_cost.png")


def fig6_pareto(summary: pd.DataFrame, jmeter: pd.DataFrame, out: Path):
    """
    2D Pareto projection: P99 latency vs. mean E_stale.
    Each point = one (strategy, ttl, regime) combination.
    """
    if jmeter.empty:
        print("Skipping Fig6 (no JMeter data)")
        return

    merged = summary.merge(jmeter[["strategy","ttl_policy","volatility_regime","p99_ms"]],
                           on=["strategy","ttl_policy","volatility_regime"], how="left")
    if merged.empty:
        return

    fig, ax = plt.subplots(figsize=(10, 7))
    for strategy in ["cache-aside","write-through","swr"]:
        s = merged[merged["strategy"] == strategy]
        ax.scatter(s["p99_ms"], s["avg_e_stale"],
                   label=strategy, color=STRATEGY_COLORS[strategy],
                   s=100, alpha=0.8, edgecolors="white", linewidth=0.5)

    # Annotate key configurations
    for _, row in merged.iterrows():
        ax.annotate(f"{row['ttl_policy']}\n{row['volatility_regime']}",
                    (row["p99_ms"], row["avg_e_stale"]),
                    textcoords="offset points", xytext=(5, 3), fontsize=6, alpha=0.7)

    ax.set_xlabel("P99 Latency (ms)")
    ax.set_ylabel("Mean E_stale (USD)")
    ax.set_title("Figure 6: Pareto Frontier - Latency vs. Staleness Error")
    ax.legend()
    plt.tight_layout()
    plt.savefig(out / "fig6_pareto.png", dpi=150)
    plt.close()
    print("Saved fig6_pareto.png")

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    args = parse_args()
    results_dir = Path(args.results_dir)
    fig_dir = results_dir / "figures"
    fig_dir.mkdir(parents=True, exist_ok=True)

    print("Loading staleness measurements...")
    staleness = load_staleness(results_dir)
    print(f"  {len(staleness):,} rows loaded.")

    print("Loading JMeter summaries...")
    jmeter = load_jmeter_summaries(results_dir)
    print(f"  {len(jmeter)} JMeter run summaries loaded.")

    print("Loading Redis stats...")
    redis = load_redis_stats(results_dir)
    print(f"  {len(redis)} Redis stat snapshots loaded.")

    print("Building summary table...")
    summary = build_summary(staleness)
    summary.to_csv(results_dir / "summary_table.csv", index=False)
    print(f"  Summary table saved to {results_dir / 'summary_table.csv'}")
    print(summary.to_string())

    print("\nGenerating figures...")
    fig1_latency(jmeter, fig_dir)
    fig2_hit_ratio(redis, fig_dir)
    fig3_estale_timeseries(staleness, fig_dir)
    fig4_epct_cdf(staleness, fig_dir)
    fig5_api_cost(staleness, fig_dir)
    fig6_pareto(summary, jmeter, fig_dir)

    print(f"\nAll outputs saved to {results_dir}/")


if __name__ == "__main__":
    main()
