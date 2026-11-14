package com.algofuture.bots.liquiditysweep.domain.model;

import com.algofuture.bots.liquiditysweep.domain.service.ConvictionSelector.Reason;

import java.util.List;

/** The day's single trade (strategy 4.0), the contract it was taken on and the reasons it qualified. */
public record DailyPick(ContractSpec contract, SweepTrade trade, List<Reason> reasons) {
}
