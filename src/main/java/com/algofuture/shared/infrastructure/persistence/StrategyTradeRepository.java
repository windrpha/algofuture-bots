package com.algofuture.shared.infrastructure.persistence;

import com.algofuture.shared.journal.domain.model.StrategyTrade;
import com.algofuture.shared.journal.domain.model.StrategyTrade.UpsertOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.Session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Trade-journal repository SHARED by every bot: the {@code strategy} catalog and the
 * {@code strategy_trade} journal. Plain JDBC (via the Hibernate session) because most exit
 * columns are nullable and need typed NULL binds.
 *
 * A trade is identified by (strategy_id, symbol, side, entry_time): re-running a backtest over the
 * same range never duplicates rows, and a trade journaled as {@code open} is closed in place once
 * a later run sees its exit. Closed trades are never rewritten.
 */
@ApplicationScoped
public class StrategyTradeRepository {

    @Inject
    EntityManager entityManager;

    @Inject
    ObjectMapper objectMapper;

    /** Registers the strategy if missing and returns its id. */
    @Transactional
    public int ensureStrategy(String name, String description) {
        entityManager.createNativeQuery("""
                        INSERT INTO strategy (name, description) VALUES (:name, :description)
                        ON CONFLICT (name) DO NOTHING
                        """)
                .setParameter("name", name)
                .setParameter("description", description)
                .executeUpdate();
        return ((Number) entityManager
                .createNativeQuery("SELECT id FROM strategy WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).intValue();
    }

    @Transactional
    public List<UpsertOutcome> upsert(List<StrategyTrade> trades) {
        List<UpsertOutcome> outcomes = new ArrayList<>(trades.size());
        entityManager.unwrap(Session.class).doWork(conn -> {
            for (StrategyTrade t : trades) {
                outcomes.add(upsert(conn, t));
            }
        });
        return outcomes;
    }

    private UpsertOutcome upsert(Connection conn, StrategyTrade t) throws SQLException {
        try (PreparedStatement close = conn.prepareStatement("""
                UPDATE strategy_trade
                   SET exit_time = ?, exit_price = ?, exit_reason = ?, rr_realized = ?,
                       status = ?, pnl = ?, pnl_ticks = ?, metadata = CAST(? AS jsonb), updated_by = ?
                 WHERE strategy_id = ? AND symbol = ? AND side = ? AND entry_time = ?
                   AND status = 'open' AND ? <> 'open'
                """)) {
            int p = 1;
            setTimestamp(close, p++, t.exitTime());
            setDouble(close, p++, t.exitPrice());
            close.setString(p++, t.exitReason());
            close.setBigDecimal(p++, t.rrRealized());
            close.setString(p++, t.status());
            setDouble(close, p++, t.pnl());
            setInt(close, p++, t.pnlTicks());
            close.setString(p++, json(t.metadata()));
            close.setString(p++, t.author());
            p = setKey(close, p, t);
            close.setString(p, t.status());
            if (close.executeUpdate() > 0) return UpsertOutcome.UPDATED;
        }

        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO strategy_trade (
                    symbol, strategy_id, side, entry_time, entry_price, entry_reason,
                    exit_time, exit_price, exit_reason, stop_loss, take_profit, quantity,
                    rr_planned, rr_realized, status, pnl, pnl_ticks, metadata, tags,
                    created_by, updated_by)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM strategy_trade
                     WHERE strategy_id = ? AND symbol = ? AND side = ? AND entry_time = ?)
                """)) {
            int p = 1;
            insert.setString(p++, t.symbol());
            insert.setInt(p++, t.strategyId());
            insert.setString(p++, t.side());
            setTimestamp(insert, p++, t.entryTime());
            insert.setDouble(p++, t.entryPrice());
            insert.setString(p++, t.entryReason());
            setTimestamp(insert, p++, t.exitTime());
            setDouble(insert, p++, t.exitPrice());
            insert.setString(p++, t.exitReason());
            setDouble(insert, p++, t.stopLoss());
            setDouble(insert, p++, t.takeProfit());
            insert.setBigDecimal(p++, t.quantity());
            insert.setBigDecimal(p++, t.rrPlanned());
            insert.setBigDecimal(p++, t.rrRealized());
            insert.setString(p++, t.status());
            setDouble(insert, p++, t.pnl());
            setInt(insert, p++, t.pnlTicks());
            insert.setString(p++, json(t.metadata()));
            insert.setString(p++, t.tags() == null ? null : json(t.tags()));
            insert.setString(p++, t.author());
            insert.setString(p++, t.author());
            setKey(insert, p, t);
            return insert.executeUpdate() > 0 ? UpsertOutcome.INSERTED : UpsertOutcome.UNCHANGED;
        }
    }

    private int setKey(PreparedStatement ps, int p, StrategyTrade t) throws SQLException {
        ps.setInt(p++, t.strategyId());
        ps.setString(p++, t.symbol());
        ps.setString(p++, t.side());
        setTimestamp(ps, p++, t.entryTime());
        return p;
    }

    // ------------------------------------------------------------------
    // Typed NULL binds
    // ------------------------------------------------------------------

    private static void setTimestamp(PreparedStatement ps, int i, Instant v) throws SQLException {
        if (v == null) ps.setNull(i, Types.TIMESTAMP_WITH_TIMEZONE);
        else ps.setObject(i, v.atOffset(ZoneOffset.UTC));
    }

    private static void setDouble(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null) ps.setNull(i, Types.DOUBLE);
        else ps.setDouble(i, v);
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER);
        else ps.setInt(i, v);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize journal JSON", e);
        }
    }
}
