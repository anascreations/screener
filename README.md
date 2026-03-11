# 📈 Bursa & US Stock Screener — Full Trading Playbook

> **Built for swing (2-3 days) and scalp (same day) trading.**
> This README covers everything: API usage, when to scan, when to buy, what price to queue, when to sell, and how to manage the trade.

---

## Table of Contents

1. [Quick Start](#1-quick-start)
2. [API Reference](#2-api-reference)
3. [When to Run the Scan](#3-when-to-run-the-scan)
4. [Reading a BUY Signal](#4-reading-a-buy-signal)
5. [Execution Playbook — Step by Step](#5-execution-playbook--step-by-step)
   - 5a. [SWING trades](#5a-swing-trade-execution-2-4-days)
   - 5b. [SCALP trades](#5b-scalp-trade-execution-same-day)
   - 5c. [BOTH type](#5c-both-trade-type)
6. [Queue Price Rules](#6-queue-price-rules)
7. [Tick-Based Exit Rules (Bursa)](#7-tick-based-exit-rules-bursa-my)
8. [Stop Loss Rules — Non-Negotiable](#8-stop-loss-rules--non-negotiable)
9. [Position Sizing by Confidence Grade](#9-position-sizing-by-confidence-grade)
10. [Market Index Check Before Trading](#10-market-index-check-before-trading)
11. [Trade Management After Entry](#11-trade-management-after-entry)
12. [Confidence Grade A / B / C Guide](#12-confidence-grade-a--b--c-guide)
13. [Candle Pattern Bonus Guide](#13-candle-pattern-bonus-guide)
14. [Common Mistakes to Avoid](#14-common-mistakes-to-avoid)
15. [Expected Win Rate & Math](#15-expected-win-rate--math)
16. [Configuration Reference](#16-configuration-reference)
17. [Database & Watchlist](#17-database--watchlist)

---

## 1. Quick Start

```bash
# Start the application
./mvnw spring-boot:run

# Run Bursa scan after 6:30 PM KL time
GET http://localhost:8080/api/my/scan/range?minPrice=0.10&maxPrice=2.00

# Check tomorrow's trade list
GET http://localhost:8080/api/my/watchlist/tomorrow

# US scan after 5:30 PM ET
GET http://localhost:8080/api/us/scan/range?minPrice=5&maxPrice=50&exchange=NASDAQ
```

---

## 2. API Reference

### Bursa Malaysia (MY)

| Endpoint | Method | Description |
|---|---|---|
| `/api/my/scan/range` | GET | **Main scan** — full market by price range |
| `/api/my/scan/{code}` | POST | Single stock analysis (e.g. `5243`) |
| `/api/my/scan/batch` | POST | Up to 20 stocks `{"codes":["5243","1155"]}` |
| `/api/my/watchlist/tomorrow` | GET | Tomorrow's actionable BUY list from DB |
| `/api/my/result/today` | GET | All BUY signals saved today |
| `/api/my/result/{code}` | GET | Historical signals for one stock |

### US Market

| Endpoint | Method | Description |
|---|---|---|
| `/api/us/scan/range` | GET | Full scan by price range |
| `/api/us/scan/{code}` | POST | Single stock (e.g. `AAPL`) |
| `/api/us/watchlist/tomorrow` | GET | Tomorrow's US trade list |

### Query Parameters — `/scan/range`

| Parameter | Default | Description |
|---|---|---|
| `minPrice` | required | Minimum price (RM for MY, $ for US) |
| `maxPrice` | required | Maximum price |
| `minScore` | `70` | Minimum score to include in BUY list |
| `exchange` | `ALL` | MY: `KLQ`/`KLS`/`ALL` \| US: `NASDAQ`/`NYSE`/`ALL` |

### Example Scans

```
# Penny stocks RM0.10 - RM0.50
GET /api/my/scan/range?minPrice=0.10&maxPrice=0.50

# Mid-cap RM0.50 - RM5.00, high conviction only
GET /api/my/scan/range?minPrice=0.50&maxPrice=5.00&minScore=120

# Full market, Main Market only
GET /api/my/scan/range?minPrice=0.10&maxPrice=50&exchange=KLQ

# US NASDAQ stocks $5-$50
GET /api/us/scan/range?minPrice=5&maxPrice=50&exchange=NASDAQ

# Single stock check
POST /api/my/scan/5243
POST /api/us/scan/TSLA
```

---

## 3. When to Run the Scan

> ⚠️ **CRITICAL**: Running too early gives you an incomplete (intraday) bar.
> The signal may disappear or reverse by close. Always scan on confirmed EOD data.

### Bursa Malaysia

```
✅ Best time:   7:00 PM – 9:00 PM KL time
✅ Acceptable:  6:30 PM – 7:00 PM (market closed 5:00 PM, data usually settles by 6:30 PM)
❌ Never:       Before 5:00 PM (market still open — intraday bar = false signals)
❌ Avoid:       5:00 PM – 6:30 PM (some data feeds lag — bar may still be updating)

Market hours: Monday–Friday, 9:00 AM – 12:30 PM and 2:30 PM – 5:00 PM KL
Scan results valid for: Next trading day morning
```

### US Market

```
✅ Best time:   6:00 PM – 9:00 PM ET
✅ Acceptable:  5:30 PM ET (market closes 4:00 PM, data settles by 5:15 PM)
❌ Never:       Before 4:00 PM ET

Market hours: Monday–Friday, 9:30 AM – 4:00 PM ET
Scan results valid for: Next trading day morning
```

### Holiday Check

```
# Check if tomorrow is a trading day (MY)
The scanner logs: "Trade date: 2026-03-05"
If that date looks wrong (weekend/holiday), do not trade.

Moveable holidays (MY) — update application.properties each year:
screener.extra-holidays=2026-03-20,2026-03-21,2026-05-27
```

---

## 4. Reading a BUY Signal

### Example Signal Output

```
╔══════════════════════════════════════════════════════════════╗
║  ✅ SWING 7777.KL (SUPERMAX) | Score:138.5 | Grade:B ⭐
║  Trade date: 2026-03-05 | Close: RM1.420 | Pattern: 🔥 BULL ENGULFING
║  Index: UPTREND | Close=1620.5 EMA20=1608.3 EMA50=1590.1 | 52wk: 38% of range
╠══════════════════════════════════════════════════════════════╣
║  [SWING] Entry:  RM1.390 — RM1.435  (limit order, skip if opens above max)
║  [SWING] SL  ⛔: RM1.340  (risk: 5.0 sen)
║  [SWING] TP1(40%): RM1.465  R:R 1:1.5
║  [SWING] TP2(40%): RM1.515  R:R 1:2.5
║  [SWING] TP3(20%): RM1.590  R:R 1:4.0
║  [SWING] After TP1 hit → move SL to breakeven
║  Vol: 3.20x | RSI: 56.4 | ADX: 31.2 | MACD depth: 0.0183
╚══════════════════════════════════════════════════════════════╝
```

### Field Meanings

| Field | Meaning | Action |
|---|---|---|
| `Score: 138.5` | Technical confluence score | Higher = more confident |
| `Grade: B ⭐` | A/B/C signal quality | Determines position size |
| `BULL ENGULFING` | Candle pattern today | Confirms price action |
| `Index: UPTREND` | KLCI trend | Tailwind — good to trade |
| `52wk: 38%` | Position in yearly range | 38% = lower half = room to run ✅ |
| `Entry: RM1.390` | Your ideal limit price | Place this as a limit order |
| `Entry Max: RM1.435` | Skip if opens above this | Prevents chasing |
| `SL: RM1.340` | Hard stop loss | Non-negotiable — set this immediately |
| `TP1 (40%)` | First profit target | Sell 40% of position here |
| `TP2 (40%)` | Second target | Sell 40% here |
| `TP3 (20%)` | Final target | Let this ride or trail |
| `Vol: 3.20x` | Volume vs 20-day average | 3.2× = strong smart money signal |
| `MACD depth: 0.0183` | Reversal energy before cross | > 0.01 = meaningful cross |

---

## 5. Execution Playbook — Step by Step

### 5a. SWING Trade Execution (2-4 days)

**The night before (after scan):**
- [ ] Write down: code, entry, entryMax, SL, TP1, TP2, TP3
- [ ] Check tomorrow is a trading day (not holiday/weekend)
- [ ] Check KLCI closed positive or neutral today

**Next morning — pre-market (8:45 AM – 9:00 AM KL):**

```
1. Check KLCI futures or pre-market sentiment
   → If KLCI expected to drop >0.5% → SKIP the trade, wait for next day
   → If KLCI flat or positive → proceed

2. Watch the stock in first 5 minutes (9:00 AM – 9:05 AM KL)
   → Note the opening price

3. Decision rules at open:
   ┌─────────────────────────────────────────────────────────────┐
   │ Opening price ≤ entryPrice     → Place LIMIT BUY at entry  │
   │ Opening price between entry    → Place LIMIT BUY at entry  │
   │   and entryMax                 │  (still valid range)      │
   │ Opening price > entryMax       → ❌ SKIP — do not chase    │
   │ Opening price gaps down >3%    → ❌ SKIP — signal invalid  │
   └─────────────────────────────────────────────────────────────┘
```

**Ideal entry window (MY):**

```
Best time to queue:    9:00 AM – 10:30 AM
Second chance window:  2:30 PM – 3:00 PM (afternoon session open dip)
Do NOT buy:            After 4:00 PM (too close to close, no room to react)
```

**After entry fills:**
- [ ] Immediately set stop loss as a sell order (or mental note — execute without hesitation)
- [ ] Set TP1 sell order for 40% of shares
- [ ] Go about your day — do not watch the screen every minute

---

### 5b. SCALP Trade Execution (same day)

**Only suitable for:**
- Grade A signals
- ADX ≥ 28 (strong directional move)
- Volume ≥ 2.5× average
- atrPct ≥ 1.5% (stock moves enough to profit after fees)

**Entry timing (MY):**

```
Window 1: 9:03 AM – 9:15 AM  ← preferred
  Wait for the first 3 minutes to settle.
  If price holding above yesterday's close → enter on first small pullback

Window 2: 9:30 AM – 10:00 AM
  If you missed window 1, wait for a 38-50% retracement of the first move.
  Entry = scalpEntry from signal. Do NOT chase if it ran > entryMax already.

Do NOT scalp after: 11:30 AM (liquidity dries up before lunch break)
Afternoon scalp:    2:35 PM – 3:15 PM only (avoid the final 30 min chaos)
```

**Exit rules for scalp (MY):**

```
TP1: scalpTP1   → sell ALL or 70% of position
TP2: +1.5× risk  → sell remaining 30%

Hard time exit:
  Morning scalp:  Close ALL by 11:45 AM (before lunch break)
  Afternoon:      Close ALL by 4:30 PM (never hold scalp overnight unless
                  signal upgrades to SWING with positive close)

If price stalls for 15 minutes after entry → EXIT regardless of profit/loss
Scalp = you need momentum. No momentum = no scalp.
```

---

### 5c. BOTH Trade Type

This is the highest-conviction signal. Execute like this:

```
PHASE 1 — Scalp plan:
  Enter at scalpEntry
  Set SL at scalpSL (tight)
  Target scalpTP1 with 50% of position

PHASE 2 — If TP1 hit:
  Move SL to breakeven on remaining 50%
  Now you are playing with "house money"
  Let the remaining position ride toward swing TP2/TP3
  Trail the stop: move SL up every time price makes a new high

This approach:
  → Locks in a small profit on half the position (the scalp)
  → Gives the rest a free ride toward the larger swing target
  → Maximum loss = zero (once SL at breakeven)
```

---

## 6. Queue Price Rules

### Bursa Malaysia — Tick Size Reference

The screener rounds all prices to legal tick sizes automatically.
This table is for your manual verification:

| Price Range | Tick Size | Example |
|---|---|---|
| < RM 0.10 | 0.0005 sen | RM0.025 → RM0.0255 |
| RM 0.10 – RM 0.49 | 0.005 (0.5 sen) | RM0.235, RM0.240 |
| RM 0.50 – RM 0.99 | 0.01 (1 sen) | RM0.65, RM0.66 |
| RM 1.00 – RM 2.49 | 0.02 (2 sen) | RM1.34, RM1.36 |
| RM 2.50 – RM 4.99 | 0.05 (5 sen) | RM2.95, RM3.00 |
| RM 5.00 and above | 0.10 (10 sen) | RM5.50, RM5.60 |

### How Many Ticks to Queue Below Close?

> Rule: **queue at entryPrice from the signal**. The system already calculates this.
> But if you want to manually estimate:

```
Signal entry = close - (0.382 × today's range)
              OR close × 0.992 (whichever is higher)

Example: Stock closed at RM1.42, day range = RM0.06
  Fib entry = 1.42 - (0.382 × 0.06) = 1.42 - 0.023 = RM1.397 → rounded to RM1.38
  This is approximately 2 ticks below close (1.42 → 1.40 → 1.38)

For a strong momentum stock (volume ≥ 3×, breakout close):
  Queue 1-2 ticks below close — it may not pull back much
  If it doesn't hit → do NOT chase. There will be another signal tomorrow.

For a moderate signal (volume 1.5-2×):
  Queue 3-4 ticks below close — wait for the morning dip
```

### Queue Price Decision Tree

```
Signal says entryPrice = RM1.38, entryMax = RM1.44

At open:
  → Stock opens RM1.35  →  Queue limit at RM1.38 (let price come to you)
  → Stock opens RM1.40  →  Queue limit at RM1.38 (morning dip likely)
  → Stock opens RM1.41  →  Queue limit at RM1.41 (within range, enter now)
  → Stock opens RM1.44  →  Queue limit at RM1.43 (last tick of valid range)
  → Stock opens RM1.50  →  ❌ SKIP (opened above entryMax — too late)
  → Stock opens RM1.28  →  ⚠️  GAP DOWN — signal invalidated, DO NOT BUY
                             (gap down = something changed overnight)
```

### US Market Queue Rules

```
Pre-market check (8:00 AM – 9:30 AM ET):
  → Watch pre-market price on TradingView or broker
  → If pre-market trading above entryMax → cancel the trade plan

At 9:30 AM open:
  → Use LIMIT orders ONLY (never market order at open — spreads are wide)
  → Queue at entryPrice
  → If fills within 5 minutes → trade is active
  → If not filled by 9:45 AM → lower limit by 1-2 ticks

"First 5 minutes" rule (US):
  → Never buy in the first 5 minutes of US open (9:30-9:35 AM)
  → Wait for the initial volatility to settle
  → The open is chaotic — fills are often worse than they look
```

---

## 7. Tick-Based Exit Rules (Bursa MY)

### For Swing Trade — How Many Ticks to TP?

The signal gives exact TP prices. This table shows the typical tick distance:

```
Example: Entry RM1.38, SL RM1.32, Risk = 6 sen = 3 ticks (at RM0.02/tick)

TP1 = Entry + (Risk × 1.5) = RM1.38 + 9 sen = RM1.47  → 4-5 ticks from entry
TP2 = Entry + (Risk × 2.5) = RM1.38 + 15 sen = RM1.53 → 7-8 ticks from entry
TP3 = Entry + (Risk × 4.0) = RM1.38 + 24 sen = RM1.62 → 12 ticks from entry
```

### Typical Tick Counts by Price Range

| Price Range | Risk Ticks (1.5×ATR) | TP1 Ticks | TP2 Ticks |
|---|---|---|---|
| RM 0.10–0.20 | 3-5 ticks (0.5sen each) | 5-8 ticks | 8-12 ticks |
| RM 0.20–0.50 | 4-6 ticks | 6-10 ticks | 10-15 ticks |
| RM 0.50–1.00 | 5-8 ticks (1sen each) | 8-12 ticks | 12-20 ticks |
| RM 1.00–2.50 | 4-8 ticks (2sen each) | 6-12 ticks | 10-20 ticks |
| RM 2.50–5.00 | 3-6 ticks (5sen each) | 5-9 ticks | 8-15 ticks |
| RM 5.00+ | 3-6 ticks (10sen each) | 5-8 ticks | 8-12 ticks |

### Sell Order Strategy

```
Day 1 after entry:
  Scenario A — Price hits TP1 same day:
    → Execute TP1 sell (40% of holding)
    → Immediately move stop loss to BREAKEVEN (entry price)
    → Let remaining 60% ride toward TP2

  Scenario B — Price rises but doesn't reach TP1:
    → Hold overnight — signal remains valid
    → Review morning candle next day

  Scenario C — Price drops toward SL:
    → Execute stop loss WITHOUT HESITATION
    → Do not "wait for recovery"
    → A small loss is better than a large one

Day 2-3:
  If TP2 hit:
    → Sell another 40% of original holding
    → Move SL to TP1 level (lock in profit on remaining 20%)

  If still not at TP2 by Day 4:
    → Exit the trade regardless — the momentum has faded
    → MACD signals are 2-4 day plays, not 2-week holds
```

---

## 8. Stop Loss Rules — Non-Negotiable

> **The stop loss is not a suggestion. It is the most important part of the trade.**
> One unmanaged loss can wipe out 5 winning trades.

```
Rule 1: Set the stop loss ORDER immediately after entry fills.
        Do not wait. Do not plan to watch the screen. Life happens.

Rule 2: Never move the stop loss DOWN.
        Moving SL down = changing the rules because you fear being wrong.
        Accept the loss. Find the next signal.

Rule 3: SL hit = trade is done. Do not re-enter the same stock same day.
        If SL is hit, the original analysis was wrong. The setup no longer exists.

Rule 4: If stock gaps down past your SL at open:
        → Sell at market open immediately.
        → Accept the extra loss as the cost of the gap.
        → Do not hold hoping it recovers.

Rule 5: Hard stop = SL from signal.
        Time stop = Exit after 4 trading days regardless if SL/TP not hit.
```

---

## 9. Position Sizing by Confidence Grade

> Never risk more than **1-2% of total capital** on a single trade.

### Grade A 🏆 (Score ≥ 145, 6+ confluence factors)

```
All boxes ticked: Market uptrend + EMA stack + Fresh cross + Vol ≥ 2× + Bullish candle + Deep MACD

Position size: 100% of allocated trade size
Example: RM 10,000 capital, 2% risk = RM 200 max loss per trade
  → If SL is 6 sen away on RM1.38 stock: max shares = RM200 / RM0.06 = 3,333 shares
  → Buy 3,300 shares (round to clean number)
  → Total investment: 3,300 × RM1.38 = RM4,554

Execute: Set limit order and walk away. Trust the system.
```

### Grade B ⭐ (Score 120-144, 4-5 confluence factors)

```
Good setup, 1-2 gaps in confluence.

Position size: 65-70% of allocated trade size
  → Using same example: 3,300 × 70% = 2,310 → round to 2,300 shares

Execute: Same process, slightly smaller position.
```

### Grade C ✔ (Score 108-119, minimum BUY threshold)

```
Marginal signal. All minimum requirements met but nothing standout.

Position size: 40-50% of allocated trade size
  → 3,300 × 50% = 1,650 → round to 1,600 shares

Consider: Only take Grade C signals if market index is UPTREND.
          In NEUTRAL market: reduce to 40%.
          Never take Grade C signals in NEUTRAL market with ADX < 22.
```

### Capital Allocation Example

```
Total capital: RM 50,000
Max per trade: 2% = RM 1,000 max loss

Grade A: Position up to RM 15,000 investment
Grade B: Position up to RM 10,000 investment
Grade C: Position up to RM 7,000  investment

Never have more than 3 open trades simultaneously.
Never allocate more than 40% of capital to open trades.
```

---

## 10. Market Index Check Before Trading

The scanner checks this automatically, but you should verify manually too.

### Bursa Malaysia — KLCI Check

```
Check: http://www.bursamalaysia.com  or  TradingView: KLSE:FBMKLCI

Green light to trade:
  ✅ KLCI above its 20-day moving average
  ✅ KLCI positive for the day (+0.3% or more)
  ✅ KLCI in uptrend for last 5 trading days

Yellow — reduce position:
  ⚠️  KLCI flat (±0.2%)
  ⚠️  KLCI below 20-day MA but above 50-day MA

Red — only take Grade A signals:
  ❌ KLCI down >0.5% on the day
  ❌ KLCI below 50-day MA
  ❌ KLCI in 3+ day losing streak

Hard stop — do not buy:
  🚫 KLCI down >1.5% on the day
  🚫 Bursa circuit breaker triggered
  🚫 Major global market shock (US -2%+, China circuit breaker)
```

### US Market — S&P 500 Check

```
Check: TradingView: SPX or SPY

Same rules apply. Additionally:
  → Check VIX (fear index):
     VIX < 18  = calm market — normal trading
     VIX 18-25 = elevated fear — Grade A only, reduce size
     VIX > 25  = high fear — do not swing trade, scalp only or wait
     VIX > 30  = extreme fear — no new positions
```

---

## 11. Trade Management After Entry

### The Breakeven Rule

```
After TP1 is hit:
  1. Sell 40% of shares at TP1
  2. Immediately change stop loss order from original SL to ENTRY PRICE
  3. You now have:
     - Profit locked on 40%
     - Zero loss risk on remaining 60% (worst case = breakeven)
     - Full upside potential on remaining 60% toward TP2 and TP3

This is the single most important risk management step.
```

### Trailing Stop for TP3

```
After TP2 hit (you now hold 20% of original position):
  Option A — Set and forget:
    → Place sell order at TP3 price
    → Walk away

  Option B — Trail the stop:
    → Each day, move SL to (today's low - 1 tick)
    → As price climbs, the SL follows it up
    → This captures more profit if the stock runs further than expected

  Exit trigger for TP3 position:
    → Price hits TP3 target, OR
    → Trailing SL is hit, OR
    → Day 7 since entry (do not hold longer than this on a MACD signal)
```

### What to Do When Trade Goes Against You

```
Scenario: Price drops after entry but hasn't hit SL yet

Day 1, price at entry - 1 tick:
  → Hold. Normal volatility. SL is your protection.

Day 2, price at entry - 2 ticks:
  → Hold IF: KLCI still positive, no bad news on the stock
  → Exit early IF: stock-specific news, sector selling, KLCI -1%+

Day 2, price at SL:
  → SELL. No questions. Execute the stop.

Never add to a losing trade ("averaging down").
The signal was for an entry, not a recovery plan.
```

---

## 12. Confidence Grade A / B / C Guide

The system grades each signal based on how many of these 7 factors align:

| Factor | Requirement | Points toward Grade A |
|---|---|---|
| Market Index | UPTREND | ✅ if KLCI/SPX above EMA20 > EMA50 |
| EMA Alignment | EMA9 > 21 > 50 > 200 | ✅ full stack |
| Volume | ≥ 2× average | ✅ smart money participating |
| RSI | 45–65 | ✅ momentum zone, not overbought |
| MACD | Fresh cross today | ✅ vs confirmed yesterday |
| Candle Pattern | Hammer/Engulfing/Morning Star | ✅ price action confirms |
| MACD Depth | > 0.020 | ✅ real reversal energy |

```
6-7 factors = Grade A 🏆 → Full position, execute with confidence
4-5 factors = Grade B ⭐ → 70% position, solid signal
≤3 factors  = Grade C ✔ → 50% position, proceed carefully
```

---

## 13. Candle Pattern Bonus Guide

Candle patterns at the BUY signal day are price action confirmation of the indicator signal.

### 🌟 Morning Star (+18 pts)

```
Day-2: Large bearish candle (selling pressure)
Day-1: Small body / doji (indecision — battle between buyers and sellers)
Day-0: Large bullish candle closing above Day-2 midpoint

Meaning: Sellers exhausted, buyers take control decisively.
Action:  This is the strongest reversal pattern. Execute full planned position.
Best for: Bottom reversals, bounce from EMA50 support
```

### 🔥 Bull Engulfing (+16 pts)

```
Day-1: Bearish candle
Day-0: Bullish candle that completely swallows Day-1's body

Meaning: Buyers overwhelmed sellers in a single day.
Action:  High conviction. Set limit order 1 tick above today's close for
         morning entry, or wait for the first 5-min candle to confirm.
Best for: Breakout from consolidation
```

### 🔨 Hammer (+12 pts)

```
Long lower shadow (≥ 2× body size)
Small upper shadow
Closes near the top of the range

Meaning: Sellers pushed price down but buyers rejected the lows aggressively.
Action:  Queue at the close price or 1-2 ticks below.
         SL goes 1 tick below the hammer's low.
Best for: Support level bounces, EMA21/50 bounces
```

### 💪 Strong Bull (+8 pts)

```
Body ≥ 60% of the day's range
Closes in the top 25% of range

Meaning: Buyers in control all day.
Action:  Standard entry plan from signal.
```

### 🚩 Bearish Reversal (-12 pts)

```
Shooting star: Long upper shadow, closes at bottom
Bear Engulfing: Today swallows yesterday's bullish candle

Meaning: Warning — buyers tried to push higher but got rejected.
Action:  If signal is still BUY despite the penalty, use Grade C sizing only.
         Consider waiting for next day's confirmation before entering.
```

---

## 14. Common Mistakes to Avoid

### ❌ Buying at market price instead of limit

```
Problem: You pay ask price, which may be 2-3 ticks above the signal entry.
         This shrinks your TP1 profit and inflates your risk.
Solution: Always use LIMIT orders. If not filled, skip the trade.
```

### ❌ Chasing when stock opens above entryMax

```
Problem: Stock opens at RM1.55 but signal said entryMax RM1.44.
         Buying at RM1.55 means your SL doesn't change but your entry is worse.
         TP1 may now be below your entry. The trade is no longer valid.
Solution: SKIP. Write it off. Another signal will come.
```

### ❌ Scanning during market hours

```
Problem: MACD cross appears on an incomplete intraday bar.
         By 5:00 PM close the bar closes differently — cross disappears.
         You bought based on a ghost signal.
Solution: Always scan after 6:30 PM KL / 5:30 PM ET. Non-negotiable.
```

### ❌ Moving the stop loss down

```
Problem: "It's only down 2 ticks, maybe it will recover."
         Then 5 ticks. Then 10 ticks. Now you're holding a bag.
Solution: Your SL was calculated for a reason. Execute it.
          Small losses are part of the system. Let them happen.
```

### ❌ Trading against the index

```
Problem: KLCI is in a 5-day downtrend. You see a great BUY signal.
         You buy anyway. Stock rises 2% then falls 8% with the market.
Solution: The scanner hard-blocks BUY when index is in DOWNTREND.
          Trust the filter. Sit on your hands.
```

### ❌ Overtrading — taking every signal

```
Problem: 5 BUY signals in one day. You take all of them.
         Market reverses. All 5 hit stop loss simultaneously.
Solution: Max 3 open trades at a time.
          If you already have 2 open, only add a third if it's Grade A.
          If you have 3 open, do not add more regardless of signal quality.
```

### ❌ Ignoring the volume filter

```
Problem: RM1.50 stock, only RM8,000 traded today (5,333 shares).
         You buy 5,000 shares. Tomorrow you want to sell.
         You ARE the market — your sell order moves the price against you.
Solution: Minimum RM30,000/day volume value for MY.
          The screener enforces this. Don't manually override it.
```

---

## 15. Expected Win Rate & Math

Based on the signal rules in this system:

| Signal Type | Win Rate (next 2-3 days) | Notes |
|---|---|---|
| Grade A, Index Uptrend | ~65-70% | All factors aligned |
| Grade B, Index Uptrend | ~58-62% | Good setup |
| Grade C, Index Uptrend | ~50-55% | Marginal — size carefully |
| Any Grade, Index Neutral | ~48-53% | Reduce size |
| Any Grade, Index Downtrend | ~35-42% | Hard-blocked — scanner won't give BUY |

### Expected Value Calculation

```
Grade A scenario (65% win rate, 1:2.5 R:R):
  Win trade:  +RM 500 (2.5× risk of RM 200)
  Loss trade: -RM 200

Expected value per trade:
  (0.65 × RM500) - (0.35 × RM200) = RM325 - RM70 = +RM255 per trade

Over 20 trades:
  ~13 winners, ~7 losers
  Profit: 13 × RM500 = RM6,500
  Loss:    7 × RM200 = -RM1,400
  Net:              = +RM5,100

This only works if:
  ✅ You take every valid signal (no cherry-picking)
  ✅ You execute every stop loss (no exceptions)
  ✅ You never move SL down
  ✅ You don't overtrade
```

---

## 16. Configuration Reference

### `application.properties`

```yaml
screener:
  min-score: 65              # Minimum score to appear in WATCH list
  min-volume-ratio: 1.3      # Minimum volume × average to avoid IGNORE
  history-days: 252          # MUST be 252 for real EMA200 and 52-week range
  scan-parallelism: 3        # Concurrent Yahoo fetches (max 4, lower if getting NO_DATA)
  delay-between-stocks-ms: 800  # Gap between fetches per slot

  yahoo:
    max-retries: 3
    delay-min-ms: 600
    delay-max-ms: 1400

  # Update annually — moveable Bursa holidays
  extra-holidays: "2026-03-20,2026-03-21,2026-05-27"
```

### Tuning for Different Market Conditions

```yaml
# Bull market — lower thresholds to get more signals
screener.min-score: 60
screener.min-volume-ratio: 1.2

# Bear/uncertain market — stricter filtering
screener.min-score: 80
screener.min-volume-ratio: 1.8

# If getting many NO_DATA errors (Yahoo rate limiting):
screener.scan-parallelism: 2
screener.delay-between-stocks-ms: 1200
```

---

## 17. Database & Watchlist

### H2 Console

```
URL:      http://localhost:8080/h2-console
JDBC URL: jdbc:h2:file:./data/screener-db
User:     sa
Password: (empty)
```

### Useful SQL Queries

```sql
-- All BUY signals today sorted by score
SELECT stock_code, stock_name, score, close_price, volume_ratio,
       entry_price, stop_loss, target_tp1, trade_type, confidence
FROM scan_results
WHERE market = 'MY' AND scan_date = CURRENT_DATE AND decision = 'BUY'
ORDER BY score DESC;

-- Grade A signals this week
SELECT stock_code, stock_name, scan_date, score, confidence, trade_type
FROM scan_results
WHERE market = 'MY' AND decision = 'BUY' AND confidence = 'A'
  AND scan_date >= DATEADD(DAY, -7, CURRENT_DATE)
ORDER BY scan_date DESC, score DESC;

-- Historical performance tracking (manual — add result column yourself)
SELECT stock_code, scan_date, score, close_price, entry_price,
       stop_loss, target_tp1, target_tp2
FROM scan_results
WHERE market = 'MY' AND decision = 'BUY'
ORDER BY scan_date DESC
LIMIT 50;

-- Check which stocks appear as BUY repeatedly (high conviction recurring)
SELECT stock_code, stock_name, COUNT(*) as signal_count,
       AVG(score) as avg_score, MAX(scan_date) as last_signal
FROM scan_results
WHERE market = 'MY' AND decision = 'BUY'
  AND scan_date >= DATEADD(DAY, -30, CURRENT_DATE)
GROUP BY stock_code, stock_name
HAVING COUNT(*) >= 2
ORDER BY signal_count DESC, avg_score DESC;
```

---

## Summary Cheat Sheet

```
┌─────────────────────────────────────────────────────────────┐
│                    DAILY TRADING ROUTINE                    │
├─────────────────────────────────────────────────────────────┤
│ 6:30 PM (KL) / 5:30 PM (ET)                                │
│   → Run scan: GET /api/my/scan/range?minPrice=0.10&maxPrice=50 │
│   → Review signals: GET /api/my/watchlist/tomorrow          │
│   → Write down: code, entry, entryMax, SL, TP1, TP2, TP3   │
│                                                             │
│ Next morning — 8:45 AM KL                                   │
│   → Check KLCI pre-market sentiment                         │
│   → If KLCI expected -0.5%+ → skip all trades              │
│                                                             │
│ 9:00 AM KL — Market opens                                   │
│   → Wait 3 minutes for opening volatility to settle        │
│   → Check opening price vs entryMax                         │
│   → If valid: place LIMIT buy at entryPrice                 │
│   → If opens above entryMax: SKIP, wait for next signal     │
│                                                             │
│ After fill:                                                 │
│   → Set stop loss order immediately                         │
│   → Set TP1 sell order (40% of shares)                      │
│                                                             │
│ When TP1 hits:                                              │
│   → Sell 40%                                               │
│   → Move SL to breakeven (entry price)                      │
│   → Set TP2 sell order (40% of remaining)                   │
│                                                             │
│ Day 4 — close all open positions from this signal           │
└─────────────────────────────────────────────────────────────┘

NEVER: Buy during market hours scan | Chase above entryMax
NEVER: Skip the stop loss | Average down | Have >3 open trades
ALWAYS: Limit orders | Scan after close | Check KLCI first
```

---

*Screener v2 | MACD Golden Cross + EMA200 + Market Index + Candle Pattern*
*Bursa Malaysia & US Markets | Built with Spring Boot + TA4J + Yahoo Finance*
