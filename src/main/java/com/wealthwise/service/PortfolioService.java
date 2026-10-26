package com.wealthwise.service;

import com.wealthwise.model.Holding;
import com.wealthwise.model.Portfolio;
import com.wealthwise.repository.HoldingRepository;
import com.wealthwise.repository.PortfolioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Computes portfolio valuations.
 *
 * <p>The core formula implemented here is:
 * <pre>
 *   V_cached(t)   = Σ q_i · p_i^cached(t)
 *   V_realtime(t) = Σ q_i · p_i^realtime(t)
 * </pre>
 *
 * <p>{@link #computeCachedValue(String)} is the endpoint called by JMeter load tests.
 * {@link #computeRealtimeValue(String)} is called in parallel by the
 * {@link com.wealthwise.metrics.StalenessMetricsFilter} to compute E_stale.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PortfolioService {

    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final PriceService priceService;

    /**
     * Computes V_cached(t) = Σ q_i · p_i^cached(t).
     * This is the value displayed to the user and subjected to staleness.
     */
    public PortfolioValuation computeCachedValue(String userId) {
        Portfolio portfolio = portfolioRepository.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("Portfolio not found: " + userId));

        List<Holding> holdings = holdingRepository.findByPortfolioIdWithAsset(portfolio.getId());

        BigDecimal vCached = holdings.stream()
                .map(h -> h.getQuantity().multiply(
                        priceService.getCachedPrice(h.getAsset().getTicker())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(6, RoundingMode.HALF_UP);

        return new PortfolioValuation(portfolio.getId(), userId, vCached, null);
    }

    /**
     * Computes V_realtime(t) = Σ q_i · p_i^realtime(t).
     * Called by the staleness filter — NOT exposed as a normal user-facing endpoint
     * to avoid polluting latency measurements.
     */
    public PortfolioValuation computeRealtimeValue(String userId) {
        Portfolio portfolio = portfolioRepository.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("Portfolio not found: " + userId));

        List<Holding> holdings = holdingRepository.findByPortfolioIdWithAsset(portfolio.getId());

        BigDecimal vRealtime = holdings.stream()
                .map(h -> h.getQuantity().multiply(
                        priceService.getLivePrice(h.getAsset().getTicker())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(6, RoundingMode.HALF_UP);

        return new PortfolioValuation(portfolio.getId(), userId, null, vRealtime);
    }

    /** Thin value object returned by both valuation methods. */
    public record PortfolioValuation(
            Long portfolioId,
            String userId,
            BigDecimal vCached,
            BigDecimal vRealtime
    ) {}
}
