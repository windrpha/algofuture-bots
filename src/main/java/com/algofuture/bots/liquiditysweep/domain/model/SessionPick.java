package com.algofuture.bots.liquiditysweep.domain.model;

/** The session's single trade and the contract it was taken on. */
public record SessionPick(ContractSpec contract, SweepTrade trade) {
}
