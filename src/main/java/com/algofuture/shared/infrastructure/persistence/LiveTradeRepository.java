package com.algofuture.shared.infrastructure.persistence;

import com.algofuture.shared.journal.domain.model.LiveTradeRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.Session;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code live_trade} journal, SHARED by every bot that alerts live. Plain JDBC (via the Hibernate session) for
 * typed NULL binds. Every write is conditional so two running instances cannot both act on the same signal: the
 * insert relies on the one-per-account-per-day constraint and the updates only touch rows still in the old state;
 * each method reports whether THIS call made the change, and only that caller sends the alert.
 */
@ApplicationScoped
public class LiveTradeRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    @Inject
    EntityManager entityManager;

    @Inject
    ObjectMapper objectMapper;

    @Transactional
    public List<LiveTradeRecord> findDay(LocalDate day) {
        List<LiveTradeRecord> out = new ArrayList<>();
        entityManager.unwrap(Session.class).doWork(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id, trade_day, account_id, account_label, strategy, symbol, micro, contracts, side,
                           signal_time, signal_price, stop_loss, take_profit, entry_estimate, risk_usd, reward_usd,
                           reasons::text, status, skip_reason, exit_type, exit_time, exit_price, pnl_usd, reminder_sent
                    FROM live_trade WHERE trade_day = ? ORDER BY id
                    """)) {
                ps.setDate(1, Date.valueOf(day));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(row(rs));
                }
            }
        });
        return out;
    }

    /** Inserts the signal for one account; false when that account already has a row for the day. */
    @Transactional
    public boolean insert(LiveTradeRecord t) {
        boolean[] inserted = {false};
        entityManager.unwrap(Session.class).doWork(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO live_trade (trade_day, account_id, account_label, strategy, symbol, micro, contracts,
                        side, signal_time, signal_price, stop_loss, take_profit, entry_estimate, risk_usd, reward_usd,
                        reasons, status, skip_reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                    ON CONFLICT ON CONSTRAINT live_trade_one_per_day DO NOTHING
                    """)) {
                int p = 1;
                ps.setDate(p++, Date.valueOf(t.tradeDay()));
                ps.setString(p++, t.accountId());
                ps.setString(p++, t.accountLabel());
                ps.setString(p++, t.strategy());
                ps.setString(p++, t.symbol());
                ps.setString(p++, t.micro());
                ps.setInt(p++, t.contracts());
                ps.setString(p++, t.side());
                ps.setObject(p++, t.signalTime().atOffset(ZoneOffset.UTC));
                ps.setDouble(p++, t.signalPrice());
                ps.setDouble(p++, t.stopLoss());
                ps.setDouble(p++, t.takeProfit());
                ps.setDouble(p++, t.entryEstimate());
                ps.setDouble(p++, t.riskUsd());
                ps.setDouble(p++, t.rewardUsd());
                ps.setString(p++, json(t.reasons()));
                ps.setString(p++, t.status());
                ps.setString(p, t.skipReason());
                inserted[0] = ps.executeUpdate() > 0;
            }
        });
        return inserted[0];
    }

    /** Closes an open row; false when it was already closed (by this or another instance). */
    @Transactional
    public boolean close(long id, String exitType, Instant exitTime, double exitPrice, double pnlUsd) {
        return entityManager.createNativeQuery("""
                        UPDATE live_trade
                           SET status = 'closed', exit_type = :exitType, exit_time = :exitTime, exit_price = :exitPrice,
                               pnl_usd = :pnl, updated_at = now()
                         WHERE id = :id AND status = 'open'
                        """)
                .setParameter("exitType", exitType)
                .setParameter("exitTime", exitTime.atOffset(ZoneOffset.UTC))
                .setParameter("exitPrice", exitPrice)
                .setParameter("pnl", pnlUsd)
                .setParameter("id", id)
                .executeUpdate() > 0;
    }

    /** Flags the close reminder as sent; returns how many rows THIS call flagged. */
    @Transactional
    public int markReminderSent(List<Long> ids) {
        if (ids.isEmpty()) return 0;
        return entityManager.createNativeQuery("""
                        UPDATE live_trade SET reminder_sent = true, updated_at = now()
                         WHERE id IN (:ids) AND NOT reminder_sent AND status = 'open'
                        """)
                .setParameter("ids", ids)
                .executeUpdate();
    }

    private LiveTradeRecord row(ResultSet rs) throws SQLException {
        return new LiveTradeRecord(rs.getLong("id"), rs.getDate("trade_day").toLocalDate(), rs.getString("account_id"),
                rs.getString("account_label"), rs.getString("strategy"), rs.getString("symbol"), rs.getString("micro"),
                rs.getInt("contracts"), rs.getString("side"), instant(rs, "signal_time"), rs.getDouble("signal_price"),
                rs.getDouble("stop_loss"), rs.getDouble("take_profit"), rs.getDouble("entry_estimate"),
                rs.getDouble("risk_usd"), rs.getDouble("reward_usd"), parse(rs.getString("reasons")),
                rs.getString("status"), rs.getString("skip_reason"), rs.getString("exit_type"),
                instant(rs, "exit_time"), nullableDouble(rs, "exit_price"), nullableDouble(rs, "pnl_usd"),
                rs.getBoolean("reminder_sent"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize reasons", e);
        }
    }

    private List<String> parse(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, STRINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse reasons", e);
        }
    }
}
