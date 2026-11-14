package com.algofuture.shared.infrastructure.nativeimage;

import com.algofuture.shared.journal.domain.model.FundedAccountRecord;
import com.algofuture.shared.journal.domain.model.LiveTradeRecord;
import com.algofuture.shared.journal.domain.model.StrategyTrade;
import com.algofuture.shared.marketdata.domain.model.FootprintLevel;
import com.algofuture.shared.marketdata.domain.model.SessionHighLow;
import com.algofuture.shared.marketdata.domain.model.SizeCount;
import com.algofuture.shared.marketdata.domain.model.TimeframeBucket;
import com.algofuture.shared.marketdata.domain.model.TradesBar1m;
import com.algofuture.shared.marketdata.domain.model.UnsweptLevel;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native image: Jackson reads these reflectively (the footprint JSONB is parsed into FootprintLevel/SizeCount, the
 * rest is (de)serialized in journals and responses). Kept here so the shared kernel stays framework-free.
 */
@RegisterForReflection(targets = {
        FootprintLevel.class, SizeCount.class, TradesBar1m.class, SessionHighLow.class, TimeframeBucket.class,
        UnsweptLevel.class, StrategyTrade.class, FundedAccountRecord.class, LiveTradeRecord.class})
public class SharedReflectionConfig {
}
