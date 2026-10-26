package com.wealthwise.service;

import com.wealthwise.config.WealthWiseProperties;
import com.wealthwise.simulator.GBMPriceSimulator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Cache-Aside (Lazy Loading) strategy.
 * On miss: fetch from GBM simulator, store in Redis with TTL. On hit: return from Redis.
 */
@Service
@Profile("cache-aside")
@Slf4j
public class CacheAsidePriceService implements PriceService {

    private static final String CACHE_NAME = "prices";

    private final GBMPriceSimulator simulator;
    private final WealthWiseProperties props;
    private final Counter apiCallCounter;

    public CacheAsidePriceService(GBMPriceSimulator simulator,
                                   WealthWiseProperties props,
                                   MeterRegistry meterRegistry) {
        this.simulator = simulator;
        this.props = props;
        this.apiCallCounter = Counter.builder("wealthwise.api.calls")
                .tag("strategy", "cache-aside")
                .register(meterRegistry);
    }

    @Override
    @Cacheable(value = CACHE_NAME, key = "#ticker")
    public BigDecimal getCachedPrice(String ticker) {
        apiCallCounter.increment();
        log.debug("[CACHE MISS] Fetching live price for {}", ticker);
        return simulator.getLivePrice(ticker);
    }

    @Override
    public BigDecimal getLivePrice(String ticker) {
        return simulator.getLivePrice(ticker);
    }

    @CacheEvict(value = CACHE_NAME, key = "#ticker")
    public void evict(String ticker) {
        log.debug("[EVICT] Cache evicted for {}", ticker);
    }
}
