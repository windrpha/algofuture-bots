package com.algofuture.bots.liquiditysweep.application.service;

import com.algofuture.bots.liquiditysweep.application.port.in.RunDailyBacktestUseCase;
import com.algofuture.bots.liquiditysweep.domain.model.BacktestSummary;
import com.algofuture.bots.liquiditysweep.domain.model.ConvictionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.DailyBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SweepBacktest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyBacktestJournalJobTest {

    private record Call(OffsetDateTime from, OffsetDateTime to, ConvictionConfig conviction, boolean journal) {}

    private final List<Call> calls = new ArrayList<>();
    private final RunDailyBacktestUseCase backtest = (symbols, from, to, interval, config, conviction, journal) -> {
        calls.add(new Call(from, to, conviction, journal));
        return new DailyBacktest("run", symbols, interval, config, conviction, BacktestSummary.of(List.of(), List.of()),
                List.of(), new SweepBacktest.JournalResult(20, 1, 0, 3));
    };
    private final DailyBacktestJournalJob job = new DailyBacktestJournalJob(backtest, List.of("NQ.FUT", "ES.FUT"));

    @Test
    void afterTheClose_journals42_overTheLastWeek_includingToday() {
        job.run(Instant.parse("2026-11-13T21:30:00Z"));               // Friday 15:30 CT
        Call c = calls.get(0);
        assertTrue(c.journal());
        assertSame(ConvictionConfig.WITH_STOP_BUFFER, c.conviction());
        assertEquals("liquidity sweep 4.2", c.conviction().strategyName());
        assertEquals(OffsetDateTime.parse("2026-11-06T06:00Z"), c.from());   // Nov 6 00:00 CT
        assertEquals(OffsetDateTime.parse("2026-11-14T05:59Z"), c.to());     // Nov 13 23:59 CT
    }

    @Test
    void beforeTheClose_stopsAtThePreviousDay_soNoOpenTradeIsJournaled() {
        job.run(Instant.parse("2026-11-13T17:00:00Z"));               // 11:00 CT
        assertEquals(OffsetDateTime.parse("2026-11-13T06:00Z"), calls.get(0).to());   // Nov 13 00:00 CT
    }
}
