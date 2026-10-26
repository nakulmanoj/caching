package com.wealthwise.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Records a single staleness measurement captured by {@link com.wealthwise.metrics.StalenessMetricsFilter}.
 *
 * <p>Each row corresponds to one portfolio valuation request and stores:
 * <ul>
 *   <li>V_cached  — displayed portfolio value from Redis</li>
 *   <li>V_realtime — true portfolio value from live prices</li>
 *   <li>E_stale   — |V_cached − V_realtime|</li>
 *   <li>E_pct     — E_stale / V_realtime × 100</li>
 * </ul>
 *
 * <p>This table is the primary data source for all results in Section 6.
 */
@Entity
@Table(name = "staleness_measurement")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StalenessMeasurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Caching strategy label: "cache-aside", "write-through", "swr" */
    @Column(nullable = false, length = 20)
    private String strategy;

    /** TTL policy label: "static-5", "static-30", "static-60", "dynamic", "event" */
    @Column(name = "ttl_policy", nullable = false, length = 20)
    private String ttlPolicy;

    /** Volatility regime at time of measurement: "low", "medium", "high" */
    @Column(name = "volatility_regime", nullable = false, length = 10)
    private String volatilityRegime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    /** V_cached(t) = Σ q_i · p_i^cached(t) */
    @Column(name = "v_cached", nullable = false, precision = 18, scale = 6)
    private BigDecimal vCached;

    /** V_realtime(t) = Σ q_i · p_i^realtime(t) */
    @Column(name = "v_realtime", nullable = false, precision = 18, scale = 6)
    private BigDecimal vRealtime;

    /** E_stale = |V_cached − V_realtime| */
    @Column(name = "e_stale", nullable = false, precision = 18, scale = 6)
    private BigDecimal eStale;

    /** E_% = E_stale / V_realtime × 100 */
    @Column(name = "e_pct", nullable = false, precision = 10, scale = 6)
    private BigDecimal ePct;

    /** HTTP response latency for this particular request (ms) */
    @Column(name = "request_latency_ms", precision = 10, scale = 3)
    private BigDecimal requestLatencyMs;

    /** Whether Redis served this response from cache (hit) or fetched from source (miss) */
    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(name = "measured_at", nullable = false, updatable = false)
    private Instant measuredAt = Instant.now();
}
