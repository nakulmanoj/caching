package com.wealthwise.metrics;

import com.wealthwise.config.WealthWiseProperties;
import com.wealthwise.model.StalenessMeasurement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;

/**
 * Appends staleness measurements to results/staleness.csv for offline analysis.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MetricsExporter {

    private static final String HEADER =
            "timestamp,strategy,ttl_policy,volatility_regime," +
            "v_cached,v_realtime,e_stale,e_pct,latency_ms,cache_hit\n";

    private final WealthWiseProperties props;

    private PrintWriter csvWriter;

    @PostConstruct
    public void init() {
        if (!props.getStaleness().isLogToCsv()) return;
        try {
            Path path = Path.of(props.getStaleness().getCsvOutputPath());
            Files.createDirectories(path.getParent());
            boolean newFile = !Files.exists(path);
            csvWriter = new PrintWriter(new FileWriter(path.toFile(), true));
            if (newFile) { csvWriter.print(HEADER); csvWriter.flush(); }
            log.info("MetricsExporter writing to {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to open CSV: {}", e.getMessage());
        }
    }

    public synchronized void exportRow(StalenessMeasurement m) {
        if (!props.getStaleness().isLogToCsv() || csvWriter == null) return;
        csvWriter.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%b%n",
                m.getMeasuredAt(), m.getStrategy(), m.getTtlPolicy(),
                m.getVolatilityRegime(), m.getVCached(), m.getVRealtime(),
                m.getEStale(), m.getEPct(), m.getRequestLatencyMs(), m.isCacheHit());
        csvWriter.flush();
    }
}
