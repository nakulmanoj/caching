package com.wealthwise.repository;

import com.wealthwise.model.StalenessMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StalenessMeasurementRepository extends JpaRepository<StalenessMeasurement, Long> {

    List<StalenessMeasurement> findByStrategyAndTtlPolicyAndVolatilityRegime(
            String strategy, String ttlPolicy, String volatilityRegime);

    @Query("""
            SELECT s.strategy, s.ttlPolicy, s.volatilityRegime,
                   AVG(s.eStale)              AS avgEStale,
                   AVG(s.ePct)               AS avgEPct,
                   AVG(s.requestLatencyMs)   AS avgLatencyMs,
                   COUNT(s)                  AS sampleCount,
                   SUM(CASE WHEN s.cacheHit THEN 1 ELSE 0 END) * 1.0 / COUNT(s) AS hitRatio
            FROM StalenessMeasurement s
            GROUP BY s.strategy, s.ttlPolicy, s.volatilityRegime
            ORDER BY s.strategy, s.ttlPolicy, s.volatilityRegime
           """)
    List<Object[]> aggregateResultsSummary();
}
