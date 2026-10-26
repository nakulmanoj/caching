-- =============================================================================
-- WealthWise Schema
-- =============================================================================

-- Asset master table (tickers traded)
CREATE TABLE IF NOT EXISTS asset (
    id          BIGSERIAL PRIMARY KEY,
    ticker      VARCHAR(10)    NOT NULL UNIQUE,
    name        VARCHAR(100)   NOT NULL,
    sector      VARCHAR(50),
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- Portfolio (one per user for this study)
CREATE TABLE IF NOT EXISTS portfolio (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(50)    NOT NULL UNIQUE,
    name        VARCHAR(100)   NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- Holdings: how many shares of each asset a portfolio holds
CREATE TABLE IF NOT EXISTS holding (
    id           BIGSERIAL PRIMARY KEY,
    portfolio_id BIGINT         NOT NULL REFERENCES portfolio(id),
    asset_id     BIGINT         NOT NULL REFERENCES asset(id),
    quantity     NUMERIC(18, 6) NOT NULL CHECK (quantity > 0),
    UNIQUE (portfolio_id, asset_id)
);

-- Price history (populated by the GBM simulator or live feed)
CREATE TABLE IF NOT EXISTS price_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    asset_id    BIGINT          NOT NULL REFERENCES asset(id),
    price       NUMERIC(18, 6)  NOT NULL,
    recorded_at TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_price_snapshot_asset_time ON price_snapshot(asset_id, recorded_at DESC);

-- Staleness measurements captured by StalenessMetricsFilter
CREATE TABLE IF NOT EXISTS staleness_measurement (
    id              BIGSERIAL PRIMARY KEY,
    strategy        VARCHAR(20)     NOT NULL,  -- 'cache-aside','write-through','swr'
    ttl_policy      VARCHAR(20)     NOT NULL,  -- 'static-5','static-30','static-60','dynamic','event'
    volatility_regime VARCHAR(10)   NOT NULL,  -- 'low','medium','high'
    portfolio_id    BIGINT          NOT NULL REFERENCES portfolio(id),
    v_cached        NUMERIC(18, 6)  NOT NULL,
    v_realtime      NUMERIC(18, 6)  NOT NULL,
    e_stale         NUMERIC(18, 6)  NOT NULL,  -- |V_cached - V_realtime|
    e_pct           NUMERIC(10, 6)  NOT NULL,  -- E_stale / V_realtime * 100
    request_latency_ms NUMERIC(10, 3),
    cache_hit       BOOLEAN         NOT NULL,
    measured_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_staleness_strategy_regime ON staleness_measurement(strategy, volatility_regime, measured_at);

-- =============================================================================
-- Seed data: 10 assets representing mix of high/low volatility sectors
-- =============================================================================
INSERT INTO asset (ticker, name, sector) VALUES
    ('AAPL',  'Apple Inc.',             'Technology'),
    ('MSFT',  'Microsoft Corp.',         'Technology'),
    ('JPM',   'JPMorgan Chase & Co.',    'Financials'),
    ('GS',    'Goldman Sachs Group',     'Financials'),
    ('XOM',   'Exxon Mobil Corp.',       'Energy'),
    ('TSLA',  'Tesla Inc.',              'Consumer Discretionary'),
    ('AMZN',  'Amazon.com Inc.',         'Consumer Discretionary'),
    ('JNJ',   'Johnson & Johnson',       'Healthcare'),
    ('VZ',    'Verizon Communications',  'Telecom'),
    ('GLD',   'SPDR Gold Shares ETF',    'Commodities')
ON CONFLICT (ticker) DO NOTHING;

-- Seed portfolios (10 simulated users)
INSERT INTO portfolio (user_id, name) VALUES
    ('user-001', 'Test Portfolio Alpha'),
    ('user-002', 'Test Portfolio Beta'),
    ('user-003', 'Test Portfolio Gamma'),
    ('user-004', 'Test Portfolio Delta'),
    ('user-005', 'Test Portfolio Epsilon'),
    ('user-006', 'Test Portfolio Zeta'),
    ('user-007', 'Test Portfolio Eta'),
    ('user-008', 'Test Portfolio Theta'),
    ('user-009', 'Test Portfolio Iota'),
    ('user-010', 'Test Portfolio Kappa')
ON CONFLICT (user_id) DO NOTHING;

-- Seed holdings: each user holds 100 shares of every asset
INSERT INTO holding (portfolio_id, asset_id, quantity)
SELECT p.id, a.id, 100
FROM portfolio p CROSS JOIN asset a
ON CONFLICT (portfolio_id, asset_id) DO NOTHING;
