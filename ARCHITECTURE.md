# Architecture — `bots`

**Hexagonal architecture (ports & adapters)** on top of **Clean Architecture**.
Two kinds of package:

- **`shared/`** — the cross-cutting, technical pieces every bot uses (market-data domain
  kernel + data infrastructure + REST helpers). It belongs to no single bot.
- **`bots/<bot>/`** — one **vertical slice per bot**: all of that bot's business code lives here.

## Structure

```
com.algofuture
├── shared/                                 ← SHARED (defined once, not per slice)
│   ├── marketdata/domain/model/            Market kernel: TradesBar1m, UnsweptLevel,
│   │                                       SessionHighLow, TimeframeBucket, FootprintLevel, SizeCount
│   └── infrastructure/
│   ├── journal/domain/model/               StrategyTrade (one strategy_trade row)
│       ├── persistence/
│       │   ├── MarketDataRepository             Native SQL over trade_1m_live (+ instrument tick size)
│       │   ├── StrategyTradeRepository          strategy catalog + idempotent strategy_trade upserts
│       │   ├── FundedAccountRepository          funded_account: prop-firm accounts live alerts size for
│       │   ├── LiveTradeRepository              live_trade: every live signal per account (also the alert state)
│       │   └── entity/Trade1mLive(+Id)          JPA table mapping (boots the persistence unit)
│       └── rest/CorsHelper                      Cross-cutting REST helper
│
└── bots/                                   ← one vertical slice per bot
    └── liquiditysweep/                     Liquidity sweeps (stop runs of prior highs/lows)
        ├── domain/model/                   KeyLevel, LiquiditySweep, SweepTrade, ContractSpec, SweepDetectorConfig, ...
        ├── domain/service/                 KeyLevelCalculator (ONH/ONL, PDH/PDL, PWH/PWL, H1 in 2.1, opening range ORH/ORL in 3.1), LiquiditySweepDetector,
        │                                   SweepTradeSimulator, SmtConfirmation, SessionSelector, RangeTarget (3.2),
        │                                   SwingLevels + ConvictionSelector (4.0: one trade per day by conviction),
        │                                   FootprintReasons (4.1: thin extreme in the excursion footprint) (pure rules)
        ├── application/port/in|out/        RunLiquiditySweepBacktestUseCase, LiquiditySweepBarDataPort,
        │                                   TradeJournalPort
        ├── application/service/            RunLiquiditySweepBacktestService
        └── infrastructure/                 LiquiditySweepBarDataAdapter, LiquiditySweepJournalAdapter,
                                            LiquiditySweepResource
```

## The principle: the port is the boundary

The question was "should infrastructure be per slice, or shared up to a point?".
The dividing line is the **outbound port**:

- **Shared** (technical infrastructure): DB connection/entities/queries in
  `MarketDataRepository`, the market value objects (`marketdata/domain/model`) as a
  *shared kernel*, and cross-cutting helpers (`CorsHelper`). Written once, reused.
- **Per slice** (business): domain, use case, the REST resource, **the `port/out` interface
  THIS bot needs**, and the **thin adapter** that implements it by delegating to the shared
  repository (anti-corruption layer).

### Why this respects Clean Architecture

- Dependencies point inward: `infrastructure → application → domain`.
- `shared` **knows no bot**. It is the slice's adapter (e.g. `<Bot>BarDataAdapter`) that depends on
  both the shared repository **and** its own port (dependency inversion). Hence no bot↔bot
  coupling.
- Each bot declares in its `BarDataPort` **only** the queries it uses (ISP), instead of
  inheriting a fat repository. The *implementation* is shared; the *contract* is not.

### Data flow (per bot)

`<Bot>Resource` → inbound port (use case) → `BarDataPort`
→ `<Bot>BarDataAdapter` → `MarketDataRepository` (shared) → DB.

## Adding another bot that reads from the DB

1. Create `bots/<other>/` with its `domain` / `application` / `infrastructure` layers.
2. Define its `port/out` with the queries it needs.
3. Implement it with a thin adapter delegating to `MarketDataRepository`
   (adding to that shared repository only the new queries that are missing).

No DB infrastructure is duplicated: it lives once, in `shared`.

## Data access (self-contained)

The project queries the market database (Postgres/Supabase) directly, without depending on
the source service:

- Dependencies: `quarkus-hibernate-orm-panache` + `quarkus-jdbc-postgresql`.
- Datasource in `application.properties` (`quarkus.datasource.*`); credentials ONLY via
  `JDBC_URL` / `DB_USERNAME` / `DB_PASSWORD` (no defaults: ECS task secrets in AWS, a git-ignored
  `.env` locally). The app never generates or alters the schema
  (`quarkus.hibernate-orm.database.generation=none`); tables of its own are created by the scripts in
  `db/migrations`. Writes: journal rows in `strategy` / `strategy_trade`, and live signals in `live_trade`.
- HTTP is internal only (`quarkus.http.host=127.0.0.1`, port 8080): the REST endpoints (backtests, journal,
  alert status) are not reachable from outside the container/host.
- Application routes live under `quarkus.http.root-path=/bots` (same convention as the data service's
  `/data-service`); `non-application-root-path=/q` is absolute, so health stays at the standard `/q/health`.
- Health (SmallRye Health), also internal: `/q/health/live` (the alert loop ran in the last minutes of the
  session; the ECS container health check runs `curl -fs http://127.0.0.1:8080/q/health/live` inside the
  container) and `/q/health/ready` (database, Telegram configured, market data fresh in session).
- `MarketDataRepository` implements the **5 real queries** (native SQL over `trade_1m_live`):
  1m bars with footprint, unswept levels, pre-NY and previous-day high/low, and HTF buckets.
  The `Trade1mLive` entity exists only to boot the persistence unit.
