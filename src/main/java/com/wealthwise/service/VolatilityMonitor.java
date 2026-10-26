package com.wealthwise.service;

import com.wealthwise.config.WealthWiseProperties;
import com.wealthwise.simulator.GBMPriceSimulator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Monitors rolling per-ticker volatility and classifies the current regime:
 *   LOW    σ < lowThreshold   (%/min)
 *   MEDIUM lowThreshold ≤ σ < highThreshold
 *   HIGH   σ ≥ highThreshold
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VolatilityMonitor {

    public enum Regime { LOW, MEDIUM, HIGH }

    private final GBMPriceSimulator simulator;
    private final WealthWiseProperties props;

    private final Map<String, Regime> currentRegimes = new ConcurrentHashMap<>();
    private final Map<String, Double> currentSigmas  = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 30_000)
    public void updateRegimes() {
        simulator.getAllLivePrices().keySet().forEach(ticker -> {
            double sigmaPerMin = simulator.getSigmaPerMinute(ticker);
            currentSigmas.put(ticker, sigmaPerMin);
            currentRegimes.put(ticker, classifyRegime(sigmaPerMin));
        });
        log.debug("Volatility regimes updated: {}", currentRegimes);
    }

    public Regime getRegime(String ticker) {
        return currentRegimes.getOrDefault(ticker.toUpperCase(), Regime.MEDIUM);
    }

    public Regime getDominantRegime() {
        if (currentRegimes.isEmpty()) return Regime.MEDIUM;
        return currentRegimes.values().stream()
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Regime.MEDIUM);
    }

    public double getSigmaPerMinute(String ticker) {
        return currentSigmas.getOrDefault(ticker.toUpperCase(), 1.0);
    }

    private Regime classifyRegime(double sigmaPerMin) {
        double low  = props.getVolatility().getLowThreshold();
        double high = props.getVolatility().getHighThreshold();
        if (sigmaPerMin < low)  return Regime.LOW;
        if (sigmaPerMin >= high) return Regime.HIGH;
        return Regime.MEDIUM;
    }
}
