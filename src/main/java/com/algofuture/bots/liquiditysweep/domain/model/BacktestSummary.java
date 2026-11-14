package com.algofuture.bots.liquiditysweep.domain.model;

import java.util.List;

public record BacktestSummary(int sweeps, int confirmed, int trades, int wins, int losses, int open,
                              double winRate, double totalPnl, int totalPnlTicks, double totalR) {

    public static BacktestSummary of(List<LiquiditySweep> sweeps, List<SweepTrade> trades) {
        int wins = 0, losses = 0, open = 0, ticks = 0;
        double pnl = 0, r = 0;
        for (SweepTrade t : trades) {
            switch (t.result()) {
                case WIN -> wins++;
                case LOSS -> losses++;
                case OPEN -> open++;
                default -> { }
            }
            if (t.pnl() != null) {
                pnl += t.pnl();
                ticks += t.pnlTicks();
                r += t.rrRealized();
            }
        }
        int closed = wins + losses;
        long confirmed = sweeps.stream().filter(s -> s.status() == SweepStatus.CONFIRMED).count();
        return new BacktestSummary(sweeps.size(), (int) confirmed, trades.size(), wins, losses, open,
                closed == 0 ? 0 : (double) wins / closed, pnl, ticks, r);
    }
}
