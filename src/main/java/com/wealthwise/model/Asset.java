package com.wealthwise.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Represents a tradeable asset (e.g., AAPL, MSFT).
 * Prices are NOT stored here — they come from Redis cache or the GBM simulator.
 */
@Entity
@Table(name = "asset")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Asset implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 10)
    private String ticker;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String sector;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
