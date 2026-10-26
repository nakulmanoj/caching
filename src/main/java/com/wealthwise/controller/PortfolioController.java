package com.wealthwise.controller;

import com.wealthwise.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * REST controller exposing the portfolio valuation endpoint.
 *
 * <p>This is the <b>primary load-test target</b> for all JMeter experiments.
 * The {@code GET /api/portfolio/{userId}/value} endpoint represents the
 * "read-heavy portfolio aggregation" use case described in Section 1.
 *
 * <p>The response body includes {@code vCached} so the
 * {@link com.wealthwise.metrics.StalenessMetricsFilter} can extract it via regex.
 */
@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService portfolioService;

    /**
     * Returns the cached portfolio valuation for a user.
     *
     * <p>This is V_cached(t) = Σ q_i · p_i^cached(t) from the paper.
     * The {@code cacheHit} field is populated by the active PriceService implementation.
     *
     * @param userId  portfolio owner identifier (e.g., "user-001")
     */
    @GetMapping("/{userId}/value")
    public ResponseEntity<PortfolioValueResponse> getPortfolioValue(@PathVariable String userId) {
        PortfolioService.PortfolioValuation valuation = portfolioService.computeCachedValue(userId);
        return ResponseEntity.ok(new PortfolioValueResponse(
                userId,
                valuation.vCached(),
                Instant.now()
        ));
    }

    /**
     * Health-check / warm-up endpoint. JMeter ramp-up calls this to pre-warm the JVM
     * before the measurement phase begins.
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        return ResponseEntity.ok(Map.of("status", "UP", "ts", Instant.now().toString()));
    }

    /** Response DTO — field name "vCached" is parsed by CachingResponseWrapper. */
    public record PortfolioValueResponse(
            String userId,
            BigDecimal vCached,
            Instant timestamp
    ) {}
}
