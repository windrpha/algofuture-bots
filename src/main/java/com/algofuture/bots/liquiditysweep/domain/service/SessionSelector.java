package com.algofuture.bots.liquiditysweep.domain.service;

import com.algofuture.bots.liquiditysweep.domain.model.ContractSpec;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps one signal per session: the FIRST qualifying entry of the day, decided as it happens (a later,
 * "better" signal cannot be known yet). Entries on the same candle are ranked by level (week > day >
 * overnight), then by the order the contracts were given in.
 */
public final class SessionSelector {

    /** A confirmed sweep on a contract, with its entry time and position in the requested symbol list. */
    public record Candidate(ContractSpec contract, int symbolOrder, LiquiditySweep sweep, Instant entryAt) {}

    private SessionSelector() {
    }

    public static List<Candidate> onePerSession(List<Candidate> candidates, ZoneId zone) {
        Map<LocalDate, Candidate> byDay = new LinkedHashMap<>();
        candidates.stream()
                .sorted(Comparator.comparing(Candidate::entryAt)
                        .thenComparing(c -> -c.sweep().levelType().rank)
                        .thenComparing(Candidate::symbolOrder))
                .forEach(c -> byDay.putIfAbsent(c.entryAt().atZone(zone).toLocalDate(), c));
        return new ArrayList<>(byDay.values());
    }
}
