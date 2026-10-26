package com.wealthwise.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Represents how many shares of a given {@link Asset} are held in a {@link Portfolio}.
 * This is the source of the q_i quantities used in E_stale calculations.
 */
@Entity
@Table(
    name = "holding",
    uniqueConstraints = @UniqueConstraint(columnNames = {"portfolio_id", "asset_id"})
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Holding implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    /** q_i: the quantity (shares) held for this asset. */
    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal quantity;
}
