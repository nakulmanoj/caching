package com.wealthwise.metrics;

import com.wealthwise.config.WealthWiseProperties;
import com.wealthwise.model.StalenessMeasurement;
import com.wealthwise.repository.PortfolioRepository;
import com.wealthwise.repository.StalenessMeasurementRepository;
import com.wealthwise.service.PortfolioService;
import com.wealthwise.service.VolatilityMonitor;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts GET /api/portfolio/{userId}/value responses and asynchronously
 * computes E_stale = |V_cached − V_realtime| after the response is committed.
 * The async probe does NOT add to measured P50/P99 latency.
 */
@Component
@Slf4j
public class StalenessMetricsFilter extends OncePerRequestFilter {

    private static final Pattern PORTFOLIO_PATH =
            Pattern.compile("^/api/portfolio/([^/]+)/value$");

    private final PortfolioService portfolioService;
    private final PortfolioRepository portfolioRepository;
    private final StalenessMeasurementRepository measurementRepository;
    private final VolatilityMonitor volatilityMonitor;
    private final MetricsExporter metricsExporter;
    private final WealthWiseProperties props;
    private final DistributionSummary eStaleDistribution;

    public StalenessMetricsFilter(PortfolioService portfolioService,
                                   PortfolioRepository portfolioRepository,
                                   StalenessMeasurementRepository measurementRepository,
                                   VolatilityMonitor volatilityMonitor,
                                   MetricsExporter metricsExporter,
                                   WealthWiseProperties props,
                                   MeterRegistry meterRegistry) {
        this.portfolioService = portfolioService;
        this.portfolioRepository = portfolioRepository;
        this.measurementRepository = measurementRepository;
        this.volatilityMonitor = volatilityMonitor;
        this.metricsExporter = metricsExporter;
        this.props = props;
        this.eStaleDistribution = DistributionSummary.builder("wealthwise.e_stale")
                .description("Portfolio staleness error E_stale in USD")
                .baseUnit("USD")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        if (!props.getStaleness().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        Matcher matcher = PORTFOLIO_PATH.matcher(path);
        if (!matcher.matches()) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = matcher.group(1);
        long startNs = System.nanoTime();

        CachingResponseWrapper wrapper = new CachingResponseWrapper(response);
        filterChain.doFilter(request, wrapper);
        wrapper.copyBodyToResponse();

        long latencyMs = (System.nanoTime() - startNs) / 1_000_000;
        BigDecimal vCached = wrapper.getCachedValue();

        CompletableFuture.runAsync(() -> measureStaleness(userId, latencyMs, vCached));
    }

    private void measureStaleness(String userId, long latencyMs, BigDecimal vCached) {
        try {
            if (vCached == null) return;

            PortfolioService.PortfolioValuation rt = portfolioService.computeRealtimeValue(userId);
            BigDecimal vRealtime = rt.vRealtime();
            if (vRealtime == null || vRealtime.compareTo(BigDecimal.ZERO) == 0) return;

            BigDecimal eStale = vCached.subtract(vRealtime).abs().setScale(6, RoundingMode.HALF_UP);
            BigDecimal ePct   = eStale.divide(vRealtime, 6, RoundingMode.HALF_UP)
                                      .multiply(BigDecimal.valueOf(100));

            eStaleDistribution.record(eStale.doubleValue());

            VolatilityMonitor.Regime regime = volatilityMonitor.getDominantRegime();
            String strategy  = props.getCaching().getStrategy();
            String ttlPolicy = props.getCaching().getTtlPolicy();

            StalenessMeasurement m = StalenessMeasurement.builder()
                    .strategy(strategy)
                    .ttlPolicy(ttlPolicy)
                    .volatilityRegime(regime.name().toLowerCase())
                    .portfolio(portfolioRepository.findByUserId(userId).orElse(null))
                    .vCached(vCached)
                    .vRealtime(vRealtime)
                    .eStale(eStale)
                    .ePct(ePct)
                    .requestLatencyMs(BigDecimal.valueOf(latencyMs))
                    .cacheHit(true)
                    .measuredAt(Instant.now())
                    .build();

            if (props.getStaleness().isWriteToDb()) {
                measurementRepository.save(m);
            }
            metricsExporter.exportRow(m);

            log.debug("[E_STALE] strategy={} ttl={} regime={} E_stale=${} E%={}% latency={}ms",
                    strategy, ttlPolicy, regime, eStale, ePct, latencyMs);

        } catch (Exception e) {
            log.warn("Staleness measurement failed for userId={}: {}", userId, e.getMessage());
        }
    }
}
