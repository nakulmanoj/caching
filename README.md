# WealthWise Cache Consistency Testbed

> **Research Paper**: *Balancing Speed and Accuracy: A Cache Consistency–Performance Study
> for Real-Time Financial Data in WealthWise*

A Spring Boot 3.x + Redis 7 empirical testbed evaluating Cache-Aside, Write-Through, and
Stale-While-Revalidate caching strategies under volatility-adjusted TTL policies.
This testbed generates the experimental data for Sections 3–7 of the paper.

---

## Quick Start

```bash
# 1. Start infrastructure
docker compose up -d

# 2. Build
mvn clean package -DskipTests

# 3. Smoke test (Cache-Aside, static-30s TTL, medium volatility)
bash scripts/run_experiment.sh --smoke

# 4. Full experiment (45 runs, ~6 hours)
bash scripts/run_experiment.sh --all

# 5. Generate all figures
pip install pandas matplotlib seaborn numpy scipy
python scripts/collect_metrics.py --results-dir ./results
```

---

## Caching Strategies

| Strategy | Spring Profile | Key Mechanism |
|---|---|---|
| Cache-Aside | `cache-aside` | `@Cacheable` — lazy load on miss |
| Write-Through | `write-through` | `@CachePut` — synchronous write every tick |
| Stale-While-Revalidate | `swr` | Serve stale + async background refresh |

Switch strategies via `--spring.profiles.active=<strategy>`.

## TTL Policies

| Policy | Description |
|---|---|
| `static-5` | Fixed 5-second TTL |
| `static-30` | Fixed 30-second TTL (default) |
| `static-60` | Fixed 60-second TTL |
| `dynamic` | `TTL = k/σ` — volatility-adaptive |
| `event` | Redis pub/sub invalidation on price-change threshold |

Override at runtime: `--wealthwise.caching.ttl-policy=dynamic`

## Volatility Regimes

Controlled by `--wealthwise.simulator.sigma`:

| Regime | σ (annualized) | `--sigma` arg |
|---|---|---|
| LOW | 0.05 (5%) | `0.05` |
| MEDIUM | 0.30 (30%) | `0.30` |
| HIGH | 0.80 (80%) | `0.80` |

## Project Structure

```
wealthwise/
├── src/main/java/com/wealthwise/
│   ├── WealthWiseApplication.java
│   ├── config/         RedisConfig.java
│   ├── controller/     PortfolioController.java
│   ├── metrics/        StalenessMetricsFilter.java, MetricsExporter.java
│   ├── model/          Asset, Portfolio, Holding, StalenessMeasurement
│   ├── repository/     JPA repos
│   ├── service/        PriceService (×3), PortfolioService, VolatilityMonitor,
│   │                   DynamicTTLPolicy
│   └── simulator/      GBMPriceSimulator
├── src/main/resources/ application.yml, schema.sql
├── scripts/            run_experiment.sh, collect_metrics.py
├── jmeter/             wealthwise-benchmark.jmx  ← create with JMeter GUI
├── results/            staleness.csv, jmeter/, redis/, figures/
└── docker-compose.yml
```

## Key Endpoints

| Endpoint | Description |
|---|---|
| `GET /api/portfolio/{userId}/value` | **Load-test target** — returns V_cached(t) |
| `GET /api/portfolio/ping` | Health / warm-up |
| `GET /actuator/metrics` | Micrometer metrics (P50/P99 via histogram) |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |

## Metrics Collected

| Source | Metric |
|---|---|
| `results/staleness.csv` | E_stale, E_%, V_cached, V_realtime per request |
| `results/jmeter/` | P50/P99 latency, throughput (RPS) |
| `results/redis/` | Cache hit ratio (keyspace_hits / total) |
| `/actuator/prometheus` | `wealthwise_e_stale` distribution, `wealthwise_api_calls_total` |

## Dependencies

- Java 21, Maven 3.9+
- Docker + Docker Compose
- Apache JMeter 5.6+ (on PATH as `jmeter`)
- Python 3.10+ with `pandas matplotlib seaborn numpy scipy`
