package com.wealthwise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * WealthWise Cache Consistency Testbed
 *
 * <p>This application is the empirical testbed for the research paper:
 * "Balancing Speed and Accuracy: A Cache Consistency–Performance Study
 * for Real-Time Financial Data in WealthWise"
 *
 * <p>Run with a strategy profile:
 * <pre>
 *   java -jar wealthwise.jar --spring.profiles.active=cache-aside
 *   java -jar wealthwise.jar --spring.profiles.active=write-through
 *   java -jar wealthwise.jar --spring.profiles.active=swr
 * </pre>
 *
 * <p>Override TTL policy and volatility regime at runtime:
 * <pre>
 *   -Dwealthwise.caching.ttl-policy=dynamic
 *   -Dwealthwise.caching.strategy=cache-aside
 * </pre>
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class WealthWiseApplication {

    public static void main(String[] args) {
        SpringApplication.run(WealthWiseApplication.class, args);
    }
}
