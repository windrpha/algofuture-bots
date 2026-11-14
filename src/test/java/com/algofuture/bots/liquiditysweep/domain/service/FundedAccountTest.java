package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.FundedAccount;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FundedAccountTest {

    @Test
    void riskIsTwentyPercentOfTheRemainingRoom() {
        FundedAccount nys = new FundedAccount("nys", "NYS", "25k", 1250, 612.9, 500, 0.20, null, true);
        assertEquals(637.1, nys.room(), 1e-9);
        assertEquals(127.42, nys.maxRisk(), 1e-9);
        assertEquals("NYS · 25k", nys.label());
    }

    @Test
    void neverAboveTheDailyLimit_andAHardCapWins() {
        assertEquals(300, new FundedAccount("a", "F", "big", 3000, 0, 300, 0.20, null, true).maxRisk());
        assertEquals(90, new FundedAccount("a", "F", "capped", 1250, 0, 500, 0.20, 90.0, true).maxRisk());
    }

    @Test
    void roomNeverNegative_andBadFractionRejected() {
        assertEquals(0, new FundedAccount("a", "F", "blown", 1250, 1300, 500, 0.20, null, true).maxRisk());
        assertThrows(IllegalArgumentException.class, () -> new FundedAccount("a", "F", "x", 1250, 0, 500, 1.5, null, true));
    }
}
