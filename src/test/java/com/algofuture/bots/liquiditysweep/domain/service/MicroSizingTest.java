package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.service.MicroSizing.Size;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MicroSizingTest {

    @Test
    void typicalNqStop_oneMnqEvenAboveTheBudget() {
        // 78 points × $2 = $156 > $125 budget, but under the $250 max: still one micro
        assertEquals(Optional.of(new Size("MNQ", "Micro Nasdaq", 1, 156, 2)), MicroSizing.of("NQ", 78, 125, 250));
    }

    @Test
    void tightStop_fitsSeveralMicros() {
        // ES 18 points × $5 = $90 → 2 MES for a $200 budget
        assertEquals(Optional.of(new Size("MES", "Micro S&P 500", 2, 180, 10)), MicroSizing.of("ES", 18, 200, 250));
    }

    @Test
    void oneMicroAboveTheMax_isSkipped() {
        assertEquals(Optional.empty(), MicroSizing.of("NQ", 175, 125, 250));   // $350
        assertEquals(new Size("MNQ", "Micro Nasdaq", 1, 350, 2), MicroSizing.single("NQ", 175));
    }
}
