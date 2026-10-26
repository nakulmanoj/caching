package com.wealthwise.service;

import com.wealthwise.config.WealthWiseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic TTL policy: TTL = k / σ
 * Scheduler re-applies Redis key expiries every 30 seconds so
 * the cache adapts to volatility changes without requiring a miss cycle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicTTLPolicy {

    private static final List<String> TICKERS =
            List.of("AAPL","MSFT","JPM","GS","XOM","TSLA","AMZN","JNJ","VZ","GLD");

    private final VolatilityMonitor volatilityMonitor;
    private final StringRedisTemplate redisTemplate;
    private final WealthWiseProperties props;

    private final Map<String, Duration> currentTtls = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 30_000)
    public void recomputeTtls() {
        String policy = props.getCaching().getTtlPolicy();
        if (!"dynamic".equals(policy)) return;   // no-op for static policies

        for (String ticker : TICKERS) {
            Duration ttl = computeTtl(ticker);
            currentTtls.put(ticker, ttl);
            String cacheKey = "prices::" + ticker;
            Boolean exists = redisTemplate.hasKey(cacheKey);
            if (Boolean.TRUE.equals(exists)) {
                redisTemplate.expire(cacheKey, ttl);
                log.debug("Dynamic TTL updated: {} → {}s", ticker, ttl.toSeconds());
            }
        }
    }

    public Duration getTtlForTicker(String ticker) {
        return currentTtls.computeIfAbsent(ticker.toUpperCase(), this::computeTtl);
    }

    public Duration resolve(String ticker, String ttlPolicy) {
        return switch (ttlPolicy) {
            case "static-5"  -> Duration.ofSeconds(5);
            case "static-30" -> Duration.ofSeconds(30);
            case "static-60" -> Duration.ofSeconds(60);
            case "dynamic"   -> getTtlForTicker(ticker);
            case "event"     -> Duration.ofDays(1);
            default          -> Duration.ofSeconds(30);
        };
    }

    private Duration computeTtl(String ticker) {
        double sigma = volatilityMonitor.getSigmaPerMinute(ticker);
        if (sigma <= 0) sigma = 0.001;
        long k   = Math.round(props.getDynamicTtl().getKConstant() / sigma);
        long min = props.getDynamicTtl().getMinTtlSeconds();
        long max = props.getDynamicTtl().getMaxTtlSeconds();
        return Duration.ofSeconds(Math.max(min, Math.min(max, k)));
    }
}
