package com.wealthwise.service;

import com.wealthwise.config.WealthWiseProperties;
import com.wealthwise.simulator.GBMPriceSimulator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stale-While-Revalidate: serve cached values immediately, refresh asynchronously
 * once the remaining TTL drops below the stale-duration threshold.
 */
@Service
@Profile("swr")
@Slf4j
public class StaleWhileRevalidatePriceService implements PriceService {

    private static final String CACHE_PREFIX = "prices::";

    private final GBMPriceSimulator simulator;
    private final RedisTemplate<String, BigDecimal> redisTemplate;
    private final WealthWiseProperties props;
    private final Counter apiCallCounter;
    private final Counter swrRefreshCounter;
    private final Map<String, Boolean> refreshingNow = new ConcurrentHashMap<>();

    public StaleWhileRevalidatePriceService(GBMPriceSimulator simulator,
                                             RedisTemplate<String, BigDecimal> redisTemplate,
                                             WealthWiseProperties props,
                                             MeterRegistry meterRegistry) {
        this.simulator = simulator;
        this.redisTemplate = redisTemplate;
        this.props = props;
        this.apiCallCounter = Counter.builder("wealthwise.api.calls")
                .tag("strategy", "swr").register(meterRegistry);
        this.swrRefreshCounter = Counter.builder("wealthwise.swr.refreshes")
                .register(meterRegistry);
    }

    @Override
    public BigDecimal getCachedPrice(String ticker) {
        String key = CACHE_PREFIX + ticker.toUpperCase();
        BigDecimal cached = redisTemplate.opsForValue().get(key);

        if (cached != null) {
            Long ttlMs = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
            long staleMs = props.getCaching().getSwrStaleDurationSeconds() * 1000L;
            if (ttlMs != null && ttlMs > 0 && ttlMs < staleMs && !refreshingNow.containsKey(ticker)) {
                refreshingNow.put(ticker, Boolean.TRUE);
                asyncRefresh(ticker, key);
            }
            return cached;
        }

        // Full miss — synchronous fetch
        apiCallCounter.increment();
        log.debug("[SWR MISS] Synchronous fetch for {}", ticker);
        BigDecimal price = simulator.getLivePrice(ticker);
        redisTemplate.opsForValue().set(key, price, resolveTtl());
        return price;
    }

    @Override
    public BigDecimal getLivePrice(String ticker) {
        return simulator.getLivePrice(ticker);
    }

    @Async
    public void asyncRefresh(String ticker, String key) {
        try {
            swrRefreshCounter.increment();
            apiCallCounter.increment();
            log.debug("[SWR REFRESH] Background refresh for {}", ticker);
            BigDecimal freshPrice = simulator.getLivePrice(ticker);
            redisTemplate.opsForValue().set(key, freshPrice, resolveTtl());
        } finally {
            refreshingNow.remove(ticker);
        }
    }

    private Duration resolveTtl() {
        return switch (props.getCaching().getTtlPolicy()) {
            case "static-5"  -> Duration.ofSeconds(5);
            case "static-60" -> Duration.ofSeconds(60);
            default          -> Duration.ofSeconds(30);
        };
    }
}
