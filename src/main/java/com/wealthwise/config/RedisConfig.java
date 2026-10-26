package com.wealthwise.config;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Redis configuration.
 *
 * <p>Configures the default TTL via the {@code wealthwise.caching.ttl-policy} property
 * at application startup. Per-request dynamic TTL updates are handled by
 * {@link com.wealthwise.service.DynamicTTLPolicy}.
 *
 * <p>The serializer uses {@link GenericToStringSerializer} for BigDecimal values to ensure
 * exact numeric precision without Jackson floating-point ClassCastExceptions.
 */
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class RedisConfig {

    private final WealthWiseProperties props;

    @Bean
    public RedisTemplate<String, BigDecimal> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, BigDecimal> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericToStringSerializer<>(BigDecimal.class));
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericToStringSerializer<>(BigDecimal.class));
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        Duration defaultTtl = resolveDefaultTtl();

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(defaultTtl)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericToStringSerializer<>(BigDecimal.class)))
                .disableCachingNullValues();

        return RedisCacheManager.builder(factory)
                .cacheDefaults(config)
                .build();
    }

    /**
     * Resolves the startup TTL from the configured TTL policy.
     * For "dynamic" policy, this sets an initial safe default (30s) that
     * DynamicTTLPolicy then overrides per-key as σ data accumulates.
     */
    private Duration resolveDefaultTtl() {
        return switch (props.getCaching().getTtlPolicy()) {
            case "static-5"  -> Duration.ofSeconds(5);
            case "static-30" -> Duration.ofSeconds(30);
            case "static-60" -> Duration.ofSeconds(60);
            case "event"     -> Duration.ofDays(1);
            default          -> Duration.ofSeconds(30);
        };
    }
}
