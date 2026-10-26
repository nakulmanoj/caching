package com.wealthwise.service;

import java.math.BigDecimal;

/**
 * Common interface for all three caching strategy implementations:
 * <ul>
 *   <li>{@link CacheAsidePriceService}   — profile {@code cache-aside}</li>
 *   <li>{@link WriteThroughPriceService} — profile {@code write-through}</li>
 *   <li>{@link StaleWhileRevalidatePriceService} — profile {@code swr}</li>
 * </ul>
 *
 * <p>Both methods are called by {@link com.wealthwise.metrics.StalenessMetricsFilter}
 * to compute E_stale = |getCachedPrice(t) − getLivePrice(t)|.
 */
public interface PriceService {

    /**
     * Returns the price as served by the cache (p_i^cached(t)).
     * May return a stale value depending on the active caching strategy and TTL.
     */
    BigDecimal getCachedPrice(String ticker);

    /**
     * Returns the current live/ground-truth price (p_i^realtime(t)).
     * Always bypasses the cache. Used only for staleness measurement, not for
     * normal portfolio valuation responses.
     */
    BigDecimal getLivePrice(String ticker);
}
