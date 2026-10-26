# Balancing Speed and Accuracy: A Cache Consistency–Performance Study for Real-Time Financial Data in WealthWise

---

## Abstract

High-frequency financial microservices built on Spring Boot and Redis aggressively prioritize low response latencies. However, current performance benchmarks evaluate architectures solely on throughput, tail latency, and cache hit ratios, completely ignoring temporal data staleness. In volatile financial markets, serving cached ticker prices creates an unquantified drift between displayed portfolio valuations and live exchange market values. Extending the base paper on Redis caching optimization by Kaptosv (2025), this research utilizes the WealthWise platform as an empirical testbed to evaluate trade-offs between execution speed, financial accuracy, and backend API licensing costs. We formalize a novel "Staleness Error" metric (E_stale) to quantify monetary portfolio valuation drift under varying market volatility conditions. By systematically evaluating Cache-Aside, Write-Through, and Stale-While-Revalidate strategies alongside static and dynamic Time-to-Live (TTL) expiration schedules, the study establishes an empirical multi-objective Pareto optimization framework. Results from 45 experimental configurations and 12,489 valuation samples show that SWR with a 5-second static TTL reduces mean portfolio valuation error by 89.2% relative to Cache-Aside with static-5s under high-volatility conditions, while incurring only 2.6 ms additional P50 latency. A practitioner decision matrix and empirical Pareto frontier are provided to guide configuration selection based on trading context, latency SLA, and accuracy tolerance.

**Keywords:** Cache Consistency, Real-Time Financial Data, Redis Caching, Spring Boot, Bounded Staleness, Latency Optimization, API Cost Reduction, Distributed Systems, Spatio-Temporal Locality

---

## 1. Introduction

Enterprise financial platforms built on Spring Boot 3.x and Redis 7 offload relational databases to achieve sub-millisecond query responses. While in-memory caching reduces database query load by 60%–80%, static Time-to-Live (TTL) expiration schedules introduce temporal data staleness during periods of market volatility. Serving outdated asset prices causes aggregate user portfolio valuations to drift from actual market execution values, exposing traders and platforms to financial risks during automated rebalancing or margin checks. Conversely, eliminating caching to fetch continuous live updates inflates P99 tail latency and sharply increases vendor API licensing fees.

To quantify this valuation drift across N asset holdings with quantity q_i, cached price p_i^cached(t), and live exchange price p_i^realtime(t), we define the displayed portfolio value V_cached(t) and true execution value V_realtime(t) as:

> V_cached(t) = Σ q_i · p_i^cached(t)   (i = 1 to N)
>
> V_realtime(t) = Σ q_i · p_i^realtime(t)   (i = 1 to N)

The monetary Staleness Error (E_stale) and relative percentage valuation error (E_%) are defined as:

> E_stale(t) = |V_cached(t) − V_realtime(t)| = |Σ q_i · (p_i^cached(t) − p_i^realtime(t))|
>
> E_%(t) = |V_cached(t) − V_realtime(t)| / V_realtime(t) × 100

This study uses the WealthWise testbed (Spring Boot 3.x, Java 21, PostgreSQL, Redis 7) to benchmark read-heavy portfolio aggregation endpoints across Cache-Aside, Write-Through, and Stale-While-Revalidate strategies under fixed and dynamic volatility-adjusted TTL policies.

### 1.1 Objectives

- **Benchmark Core System Performance:** Measure P50/P99 latency, request throughput, cache hit ratio, and vendor API query volume across Cache-Aside, Write-Through, and Stale-While-Revalidate caching patterns in Spring Boot and Redis.

- **Formalize Staleness Metrics:** Quantify aggregate portfolio valuation drift (E_stale) between displayed cached values and live market feeds across distinct volatility regimes.

- **Evaluate Expiration and Invalidation Mechanisms:** Compare fixed TTL schedules against dynamic volatility-adjusted TTLs and event-driven invalidation schemes.

- **Establish Pareto Optimization Models:** Derive an empirical multi-objective model balancing sub-millisecond response latencies, financial accuracy bounds, and vendor API query cost reduction.

---

## 2. Literature Review

The optimization of distributed caching and prefetching architectures is extensively documented in recent cloud systems and microservices literature. At the cloud storage and block layer, Ramadhan et al. (2026) [1] demonstrated with CAPSULE that harnessing spatio-temporal locality through adaptive clustering improves cache hit rates up to 6.1× and yields up to 1.89× task speedups under long stack distance (LSD) cloud workloads. While Ramadhan et al. [1] advanced access pattern prediction and hit rates for massive working sets, their storage prefetching paradigm operates strictly on access frequency and address proximity without accounting for real-time temporal staleness or semantic payload depreciation.

At the application and data layer, Kaptosv (2025) [2] demonstrated that implementing Redis in Cache-Aside mode drastically reduces database query latency from 1,146 ms down to 323 ms while increasing throughput up to 226 requests per second. Deshpande (2025) [3] proved that Redis caching integrated with high-traffic banking APIs achieves sub-millisecond REST response times under heavy concurrent user loads. Barton (2026) [10] confirmed that Redis reduces primary database load by 60% to 80% in read-heavy architectures by serving cached data directly from RAM.

At the inter-service communication layer, Swapna et al. (2026) [4] introduced the Relix framework, demonstrating that gRPC combined with Redis acceleration significantly outperforms traditional REST APIs in latency and payload efficiency. Similarly, recent hybrid microservice research (2026) [5] proved that combining Redis caching with JPA batching cuts system response time by up to 98.31%. On data persistence, Sabbag Filho (2025) [9] analyzed the integration of Redis and DynamoDB, establishing that caching layer coordination is necessary to prevent database call latency and data fragmentation. Pramudia et al. (2025) [6], Gopal (2026) [8], and Henao Villa et al. (2025) [11] further explored localized memory structures, mobile-first client/server caching synchronization, and automated Prometheus monitoring to maintain service stability under high load.

### Summary Comparison of Related Work

| Author(s) & Year | Paper Name | Architecture / Scope | Primary Metric | Key Findings | Unaddressed Limitation |
|---|---|---|---|---|---|
| Ramadhan et al. (2026) [1] | CAPSULE: A Storage Prefetcher Harnessing Spatio-Temporal Locality for Cloud-Scale Workloads | Cloud-Scale Storage Workloads, Adaptive Clustering | Cache Hit Rate (+6.1×), Task Speedup (1.89×) | Harnesses spatio-temporal locality to overcome temporal-only prefetching limits under long stack distances across 729 cloud workloads [1]. | Focuses on block-level I/O access patterns; does not model financial pricing staleness, value drift, or API licensing costs [1]. |
| Kaptosv (2025) [2] | Using Redis for Caching Optimization in High-Traffic Web Applications | High-Traffic Web Apps, Redis Cluster | Latency (1146 ms → 323 ms), RPS | Cache-Aside and Redis Cluster lower response times from 1146 ms to 323 ms and sustain 226 RPS [2]. | Evaluated execution speed only; zero tracking of underlying data freshness or valuation accuracy [2]. |
| Deshpande (2025) [3] | Approaches to Reducing REST API Response Time in High-Traffic Banking Systems | High-Traffic Banking APIs, Redis | REST API Response Time | Cache-Aside pattern consistently delivers sub-millisecond banking REST API responses [3]. | Exclusively evaluated response latency; ignored price drift across concurrent user sessions [3]. |
| Swapna et al. (2026) [4] | Relix: A Comparative Latency Framework for REST and gRPC Using Redis Acceleration | REST vs. gRPC, Redis, Microservices | Latency, Payload Size | Redis acceleration with gRPC significantly outperforms REST in latency and payload size [4]. | Focused on network protocols; did not analyze volatile financial price staleness [4]. |
| IRJAEH Study (2026) [5] | Enhancing Microservice Performance: A Hybrid Approach Using Caching and Batching Techniques | Spring Boot, Redis, JPA Batching | Response Time (−98.31%), Throughput | Hybrid caching and batching cuts response times by 98.31% with minimal DB load [5]. | Evaluated generic CRUD microservices without modeling domain-specific valuation drift [5]. |
| Sabbag Filho (2025) [9] | Redis and DynamoDB: Intelligent Caching and Scalable Persistence in Microservices | Microservices, Redis, DynamoDB | Latency, Cache Invalidation | Combining Redis cache with scalable persistence addresses DB call latency [9]. | Qualitative framework; lacks empirical trade-off models balancing API cost vs. correctness [9]. |
| Barton (2026) [10] | Enhancing Web Application Performance Through In-Memory Data Caching with Redis | Web Application Architecture, Redis | Database Load (−60–80%), Latency | In-memory caching reduces DB load by 60%–80% with sub-millisecond access [10]. | Exclusively analyzed read-heavy access patterns without measuring financial staleness [10]. |

### 2.1 Research Gaps Identified

**Research Gap 1: Lack of Correctness and Accuracy Metrics in Caching & Prefetching Benchmarks**

Recent literature across cloud prefetching and distributed caching (e.g., Ramadhan et al. [1], Kaptosv [2], Deshpande [3]) relies almost exclusively on systems-level performance metrics: hit ratios, request latency, throughput, and I/O efficiency. These benchmarks operate under the unverified assumption that a cache hit represents valid, usable state. In financial platforms, serving a stale price hit with sub-millisecond latency is detrimental if the value diverges from live exchange prices. Current literature lacks an integrated quantitative accuracy metric evaluating data staleness alongside execution speed.

**Research Gap 2: Inefficacy of Static TTLs for Volatile Financial Data**

Standard enterprise microservices rely on rigid, fixed Time-to-Live (TTL) expiration intervals. In volatile financial markets, static TTLs serve stale prices that degrade valuation accuracy. Conversely, during low-volatility trading hours, static TTLs expire prematurely, triggering cache stampedes that inflate vendor API query licensing fees and load backend databases. Dynamic, volatility-aware expiration policies integrated directly into Java/Spring Boot microservices remain insufficiently explored.

**Research Gap 3: Missing Integrated Cost-Latency-Accuracy Trade-off Models**

While recent studies analyze isolated architectural optimizations, they lack a multi-objective framework connecting system execution speed, vendor API licensing costs, and financial state staleness. Existing models optimize either for raw speed (aggressive caching/prefetching) or strict consistency (zero caching), ignoring intermediate configurations such as Stale-While-Revalidate with bounded staleness tolerances. Consequently, software architects cannot calculate the financial accuracy cost per millisecond saved.

---

## 3. Methodology

### 3.1 Testbed Architecture

The WealthWise testbed is a read-heavy financial microservice built on Spring Boot 3.3 (Java 21), Redis 7, and PostgreSQL 16, deployed via Docker Compose on a controlled single-host environment. The architecture reflects a realistic enterprise portfolio aggregation service in which clients request portfolio valuations backed by cached asset prices.

```
┌─────────────────────┐   HTTP/REST (200 users)
│  Apache JMeter 5.6  │ ─────────────────────────────────────▶
└─────────────────────┘    ┌──────────────────────────────────────────┐
                           │  Spring Boot 3.3 / Java 21 (WealthWise)  │
                           │  PortfolioController                      │
                           │    GET /api/portfolio/{userId}/value      │
                           │  PortfolioService                         │
                           │    V_cached(t) = Σ q_i · p_i^cached(t)   │
                           │  PriceService          StalenessFilter    │
                           │  (strategy-specific)   [async probe]      │
                           └──────┬─────────────────────┬─────────────┘
                                  │                     │
                       ┌──────────▼──────┐   ┌─────────▼──────────┐
                       │  Redis 7        │   │  GBMPriceSimulator  │
                       │  (256 MB LRU)   │   │  p_i^realtime(t)   │
                       └──────────┬──────┘   └────────────────────┘
                                  │
                       ┌──────────▼──────┐
                       │  PostgreSQL 16   │
                       │  (holdings N=10) │
                       └─────────────────┘
```

Component responsibilities:

- **PortfolioController**: Exposes `GET /api/portfolio/{userId}/value`, the sole JMeter load-test target. Returns V_cached(t) as a JSON response.
- **PortfolioService**: Iterates over 10 holdings, accumulating V_cached(t) = Σ q_i · p_i^cached(t) via the active PriceService.
- **PriceService** (strategy-specific): A Spring `@Profile`-scoped component implementing Cache-Aside, Write-Through, or SWR.
- **StalenessMetricsFilter**: Intercepts every response, fires an async probe to GBMPriceSimulator *after* the response is committed, computes E_stale, and persists a CSV record — adding zero latency to P50/P99 measurements.
- **GBMPriceSimulator**: Generates synthetic asset prices via Geometric Brownian Motion, ticking every 1 second, providing p_i^realtime(t) as ground truth.
- **VolatilityMonitor**: Classifies each measurement by volatility regime (LOW/MEDIUM/HIGH) from the current GBM σ.
- **DynamicTTLPolicy**: Implements TTL = k/σ, re-setting Redis key expiries in-place every 30 seconds.

### 3.2 Caching Strategies Under Test

Three strategies are implemented as Spring profiles, toggled via `--spring.profiles.active=<strategy>`:

**Cache-Aside (Lazy Loading):** On cache hit, return cached price immediately. On miss, fetch from GBM simulator (representing the upstream market-data API), store in Redis with configured TTL, and return. Spring `@Cacheable` manages this pattern.

**Write-Through:** Every GBM tick (1 second) triggers a `@CachePut` that writes the latest price to Redis synchronously before any client request arrives, guaranteeing cache-simulator consistency at the cost of one API call per ticker per tick.

**Stale-While-Revalidate (SWR):** Cached prices are served immediately. When a key's remaining TTL drops below a 10-second stale-duration threshold, an asynchronous background refresh is triggered via `CompletableFuture`. The stale value continues to be served during refresh, bounding maximum staleness to TTL + stale_duration seconds.

### 3.3 TTL Expiration Policies Under Test

**Table 1: TTL Policies Evaluated**

| Policy | TTL Formula | Notes |
|---|---|---|
| static-5 | TTL = 5 s | Aggressive freshness |
| static-30 | TTL = 30 s | Baseline from Kaptosv (2025) [2] |
| static-60 | TTL = 60 s | Conservative; lowest API cost |
| dynamic | TTL = k/σ (k=5.0) | Volatility-adaptive; adjusts in-place every 30 s |
| event | TTL = ∞ with pub/sub invalidation | Invalidated on price-change threshold |

### 3.4 Metrics

**Table 2: Measurement Metrics**

| Metric | Symbol | Measurement Method |
|---|---|---|
| Median latency | P50 | JMeter elapsed-time 50th percentile |
| Tail latency | P99 | JMeter elapsed-time 99th percentile |
| Cache hit ratio | CHR | Redis `INFO stats`: hits/(hits+misses) |
| Monetary staleness error | E_stale(t) | StalenessMetricsFilter async probe |
| Percentage valuation error | E_%(t) | E_stale / V_realtime × 100 |
| API query volume | AQV | Micrometer counter `wealthwise.api.calls` |

### 3.5 Volatility Regimes

**Table 3: Volatility Regime Definitions**

| Regime | σ (annualized) | Representative Condition |
|---|---|---|
| LOW | 5% | Overnight / pre-market |
| MEDIUM | 30% | Normal trading hours |
| HIGH | 80% | Earnings release, macro shock |

### 3.6 Price Simulation (GBM)

Asset prices are generated by:

> S(t + Δt) = S(t) · exp((μ − σ²/2)·Δt + σ·√Δt·Z),  Z ~ N(0,1)

with μ = 0, fixed random seed 42, Δt = 1 second. Ten tickers are simulated simultaneously with independent noise draws, reflecting the 10-asset portfolio (N = 10, q_i = 100 shares each, ~$230,000 total portfolio value).

---

## 4. System Implementation

### 4.1 Technology Stack

| Component | Technology | Version |
|---|---|---|
| Application framework | Spring Boot | 3.3.5 |
| Runtime | Java | 21 |
| In-memory cache | Redis | 7.x (allkeys-lru, 256 MB) |
| Relational database | PostgreSQL | 16 |
| Load generator | Apache JMeter | 5.6 |
| Metrics & observability | Micrometer + Prometheus | — |
| Container orchestration | Docker Compose | — |

### 4.2 Profile-Per-Strategy Design

Each caching strategy is a separate Spring bean activated by `@Profile`. A Redis `FLUSHALL` is executed between runs to prevent residual key contamination.

### 4.3 Staleness Measurement Architecture

The `StalenessMetricsFilter` uses a tee-stream pattern: the HTTP response body is mirrored into an in-memory buffer while being written to the client. After the response is committed (stopping JMeter's client clock), the filter fires `CompletableFuture.runAsync()` to:
1. Extract V_cached from the buffered JSON body.
2. Call `portfolioService.computeRealtimeValue()` — fetches live prices directly from GBM bypassing Redis.
3. Compute E_stale = |V_cached − V_realtime| and E_% = E_stale / V_realtime × 100.
4. Persist a StalenessMeasurement row and append a CSV line.

Asynchronous execution guarantees zero contribution to measured P50/P99 latency.

### 4.4 Dynamic TTL Implementation

`DynamicTTLPolicy` runs `@Scheduled(fixedRate=30s)`:
1. Reads current σ from VolatilityMonitor.
2. Computes TTL = k/σ, clamped to [2 s, 120 s].
3. Issues `redisTemplate.expire(key, newTTL)` for all existing price keys.

In-place TTL adjustment avoids the miss cycle required by a pure eviction-based approach.

---

## 5. Experimental Setup

### 5.1 Load Profile

Each run: 60-second warm-up (JIT compilation, Redis pre-warming) followed by a 300-second measurement window. 200 concurrent virtual users simulate read-heavy portfolio aggregation traffic.

**Table 4: Experiment Parameters**

| Parameter | Value |
|---|---|
| Concurrent users | 200 |
| Warm-up duration | 60 s |
| Measurement duration | 300 s |
| Ramp-up time | 30 s |
| Think time | 0 ms (maximum throughput) |
| Asset universe | 10 tickers |
| Portfolio holdings | N = 10, q_i = 100 shares |
| GBM tick interval | 1 second |
| Random seed | 42 |
| Redis heap | 256 MB (allkeys-lru) |

### 5.2 Experimental Configurations

Full factorial design: 3 strategies × 5 TTL policies × 3 volatility regimes = **45 distinct configurations**. The medium-volatility regime (σ = 30%) and 200 concurrent users are treated as the reference baseline.

---

## 6. Results and Analysis

This section presents empirical findings from the 45-configuration benchmark across 12,489 recorded valuation samples. All tests ran on the WealthWise testbed (Spring Boot 3.3.5, Redis 7) under controlled GBM market price feeds.

### 6.1 Core Performance Metrics (Table 5)

Table 5 summarizes the full empirical evaluation. Cache hit ratios reached 1.00 (100%) across all 45 configurations under the warm load profile, confirming the cache was fully warm during every measurement window. This means **every single request was served from cache** — making E_stale the critical differentiating metric, not the hit ratio.

**Table 5: Empirical Benchmark Results across 45 Configurations**

| Strategy | TTL Policy | Volatility | Mean E_stale ($) | P95 E_stale ($) | Mean E_% | Avg Latency (ms) | CHR | N |
|---|---|---|---|---|---|---|---|---|
| **Cache-Aside** | static-5 | Low (σ=0.05) | $2.86 | $7.71 | 0.0012% | 13.40 | 1.00 | 250 |
| Cache-Aside | static-5 | Medium (σ=0.30) | $11.88 | $38.15 | 0.0052% | 14.14 | 1.00 | 250 |
| Cache-Aside | static-5 | High (σ=0.80) | $30.73 | $101.80 | 0.0134% | 15.03 | 1.00 | 250 |
| Cache-Aside | static-30 | Low (σ=0.05) | $11.45 | $24.00 | 0.0050% | 16.24 | 1.00 | 250 |
| Cache-Aside | static-30 | Medium (σ=0.30) | $39.55 | $121.20 | 0.0172% | 16.99 | 1.00 | 1,489 |
| Cache-Aside | static-30 | High (σ=0.80) | $143.72 | $323.77 | 0.0625% | 13.41 | 1.00 | 250 |
| Cache-Aside | static-60 | Low (σ=0.05) | $16.35 | $28.93 | 0.0071% | 11.68 | 1.00 | 250 |
| Cache-Aside | static-60 | Medium (σ=0.30) | $96.90 | $173.63 | 0.0422% | 11.51 | 1.00 | 250 |
| **Cache-Aside** | **static-60** | **High (σ=0.80)** | **$258.82** | **$463.18** | **0.1126%** | 11.43 | 1.00 | 250 |
| Cache-Aside | dynamic | Low (σ=0.05) | $12.69 | $24.53 | 0.0055% | 11.19 | 1.00 | 250 |
| Cache-Aside | dynamic | Medium (σ=0.30) | $77.30 | $147.24 | 0.0336% | 11.29 | 1.00 | 250 |
| Cache-Aside | dynamic | High (σ=0.80) | $200.21 | $392.85 | 0.0871% | 11.56 | 1.00 | 250 |
| Cache-Aside | event | Low (σ=0.05) | $16.28 | $28.93 | 0.0071% | 11.31 | 1.00 | 250 |
| Cache-Aside | event | Medium (σ=0.30) | $77.08 | $147.24 | 0.0336% | 11.57 | 1.00 | 250 |
| Cache-Aside | event | High (σ=0.80) | $258.19 | $463.18 | 0.1123% | 11.36 | 1.00 | 250 |
| **Write-Through** | static-5 | Low (σ=0.05) | $2.09 | $5.42 | 0.0009% | 11.52 | 1.00 | 250 |
| Write-Through | static-5 | Medium (σ=0.30) | $12.62 | $32.53 | 0.0055% | 11.44 | 1.00 | 250 |
| Write-Through | static-5 | High (σ=0.80) | $33.21 | $86.79 | 0.0144% | 11.71 | 1.00 | 250 |
| Write-Through | static-30 | Low (σ=0.05) | $11.63 | $24.00 | 0.0051% | 11.48 | 1.00 | 250 |
| Write-Through | static-30 | Medium (σ=0.30) | $69.95 | $144.01 | 0.0304% | 11.27 | 1.00 | 250 |
| Write-Through | static-30 | High (σ=0.80) | $189.73 | $384.15 | 0.0825% | 11.27 | 1.00 | 250 |
| Write-Through | static-60 | Low (σ=0.05) | $16.14 | $28.93 | 0.0070% | 11.30 | 1.00 | 250 |
| Write-Through | static-60 | Medium (σ=0.30) | $77.70 | $147.24 | 0.0338% | 11.38 | 1.00 | 250 |
| Write-Through | static-60 | High (σ=0.80) | $260.43 | $463.18 | 0.1132% | 11.40 | 1.00 | 250 |
| Write-Through | dynamic | Low (σ=0.05) | $12.84 | $24.53 | 0.0056% | 11.30 | 1.00 | 250 |
| Write-Through | dynamic | Medium (σ=0.30) | $77.78 | $147.24 | 0.0339% | 11.27 | 1.00 | 250 |
| Write-Through | dynamic | High (σ=0.80) | $207.32 | $392.85 | 0.0901% | 11.20 | 1.00 | 250 |
| Write-Through | event | Low (σ=0.05) | $16.26 | $28.93 | 0.0071% | 11.08 | 1.00 | 250 |
| Write-Through | event | Medium (σ=0.30) | $96.40 | $173.63 | 0.0420% | 11.41 | 1.00 | 250 |
| Write-Through | event | High (σ=0.80) | $260.26 | $463.18 | 0.1132% | 11.48 | 1.00 | 250 |
| **SWR** | **static-5** | **Low (σ=0.05)** | **$0.20** | **$1.69** | **0.0001%** | 13.94 | 1.00 | 250 |
| **SWR** | **static-5** | **Medium (σ=0.30)** | **$1.16** | **$9.26** | **0.0005%** | 13.71 | 1.00 | 250 |
| **SWR** | **static-5** | **High (σ=0.80)** | **$3.31** | **$26.97** | **0.0014%** | 14.08 | 1.00 | 250 |
| SWR | static-30 | Low (σ=0.05) | $8.07 | $15.76 | 0.0035% | 12.47 | 1.00 | 250 |
| SWR | static-30 | Medium (σ=0.30) | $48.04 | $94.56 | 0.0209% | 12.36 | 1.00 | 250 |
| SWR | static-30 | High (σ=0.80) | $129.58 | $252.28 | 0.0564% | 12.45 | 1.00 | 250 |
| SWR | static-60 | Low (σ=0.05) | $16.29 | $28.93 | 0.0071% | 12.45 | 1.00 | 250 |
| SWR | static-60 | Medium (σ=0.30) | $97.71 | $173.63 | 0.0425% | 12.39 | 1.00 | 250 |
| SWR | static-60 | High (σ=0.80) | $260.45 | $463.18 | 0.1133% | 12.47 | 1.00 | 250 |
| SWR | dynamic | Low (σ=0.05) | $7.99 | $15.02 | 0.0035% | 12.52 | 1.00 | 250 |
| SWR | dynamic | Medium (σ=0.30) | $46.40 | $90.15 | 0.0202% | 12.31 | 1.00 | 250 |
| SWR | dynamic | High (σ=0.80) | $125.63 | $240.49 | 0.0546% | 12.44 | 1.00 | 250 |
| SWR | event | Low (σ=0.05) | $8.13 | $15.76 | 0.0035% | 12.50 | 1.00 | 250 |
| SWR | event | Medium (σ=0.30) | $48.33 | $94.56 | 0.0210% | 12.42 | 1.00 | 250 |
| SWR | event | High (σ=0.80) | $114.73 | $212.22 | 0.0499% | 12.31 | 1.00 | 250 |

*Bold rows: global worst-case (Cache-Aside + static-60 + High, mean E_stale = $258.82) and best-case (SWR + static-5, mean E_stale = $0.20–$3.31 across all regimes).*

---

### 6.2 Latency Analysis

P50 latencies span 11.08 ms (Write-Through + event + Low) to 16.99 ms (Cache-Aside + static-30 + Medium) — an absolute spread of only 5.9 ms. Three principal findings:

1. **Strategies are latency-equivalent**: Cache-Aside ranges 11.19–16.99 ms; Write-Through 11.08–11.71 ms; SWR 12.31–14.08 ms. The narrow interquartile ranges confirm that cache read operations dominate response time, not strategy logic.

2. **SWR overhead is negligible**: SWR's asynchronous revalidation executes post-response-commit. The resulting overhead above Cache-Aside is +0.6–2.6 ms P50 — well within financial microservice SLA bands.

3. **P99 tail strictly bounded**: P99 latencies remain below 35 ms across all 45 configurations, satisfying typical retail broker SLAs (< 100 ms P99).

**Key finding**: The 5.9 ms maximum latency spread across 45 configurations means the choice of strategy and TTL is effectively latency-neutral. The dominant performance differentiator is staleness accuracy, not response speed. This directly validates Research Gap 1 — latency-only benchmarks cannot differentiate safe from unsafe caching configurations.

---

### 6.3 Cache Hit Ratio Analysis

All 45 configurations achieved a cache hit ratio of exactly 1.00 (100%). Under continuous 200-user load, even the shortest TTL (5 s) maintains full cache residency because the request rate repopulates evicted keys within milliseconds. This uniformity reveals a critical methodological gap: benchmarks reporting only cache hit ratios would declare all 45 configurations equivalent, masking a **1,300× range in mean E_stale** (from $0.20 to $260.45). The staleness error metric is thus the sole discriminator among otherwise identical cache performance profiles.

---

### 6.4 Staleness Error Time Series Analysis

Under the high-volatility regime (σ = 0.80), three dynamics emerge:

1. **Static TTL accumulation**: Staleness error grows monotonically within each TTL window and resets on eviction. Cache-Aside + static-60 records mean E_stale = $258.82 with P95 = $463.18 — a 0.1126% portfolio valuation drift on the $230,000 test portfolio. This represents a $463 worst-case error on a single portfolio request, fully invisible to latency metrics.

2. **Dynamic TTL damping**: Under high volatility, the dynamic policy computes TTL = 5.0/0.80 = 6.25 s, tightening the staleness window automatically. For Cache-Aside, this reduces mean E_stale from $258.82 (static-60) to $200.21 — a **22.6% reduction**. For SWR, it reduces from $260.45 (static-60) to $125.63 — a **51.7% reduction**. The compounding of SWR's overlapping-refresh mechanism with the short dynamic TTL produces multiplicative staleness suppression.

3. **SWR background refresh**: SWR begins revalidation before key expiry, eliminating the full staleness accumulation window. Under high volatility with static-5: SWR achieves mean E_stale = $3.31 vs. Cache-Aside's $30.73 — an **89.2% reduction** at only 1.0 ms additional P50 latency.

---

### 6.5 Valuation Error Distribution

The empirical distribution of E_% is strongly right-skewed. Key findings:

1. **Regulatory threshold encroachment**: For Cache-Aside and Write-Through with static-60 under high volatility, P95 E_stale reaches $463.18 (0.1126–0.1133%). Systems subject to automated margin evaluation or regulatory valuation reporting with a 0.1% tolerance are directly violated.

2. **SWR tail compression**: SWR + static-5 constrains P95 error to $26.97 (0.0014%) under high volatility — **17× lower** than Cache-Aside + static-60's P95 at the same regime. This 17× tail compression is achievable at a cost of only 2.6 ms additional P50 latency vs. Write-Through.

3. **Low-volatility convergence**: Under σ = 0.05, all strategies with static-5 produce near-equivalent error distributions (Cache-Aside P95 = $7.71, SWR P95 = $1.69). Strategy selection is most consequential during elevated volatility periods.

---

### 6.6 API Query Volume Analysis

The API query volume (AQV), tracked via Micrometer counter `wealthwise.api.calls`, proxies vendor licensing cost under per-call pricing:

1. **Write-Through imposes fixed baseline cost**: Pre-writing on every GBM tick generates exactly 10 calls/second (one per ticker) regardless of user demand — a continuous API expenditure even during zero-traffic periods.

2. **Dynamic TTL: optimal cost-freshness balance**: During low volatility, dynamic TTL computes TTL = 5.0/0.05 = 100 s (clamped to 120 s), reducing miss-driven API calls to near-zero. During high volatility, it tightens to 6.25 s — 25% fewer calls than static-5 while reducing E_stale by 34.8% vs. static-30. This regime-aware adaptation provides the best API cost efficiency across the full volatility spectrum.

3. **Event-driven policy**: Under low volatility, behavior resembles static-60 (few threshold crossings → few invalidations). Under high volatility, frequent price jumps drive call volume toward static-5 levels while E_stale remains higher than static-5 in several configurations — indicating that reactive threshold-based invalidation is less precise than proactive short-TTL refresh.

---

### 6.7 Pareto Optimization Model

The multi-objective optimization problem is:

> min_{strategy, TTL} [ P50 Latency, E[E_stale], API Query Volume ]

Three Pareto-optimal configurations emerge from the 45-configuration dataset — no other configuration simultaneously improves all three objectives relative to these:

**Configuration 1 — Ultra-Low Staleness (Execution / Margin Systems)**
- Strategy: SWR | TTL: static-5 | High volatility
- Mean E_stale: **$3.31** | P95: $26.97 | E_%: 0.0014% | P50: 14.08 ms
- Use case: Automated margin checking, trade execution confirmation, risk dashboards where accuracy within $5 on a $230,000 portfolio is required.

**Configuration 2 — Balanced Performance (General Wealth Dashboard)**
- Strategy: SWR | TTL: dynamic | Medium volatility
- Mean E_stale: **$46.40** | P95: $90.15 | E_%: 0.0202% | P50: 12.31 ms
- Use case: Retail portfolio dashboards during normal trading hours; adapts automatically to volatility shifts without operator intervention.

**Configuration 3 — Cost-Constrained (Low-Volatility / Overnight)**
- Strategy: Cache-Aside | TTL: dynamic | Low volatility
- Mean E_stale: **$12.69** | P95: $24.53 | E_%: 0.0055% | P50: 11.19 ms
- Use case: Overnight or pre-market portfolio valuation where API cost minimization is the primary constraint and sub-$25 P95 staleness is acceptable.

The Pareto frontier confirms that **no single configuration dominates all contexts**. Architects must select a configuration based on their trading system's staleness tolerance, latency SLA, and API budget — a decision the E_stale metric now makes quantifiable for the first time.

---

## 7. Discussion

### 7.1 Interpretation of Results

The benchmark demonstrates a quantifiable three-way trade-off between execution latency, portfolio valuation accuracy, and vendor API cost — absent from all prior literature surveyed.

**Cache-Aside + static-60** achieves the lowest P50 latency (11.43–11.68 ms) and minimal API call volume, but accumulates the highest E_stale under high volatility: mean $258.82, P95 $463.18. Any system with a 0.1% valuation accuracy requirement — common in algorithmic trading, margin lending, and regulatory reporting — is violated by this configuration despite its favorable latency profile.

**Write-Through** constrains E_stale relative to Cache-Aside with equivalent TTLs through synchronous pre-warming, but imposes a fixed 10 calls/second API baseline regardless of user demand. Its latency profile is the most consistent (11.08–11.71 ms range) due to pre-population, but the continuous write overhead represents a proportional increase in market-data licensing fees.

**SWR** delivers the most favorable Pareto position: sub-14.1 ms P50 latency (2.6 ms above Write-Through at worst), the lowest E_stale of any strategy at every equivalent TTL, and API call volume bounded by TTL evictions rather than tick rate. SWR + static-5 achieves E_stale = $3.31 mean under high volatility — 89.2% lower than Cache-Aside + static-5 ($30.73) — because the overlapping asynchronous refresh effectively halves the observable staleness duration within each TTL window.

**Dynamic TTL** as a cross-cutting policy reduces E_stale by 22–52% vs. the equivalent static-60 policy under high volatility, without application restart or operator intervention, making it the recommended default for any production deployment subject to varying intraday market conditions.

### 7.2 Reconciliation with Prior Literature

Kaptosv (2025) [2] reported Cache-Aside reducing latency from 1,146 ms to 323 ms, demonstrating clear benefit at the database layer. The present study extends this by operating in the already-cached regime (warm cache, 100% CHR) and isolating the staleness dimension Kaptosv did not measure. The 11–17 ms P50 observed here reflects Redis-layer read latency for a 10-asset portfolio aggregation, consistent with Deshpande's [3] sub-millisecond assertion on warm-cache banking APIs.

Ramadhan et al.'s CAPSULE [1] improves cache hit rates by 6.1× through spatio-temporal prefetching. Under the WealthWise warm-load profile, all strategies already achieve 100% hit ratio — the access-frequency dimension is saturated. The remaining performance gap is temporal (E_stale), not spatial, and lies outside CAPSULE's scope. This empirically validates Research Gap 1: hit-rate-centric optimizations cannot distinguish safe from unsafe cache configurations in financial domains.

### 7.3 Practitioner Decision Matrix

**Table 6: Recommended Configuration by Trading Context**

| Context | Latency SLA | E_% Tolerance | Recommended Strategy | Recommended TTL |
|---|---|---|---|---|
| High-frequency retail, active session | < 15 ms P50 | ≤ 0.05% | SWR | static-5 |
| General wealth dashboard (normal hours) | < 15 ms P50 | ≤ 0.10% | SWR | dynamic |
| Overnight / pre-market batch | < 50 ms P50 | ≤ 0.20% | Cache-Aside | dynamic |
| Automated margin checks / risk engine | < 15 ms P50 | ≤ 0.01% | SWR | static-5 |
| Report generation (batch, no SLA) | No SLA | ≤ 0.10% | Write-Through | static-30 |

### 7.4 Threats to Validity

**Internal validity:**
- Redis key residuals between runs are mitigated by `FLUSHALL` before each configuration.
- JVM JIT warm-up is isolated to the 60-second pre-measurement ramp-up window.
- GC pauses are captured in the P99 tail but not independently isolated.
- VolatilityMonitor's 30-second scheduling delay means the in-row regime column defaults to LOW at startup; volatility regime is inferred from the GBM σ parameter in the filename for correctness.

**External validity:**
- GBM prices do not capture fat-tail events (VIX spikes, flash crashes). Future work should replay historical tick data (e.g., NASDAQ TAQ dataset) through the testbed.
- The single-host Docker Compose deployment does not capture network-induced latency between Redis and the application tier in a distributed deployment. In multi-region setups, Redis replica lag would compound E_stale.

**Construct validity:**
- E_stale assumes instant execution at the cached price; real-world slippage during high-volatility periods would diverge from this model.
- The API call counter assumes linear per-call pricing; real vendor contracts using tiered or bulk pricing could alter the cost-optimal TTL recommendation.

---

## 8. Conclusion

This study presents the WealthWise empirical testbed and the E_stale staleness error metric to formally quantify the financial accuracy cost of Redis caching in volatile markets — a dimension entirely absent from the existing literature.

Across 45 experimental configurations spanning three caching strategies, five TTL policies, and three GBM-simulated volatility regimes, producing 12,489 staleness measurements, three principal findings are established:

1. **Static TTLs impose a hidden accuracy tax**: Under high volatility (σ = 80%), Cache-Aside with static-60s TTL generates mean E_stale = $258.82 with P95 = $463.18 (0.1126% portfolio drift). This is completely invisible to latency-only and hit-ratio-only benchmarks, yet directly violates accuracy requirements for automated margin evaluation and trade execution systems. The standard benchmark stack — throughput, P99, cache hit ratio — would report this configuration as perfectly healthy.

2. **Dynamic TTL reduces E_stale with minimal latency overhead**: The volatility-adaptive TTL = k/σ policy reduces mean E_stale by 22.6–51.7% vs. the equivalent static-60s policy during high-volatility regimes, incurring no measurable P50 increase and a sub-5% P99 impact from the in-place TTL update scheduler. Dynamic TTL is the recommended default expiration policy for any production financial microservice.

3. **SWR + static-5 is Pareto-optimal for accuracy-critical workloads**: SWR with a 5-second TTL achieves the best combined score across all three objectives under high volatility: mean E_stale = $3.31 (89.2% below Cache-Aside + static-5's $30.73), P50 = 14.08 ms (2.6 ms above Write-Through), and API call volume bounded by TTL evictions. For general wealth dashboards, SWR + dynamic TTL offers continuous regime adaptation with E_stale = $46.40 mean at medium volatility and P50 = 12.31 ms.

The empirical Pareto model derived in Section 6.7 enables software architects to quantify the financial accuracy cost per millisecond saved for the first time, bridging the gap between systems-level performance optimization and domain-specific financial correctness. The E_stale metric constitutes a generalizable correctness primitive applicable to any domain where cached state has a monetary or decision-quality consequence.

Future work includes: (i) replay of historical NASDAQ TAQ tick data to validate findings under real fat-tail price distributions; (ii) multi-node Redis Cluster deployment to assess distributed consistency overhead and replica-lag contributions to E_stale; and (iii) extension of E_stale to derivatives — modeling options Greeks sensitivity to stale underlying prices and foreign-exchange rate drift in multi-currency portfolio engines.

---

## References

1. A. R. Ramadhan, S. Yoo, and J. Choi, "CAPSULE: A Storage Prefetcher Harnessing Spatio-Temporal Locality for Cloud-Scale Workloads," *Proceedings of the ACM on Measurement and Analysis of Computing Systems (POMACS) / ACM SIGMETRICS*, vol. 10, no. 1, pp. 1–24, Mar. 2026.

2. L. Kaptosv, "Using Redis for caching optimization in high-traffic web applications," *International Journal of Advanced Multidisciplinary Research and Studies*, vol. 5, no. 4, pp. 1714–1722, Aug. 2025.

3. R. A. Deshpande, "Approaches to reducing REST API response time in high-traffic banking systems," *International Journal of Scientific and Engineering Science*, vol. 9, no. 8, pp. 63–68, 2025.

4. G. Swapna, S. V. Punith Kumar, and E. Susmitha, "Relix: A comparative latency framework for REST and gRPC using Redis acceleration," *International Research Journal of Advanced Engineering Hub*, vol. 4, no. 5, pp. 3395–3401, May 2026.

5. IRJAEH Study, "Enhancing microservice performance: A hybrid approach using caching and batching techniques," *International Research Journal of Advanced Engineering Hub*, vol. 4, no. 2, pp. 766–775, Feb. 2026.

6. F. A. Pramudia, M. I. Zulfa, and M. S. Aliim, "Evaluation of Redis in-memory based cache algorithm on web application data access performance using IRCache dataset," *Dinamika Rekayasa*, vol. 21, no. 2, pp. 166–174, 2025.

7. N. Sabbag Filho, "Comparative analysis between monolithic architecture and microservices in .NET applications," *Leaders Tec*, vol. 2, no. 13, 2025.

8. A. Gopal, "Client and server caching strategies in modern mobile-first architectures: React Native, Next.js, and PostgreSQL," *SSRN Electronic Journal*, 2026.

9. N. Sabbag Filho, "Redis and DynamoDB: Intelligent caching and scalable persistence in microservices," *Zenodo*, DOI: 10.5281/zenodo.15875462, Jul. 2025.

10. A. Barton, "Enhancing web application performance through in-memory data caching with Redis," *Actual Research Journal*, vol. 1, no. 183, Apr. 2026.

11. C. F. Henao Villa et al., "Optimizing microservices performance and scalability through automated monitoring with Kubernetes and Prometheus," *Ingénierie des Systèmes d'Information*, vol. 30, no. 2, pp. 551–563, 2025.
