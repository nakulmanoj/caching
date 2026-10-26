package com.wealthwise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed configuration properties for the WealthWise application.
 * Replaces fragile {@code @Value("#{${...}}")} SpEL expressions for Map injection.
 */
@Component
@ConfigurationProperties(prefix = "wealthwise")
public class WealthWiseProperties {

    private final Simulator simulator = new Simulator();
    private final Volatility volatility = new Volatility();
    private final DynamicTtl dynamicTtl = new DynamicTtl();
    private final Staleness staleness = new Staleness();
    private final Caching caching = new Caching();

    public Simulator getSimulator() { return simulator; }
    public Volatility getVolatility() { return volatility; }
    public DynamicTtl getDynamicTtl() { return dynamicTtl; }
    public Staleness getStaleness() { return staleness; }
    public Caching getCaching() { return caching; }

    public static class Simulator {
        private boolean enabled = true;
        private long tickIntervalMs = 1000;
        private double sigma = 0.30;
        private Map<String, Double> basePrices = new LinkedHashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getTickIntervalMs() { return tickIntervalMs; }
        public void setTickIntervalMs(long tickIntervalMs) { this.tickIntervalMs = tickIntervalMs; }
        public double getSigma() { return sigma; }
        public void setSigma(double sigma) { this.sigma = sigma; }
        public Map<String, Double> getBasePrices() { return basePrices; }
        public void setBasePrices(Map<String, Double> basePrices) { this.basePrices = basePrices; }
    }

    public static class Volatility {
        private double lowThreshold = 0.5;
        private double highThreshold = 2.0;

        public double getLowThreshold() { return lowThreshold; }
        public void setLowThreshold(double lowThreshold) { this.lowThreshold = lowThreshold; }
        public double getHighThreshold() { return highThreshold; }
        public void setHighThreshold(double highThreshold) { this.highThreshold = highThreshold; }
    }

    public static class DynamicTtl {
        private double kConstant = 5.0;
        private long minTtlSeconds = 2;
        private long maxTtlSeconds = 120;

        public double getKConstant() { return kConstant; }
        public void setKConstant(double kConstant) { this.kConstant = kConstant; }
        public long getMinTtlSeconds() { return minTtlSeconds; }
        public void setMinTtlSeconds(long minTtlSeconds) { this.minTtlSeconds = minTtlSeconds; }
        public long getMaxTtlSeconds() { return maxTtlSeconds; }
        public void setMaxTtlSeconds(long maxTtlSeconds) { this.maxTtlSeconds = maxTtlSeconds; }
    }

    public static class Staleness {
        private boolean enabled = true;
        private boolean writeToDb = true;
        private boolean logToCsv = true;
        private String csvOutputPath = "./results/staleness.csv";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isWriteToDb() { return writeToDb; }
        public void setWriteToDb(boolean writeToDb) { this.writeToDb = writeToDb; }
        public boolean isLogToCsv() { return logToCsv; }
        public void setLogToCsv(boolean logToCsv) { this.logToCsv = logToCsv; }
        public String getCsvOutputPath() { return csvOutputPath; }
        public void setCsvOutputPath(String csvOutputPath) { this.csvOutputPath = csvOutputPath; }
    }

    public static class Caching {
        private String strategy = "cache-aside";
        private String ttlPolicy = "static-30";
        private long swrStaleDurationSeconds = 10;

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public String getTtlPolicy() { return ttlPolicy; }
        public void setTtlPolicy(String ttlPolicy) { this.ttlPolicy = ttlPolicy; }
        public long getSwrStaleDurationSeconds() { return swrStaleDurationSeconds; }
        public void setSwrStaleDurationSeconds(long swrStaleDurationSeconds) {
            this.swrStaleDurationSeconds = swrStaleDurationSeconds;
        }
    }
}
