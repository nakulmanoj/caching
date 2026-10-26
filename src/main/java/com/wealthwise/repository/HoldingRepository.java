package com.wealthwise.repository;

import com.wealthwise.model.Holding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HoldingRepository extends JpaRepository<Holding, Long> {

    /**
     * Returns all holdings for a given portfolio, eagerly fetching the linked Asset.
     * Used by PortfolioService to iterate over (q_i, ticker) pairs.
     */
    @Query("SELECT h FROM Holding h JOIN FETCH h.asset WHERE h.portfolio.id = :portfolioId")
    List<Holding> findByPortfolioIdWithAsset(Long portfolioId);
}
