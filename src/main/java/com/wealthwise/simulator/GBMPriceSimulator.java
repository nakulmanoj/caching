package com.wealthwise.simulator;

import com.wealthwise.config.WealthWiseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates real-time asset prices using Geometric Brownian Motion:
 *   S(t + Δt) = S(t) · exp((μ − σ²/2)·Δt + σ·√Δt·Z),  Z ~ N(0,1)
 *
 * Activated when wealthwise.simulator.enabled=true (default).
 * σ is read from WealthWiseProperties, overridable per experiment run via
 *   --wealthwise.simulator.sigma=0.80  (HIGH volatility regime)
 */
@Component
@ConditionalOnProperty(name = "wealthwise.simulator.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class GBMPriceSimulator {

    private static final double MU = 0.0;
    // Δt ≈ 1 second in trading-year units
    private static final double DT = 1.0 / (252.0 * 6.5 * 3600.0);

    private final WealthWiseProperties props;

    // Fixed seed for reproducibility across experiment runs
    private final Random random = new Random(42);

    /** Current live prices — ground truth p_i^realtime(t). */
    private final Map<String, Double> livePrices = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        Map<String, Double> base = props.getSimulator().getBasePrices();
        if (base.isEmpty()) {
            // Fallback defaults if YAML not bound
            livePrices.put("AAPL", 195.0);  livePrices.put("MSFT", 415.0);
            livePrices.put("JPM",  210.0);  livePrices.put("GS",   490.0);
            livePrices.put("XOM",  115.0);  livePrices.put("TSLA", 250.0);
            livePrices.put("AMZN", 195.0);  livePrices.put("JNJ",  155.0);
            livePrices.put("VZ",    42.0);  livePrices.put("GLD",  230.0);
        } else {
            livePrices.putAll(base);
        }
        log.info("GBMPriceSimulator initialised: σ={} tickers={}", props.getSimulator().getSigma(), livePrices.keySet());
    }

    /** Advances all prices by one GBM step every tick (default 1 second). */
    @Scheduled(fixedRateString = "${wealthwise.simulator.tick-interval-ms:1000}")
    public void tick() {
        double sigma = props.getSimulator().getSigma();
        livePrices.replaceAll((ticker, price) -> {
            double z = random.nextGaussian();
            double newPrice = price * Math.exp((MU - 0.5 * sigma * sigma) * DT
                    + sigma * Math.sqrt(DT) * z);
            return Math.max(newPrice, 0.01);
        });
    }

    /** Returns p_i^realtime(t) — bypasses cache, always live. */
    public BigDecimal getLivePrice(String ticker) {
        Double price = livePrices.get(ticker.toUpperCase());
        if (price == null) {
            // Return a reasonable default rather than crashing
            log.warn("Unknown ticker: {}. Using default price 100.0", ticker);
            return BigDecimal.valueOf(100.0);
        }
        return BigDecimal.valueOf(price);
    }

    /** σ (%/min) — used by VolatilityMonitor for regime classification. */
    public double getSigmaPerMinute(String ticker) {
        double sigma = props.getSimulator().getSigma();
        return sigma * Math.sqrt(1.0 / (252.0 * 6.5 * 60.0)) * 100.0;
    }

    public Map<String, Double> getAllLivePrices() {
        return Map.copyOf(livePrices);
    }
}
