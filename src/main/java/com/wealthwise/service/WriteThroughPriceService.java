package com.wealthwise.service;

import com.wealthwise.simulator.GBMPriceSimulator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Write-Through strategy — @CachePut on every GBM tick ensures near-zero E_stale.
 */
@Service
@Profile("write-through")
@Slf4j
public class WriteThroughPriceService implements PriceService {

    private static final String CACHE_NAME = "prices";

    private final GBMPriceSimulator simulator;
    private final Counter apiCallCounter;

    public WriteThroughPriceService(GBMPriceSimulator simulator,
                                     MeterRegistry meterRegistry) {
        this.simulator = simulator;
        this.apiCallCounter = Counter.builder("wealthwise.api.calls")
                .tag("strategy", "write-through")
                .register(meterRegistry);
    }

    @Override
    @Cacheable(value = CACHE_NAME, key = "#ticker")
    public BigDecimal getCachedPrice(String ticker) {
        apiCallCounter.increment();
        log.debug("[CACHE COLD MISS] {}", ticker);
        return simulator.getLivePrice(ticker);
    }

    @CachePut(value = CACHE_NAME, key = "#ticker")
    public BigDecimal writeThroughUpdate(String ticker, BigDecimal price) {
        apiCallCounter.increment();
        log.debug("[WRITE-THROUGH] {} → {}", ticker, price);
        return price;
    }

    @Override
    public BigDecimal getLivePrice(String ticker) {
        return simulator.getLivePrice(ticker);
    }
}
