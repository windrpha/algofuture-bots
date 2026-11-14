package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.ContractSpec;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel.Type;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import com.algofuture.bots.liquiditysweep.domain.service.SessionSelector.Candidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionSelectorTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final ContractSpec NQ = ContractSpec.of("NQ.FUT", 0.25);
    private static final ContractSpec ES = ContractSpec.of("ES.FUT", 0.25);

    private static Candidate candidate(ContractSpec c, int order, Type type, String entryUtc) {
        Instant entry = Instant.parse(entryUtc);
        LiquiditySweep sweep = new LiquiditySweep(c.sourceSymbol(), SweepDirection.BULLISH, SweepStatus.CONFIRMED, 100,
                type, entry, List.of(type.label), entry, entry, 99, 101, entry, 102.0, 99.0, 108.0);
        return new Candidate(c, order, sweep, entry);
    }

    @Test
    void firstEntryOfTheSessionWins_otherContractIgnored() {
        List<Candidate> picks = SessionSelector.onePerSession(List.of(
                candidate(ES, 1, Type.PREV_WEEK_LOW, "2026-11-11T15:20:00Z"),     // 09:20 CT, better level but later
                candidate(NQ, 0, Type.SESSION_LOW, "2026-11-11T14:55:00Z")),      // 08:55 CT
                CHICAGO);

        assertEquals(1, picks.size());
        assertEquals("NQ", picks.get(0).contract().root());
    }

    @Test
    void sameCandle_higherLevelWins_thenSymbolOrder() {
        String at = "2026-11-11T14:55:00Z";
        assertEquals("ES", SessionSelector.onePerSession(List.of(
                candidate(NQ, 0, Type.SESSION_LOW, at), candidate(ES, 1, Type.PREV_DAY_LOW, at)), CHICAGO)
                .get(0).contract().root());
        assertEquals("NQ", SessionSelector.onePerSession(List.of(
                candidate(ES, 1, Type.SESSION_LOW, at), candidate(NQ, 0, Type.SESSION_LOW, at)), CHICAGO)
                .get(0).contract().root());
    }

    @Test
    void oneTradePerDay() {
        assertEquals(2, SessionSelector.onePerSession(List.of(
                candidate(NQ, 0, Type.SESSION_LOW, "2026-11-10T14:55:00Z"),
                candidate(ES, 1, Type.SESSION_LOW, "2026-11-10T15:30:00Z"),
                candidate(ES, 1, Type.SESSION_LOW, "2026-11-11T15:00:00Z")), CHICAGO).size());
    }
}
