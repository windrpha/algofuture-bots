package com.algofuture.bots.liquiditysweep.infrastructure.nativeimage;

import com.algofuture.bots.liquiditysweep.domain.model.BacktestSummary;
import com.algofuture.bots.liquiditysweep.domain.model.ContractSpec;
import com.algofuture.bots.liquiditysweep.domain.model.ConvictionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.DailyBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.DailyPick;
import com.algofuture.bots.liquiditysweep.domain.model.ExitType;
import com.algofuture.bots.liquiditysweep.domain.model.FootprintRules;
import com.algofuture.bots.liquiditysweep.domain.model.FundedAccount;
import com.algofuture.bots.liquiditysweep.domain.model.KeyLevel;
import com.algofuture.bots.liquiditysweep.domain.model.LiquiditySweep;
import com.algofuture.bots.liquiditysweep.domain.model.SessionBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SessionConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SessionPick;
import com.algofuture.bots.liquiditysweep.domain.model.SweepBacktest;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDetectorConfig;
import com.algofuture.bots.liquiditysweep.domain.model.SweepDirection;
import com.algofuture.bots.liquiditysweep.domain.model.SweepStatus;
import com.algofuture.bots.liquiditysweep.domain.model.SweepTrade;
import com.algofuture.bots.liquiditysweep.domain.model.TradeResult;
import com.algofuture.bots.liquiditysweep.domain.service.ConvictionSelector;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native image: the REST resources return {@code Response} objects, so Quarkus cannot infer which types Jackson will
 * serialize; these are every model that appears in a backtest response. Kept here so the domain stays framework-free.
 */
@RegisterForReflection(targets = {
        SweepBacktest.class, SweepBacktest.JournalResult.class, SessionBacktest.class, SessionConfig.class,
        SessionPick.class, DailyBacktest.class, DailyPick.class, ConvictionConfig.class, FootprintRules.class,
        BacktestSummary.class, ContractSpec.class, SweepDetectorConfig.class, LiquiditySweep.class, SweepTrade.class,
        KeyLevel.class, KeyLevel.Type.class, KeyLevel.Kind.class, SweepDirection.class, SweepStatus.class,
        ExitType.class, TradeResult.class, FundedAccount.class, ConvictionSelector.Reason.class})
public class LiquiditySweepReflectionConfig {
}
