-- Funded (prop-firm) accounts the live alerts size for, and every live signal sent per account.
-- Run once against the market database; the app never creates or alters tables.

CREATE TABLE IF NOT EXISTS funded_account (
    id                  VARCHAR(40)   PRIMARY KEY,                -- short id used in logs: 'nys-25k-1'
    firm                VARCHAR(40)   NOT NULL,                   -- 'NYS'
    name                VARCHAR(100)  NOT NULL,                   -- 'The Thunder 25k'
    account_number      VARCHAR(60),                              -- as the firm shows it
    size_usd            NUMERIC(12,2),                            -- nominal account size
    max_loss            NUMERIC(12,2) NOT NULL CHECK (max_loss > 0),   -- firm's max loss (trailing drawdown)
    max_loss_used       NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (max_loss_used >= 0),  -- dashboard "Max Loss" used part
    daily_loss          NUMERIC(12,2) NOT NULL DEFAULT 0,         -- daily loss / pause limit (0 = none)
    risk_fraction       NUMERIC(4,3)  NOT NULL DEFAULT 0.200
                        CHECK (risk_fraction > 0 AND risk_fraction <= 1),         -- share of the room one trade may risk
    max_risk_per_trade  NUMERIC(10,2),                            -- optional hard cap overriding the fraction
    enabled             BOOLEAN       NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS live_trade (
    id              BIGSERIAL        PRIMARY KEY,
    trade_day       DATE             NOT NULL,                    -- session date (America/Chicago)
    account_id      VARCHAR(40)      NOT NULL REFERENCES funded_account (id),
    account_label   VARCHAR(160)     NOT NULL,                    -- 'NYS · The Thunder 25k' at alert time
    strategy        VARCHAR(40)      NOT NULL,                    -- 'liquidity sweep 4.2'
    symbol          VARCHAR(10)      NOT NULL,                    -- index root: 'NQ'
    micro           VARCHAR(10)      NOT NULL,                    -- 'MNQ'
    contracts       INTEGER          NOT NULL CHECK (contracts >= 0),   -- 0 when skipped
    side            VARCHAR(5)       NOT NULL CHECK (side IN ('long', 'short')),
    signal_time     TIMESTAMPTZ      NOT NULL,                    -- entry candle close
    signal_price    DOUBLE PRECISION NOT NULL,
    stop_loss       DOUBLE PRECISION NOT NULL,
    take_profit     DOUBLE PRECISION NOT NULL,
    entry_estimate  DOUBLE PRECISION NOT NULL,                    -- latest price when the alert went out
    risk_usd        NUMERIC(10,2)    NOT NULL,                    -- loss at the stop from entry_estimate
    reward_usd      NUMERIC(10,2)    NOT NULL,                    -- gain at the target from entry_estimate
    reasons         JSONB            NOT NULL DEFAULT '[]',
    status          VARCHAR(10)      NOT NULL CHECK (status IN ('open', 'skipped', 'closed')),
    skip_reason     TEXT,
    exit_type       VARCHAR(20),                                  -- 'sl' / 'tp' / 'session_close'
    exit_time       TIMESTAMPTZ,
    exit_price      DOUBLE PRECISION,
    pnl_usd         NUMERIC(10,2),                                -- before commissions, from entry_estimate
    reminder_sent   BOOLEAN          NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    -- one signal per account per day: also what stops a second running instance from alerting twice
    CONSTRAINT live_trade_one_per_day UNIQUE (trade_day, account_id)
);

CREATE INDEX IF NOT EXISTS idx_live_trade_day ON live_trade (trade_day);

-- The account funded on 2026-11-13 (dashboard at that date: max loss $612.90 used of $1,250, daily pause $500).
INSERT INTO funded_account (id, firm, name, account_number, size_usd, max_loss, max_loss_used, daily_loss)
VALUES ('nys-25k-1', 'NYS', 'The Thunder 25k', 'NYS212857-288423', 25000, 1250, 612.90, 500)
ON CONFLICT (id) DO NOTHING;
