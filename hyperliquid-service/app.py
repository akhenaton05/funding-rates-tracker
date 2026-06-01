# Hyperliquid Exchange Service

from flask import Flask, request, jsonify
from flask_cors import CORS
import os
import logging
import time
from datetime import datetime
from decimal import Decimal, ROUND_DOWN
from dotenv import load_dotenv
import eth_account

load_dotenv()

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s %(levelname)-8s %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)

app = Flask(__name__)
CORS(app, allow_origins="*")

# ─── Конфигурация ────────────────────────────────────────────────────────────
HL_PRIVATE_KEY = os.getenv("HL_PRIVATE_KEY")
HL_WALLET      = os.getenv("HL_WALLET_ADDRESS")
HL_TESTNET     = os.getenv("HL_TESTNET", "false").lower() == "true"

from hyperliquid.utils import constants
BASE_URL = constants.TESTNET_API_URL if HL_TESTNET else constants.MAINNET_API_URL

# ─── Глобальные клиенты ──────────────────────────────────────────────────────
info_client     = None
exchange_client = None
wallet_address  = None

market_meta_cache: dict[str, dict] = {}

KNOWN_MAX_LEVERAGE: dict[str, int] = {
    "BTC": 50, "ETH": 50, "SOL": 20, "ARB": 20, "OP": 20,
    "AVAX": 20, "MATIC": 20, "DOGE": 20, "LINK": 20, "UNI": 20,
    "ATOM": 20, "NEAR": 20, "APT": 20, "SUI": 20, "INJ": 20,
    "TIA": 20, "SEI": 20, "PEPE": 10, "SHIB": 10, "BONK": 10,
    "XRP": 20, "BNB": 20, "ADA": 20, "DOT": 20, "LTC": 20,
    "WIF": 10, "POPCAT": 10, "HYPE": 10, "TSLA": 5, "AAPL": 5,
    "NVDA": 5, "MSFT": 5, "AMZN": 5, "GOOGL": 5, "META": 5,
    "XAG": 10, "XAU": 10,
}
SYSTEM_MAX_LEVERAGE = 50

# ─── Инициализация ────────────────────────────────────────────────────────────
def init_clients():
    global info_client, exchange_client, wallet_address

    from hyperliquid.info import Info
    from hyperliquid.exchange import Exchange

    logger.info("Initializing Hyperliquid clients...")
    logger.info(f"Base URL: {BASE_URL}")
    logger.info(f"Testnet:  {HL_TESTNET}")

    try:
        info_client = Info(BASE_URL, skip_ws=True)
        logger.info("Info client initialized OK")
    except Exception as e:
        logger.error(f"Failed to init Info client: {e}", exc_info=True)
        raise

    if not HL_PRIVATE_KEY:
        logger.warning("No HL_PRIVATE_KEY — READ-ONLY mode")
        wallet_address = HL_WALLET
        return

    try:
        agent_wallet = eth_account.Account.from_key(HL_PRIVATE_KEY)
        agent_address = agent_wallet.address
        logger.info(f"Agent wallet (signing): {agent_address}")

        # Master адрес для чтения баланса/позиций
        # Если HL_WALLET_ADDRESS задан — используем его, иначе agent (для обычных аккаунтов)
        wallet_address = HL_WALLET if HL_WALLET else agent_address
        logger.info(f"Master wallet (balance/positions): {wallet_address}")

        exchange_client = Exchange(
            agent_wallet,
            BASE_URL,
            account_address=HL_WALLET if HL_WALLET else None  # agent торгует от имени master
        )
        logger.info("Exchange client initialized — TRADING ENABLED")
    except Exception as e:
        logger.error(f"Failed to init Exchange client: {e}", exc_info=True)
        exchange_client = None
        logger.warning("Continuing in READ-ONLY mode")


def load_markets():
    global market_meta_cache
    try:
        meta, asset_ctxs = info_client.meta_and_asset_ctxs()
        universe = meta["universe"]
        loaded = 0
        for i, asset in enumerate(universe):
            name = asset["name"].upper()
            market_meta_cache[name] = {
                "assetIndex":   i,
                "szDecimals":   asset.get("szDecimals", 4),
                "maxLeverage":  asset.get("maxLeverage", KNOWN_MAX_LEVERAGE.get(name, 20)),
                "onlyIsolated": asset.get("onlyIsolated", False),
            }
            loaded += 1
        logger.info(f"Loaded {loaded} Hyperliquid markets")
        return loaded
    except Exception as e:
        logger.error(f"Failed to load markets: {e}", exc_info=True)
        return 0


def get_market_meta(symbol: str) -> dict:
    upper = symbol.upper()
    variants = [
        upper,
        upper.replace("-USD", "").replace("-USDT", "").replace("-PERP", ""),
        upper.replace("1000", ""),
    ]
    for v in variants:
        if v in market_meta_cache:
            return market_meta_cache[v]
    if len(market_meta_cache) > 10:
        load_markets()
        for v in variants:
            if v in market_meta_cache:
                return market_meta_cache[v]
    available = list(market_meta_cache.keys())[:20]
    raise ValueError(f"Market '{symbol}' not found. Available: {available}")


def get_asset_ctx(symbol: str):
    meta = get_market_meta(symbol)
    idx  = meta["assetIndex"]
    _, ctxs = info_client.meta_and_asset_ctxs()
    if idx >= len(ctxs):
        raise ValueError(f"Asset index {idx} out of range")
    return meta, ctxs[idx]


def round_size(size: float, sz_decimals: int) -> float:
    q = Decimal("0." + "0" * sz_decimals) if sz_decimals > 0 else Decimal("1")
    return float(Decimal(str(size)).quantize(q, rounding=ROUND_DOWN))


def round_price(price: float, px_decimals: int) -> float:
    if px_decimals <= 0:
        return float(int(price))
    q = Decimal("0." + "0" * px_decimals)
    return float(Decimal(str(price)).quantize(q, rounding=ROUND_DOWN))


# ─── Endpoints ───────────────────────────────────────────────────────────────

@app.route("/", methods=["GET"])
def health_check():
    """Healthcheck — проверяет подключение к HL API."""
    hl_reachable = False
    hl_error     = None
    try:
        meta = info_client.meta_and_asset_ctxs()
        hl_reachable = meta is not None
    except Exception as e:
        hl_error = str(e)

    return jsonify(
        status="OK",
        service="hyperliquid-service",
        timestamp=datetime.utcnow().isoformat(),
        trading_enabled=exchange_client is not None,
        wallet=wallet_address,
        markets_loaded=len(market_meta_cache),
        testnet=HL_TESTNET,
        base_url=BASE_URL,
        hl_api_reachable=hl_reachable,
        hl_api_error=hl_error,
    )

@app.route("/positions/last-closed", methods=["GET"])
def get_last_closed_position():
    """
    Найти закрытую позицию по времени закрытия.
    Params:
        market      - символ (ETH, BTC...)
        closed_at_ms - unix ms когда была закрыта позиция
        window_ms   - окно поиска в мс вокруг closed_at_ms (дефолт 10 сек)
    """
    try:
        market       = request.args.get("market")
        closed_at_ms = int(request.args.get("closed_at_ms", int(time.time() * 1000)))
        window_ms    = int(request.args.get("window_ms", 10_000))  # ±10 сек по дефолту

        since_ms  = closed_at_ms - window_ms
        until_ms  = closed_at_ms + window_ms

        raw   = info_client.user_fills_by_time(wallet_address, since_ms)
        fills = raw if isinstance(raw, list) else []

        closing_fills = []
        for f in fills:
            coin       = f.get("coin", "").upper()
            closed_pnl = float(f.get("closedPnl", 0) or 0)
            fill_time  = int(f.get("time", 0))

            if market and coin != market.upper():
                continue
            if closed_pnl == 0.0:
                continue  # открывающий fill
            if fill_time > until_ms:
                continue  # за пределами окна

            closing_fills.append(f)

        if not closing_fills:
            return jsonify(status="OK", data=None,
                           message=f"No closing fills for {market} near {closed_at_ms}"), 200

        # Ближайший к closed_at_ms
        last = min(closing_fills, key=lambda x: abs(int(x.get("time", 0)) - closed_at_ms))

        close_side = "SELL" if last.get("side") == "A" else "BUY"
        open_side  = "LONG" if close_side == "SELL" else "SHORT"
        closed_pnl = float(last.get("closedPnl", 0))
        fee        = float(last.get("fee", 0))

        return jsonify(
            status="OK",
            data={
                "coin":        last.get("coin", "").upper(),
                "side":        open_side,
                "close_price": last.get("px"),
                "size":        last.get("sz"),
                "closed_pnl":  str(closed_pnl),
                "fee":         str(fee),
                "net_pnl":     str(closed_pnl - fee),
                "time_ms":     last.get("time"),
                "time":        datetime.utcfromtimestamp(
                                   int(last.get("time", 0)) / 1000
                               ).isoformat(),
            }
        ), 200
    except Exception as e:
        logger.error(f"Last closed position error: {e}", exc_info=True)
        return jsonify(status="ERROR", message=str(e)), 500


@app.route("/markets", methods=["GET"])
def get_markets():
    markets = [
        {
            "symbol":      sym,
            "assetIndex":  meta["assetIndex"],
            "szDecimals":  meta["szDecimals"],
            "maxLeverage": meta["maxLeverage"],
        }
        for sym, meta in sorted(market_meta_cache.items(), key=lambda x: x[1]["assetIndex"])
    ]
    return jsonify(status="OK", count=len(markets), data=markets)


@app.route("/markets/reload", methods=["POST"])
def reload_markets():
    market_meta_cache.clear()
    loaded = load_markets()
    return jsonify(status="OK", message=f"Reloaded {loaded} markets", count=len(market_meta_cache))


@app.route("/balance", methods=["GET"])
def get_balance():
    try:
        if not wallet_address:
            return jsonify(status="ERROR", message="wallet_address not configured"), 500

        logger.info(f"Getting balance for wallet: {wallet_address}")

        # Для Unified Account — читаем spot баланс
        spot_state = info_client.spot_user_state(wallet_address)
        logger.info(f"spot_user_state: {spot_state}")

        usdc_balance = 0.0
        balances = spot_state.get("balances", [])
        for b in balances:
            if b.get("coin", "").upper() in ("USDC", "USDC.E"):
                usdc_balance = float(b.get("total", 0) or 0)
                break

        # Perp state для margin info (может быть пустым у unified)
        perp_state    = info_client.user_state(wallet_address)
        summary       = perp_state.get("crossMarginSummary") or perp_state.get("marginSummary") or {}
        perp_value    = float(summary.get("accountValue", 0) or 0)
        margin_used   = float(summary.get("totalMarginUsed", 0) or 0)
        withdrawable  = float(perp_state.get("withdrawable", 0) or 0)

        # Unified: total = spot USDC + perp value
        total     = usdc_balance if perp_value == 0 else perp_value
        available = withdrawable if withdrawable > 0 else max(0.0, total - margin_used)

        logger.info(f"Balance: spot_usdc={usdc_balance:.4f}, perp_value={perp_value:.4f}, total={total:.4f}")

        return jsonify(
            status="OK",
            data={
                "total":               f"{total:.6f}",
                "available_for_trade": f"{available:.6f}",
                "margin_used":         f"{margin_used:.6f}",
                "equity":              f"{total:.6f}",
                "withdrawable":        f"{withdrawable:.6f}",
            }
        )
    except Exception as e:
        logger.error(f"Balance error: {e}", exc_info=True)
        return jsonify(status="ERROR", message=str(e)), 500


@app.route("/positions", methods=["GET"])
def get_positions():
    try:
        if not wallet_address:
            return jsonify(status="ERROR", message="wallet_address not configured"), 500

        market = request.args.get("market")
        side   = request.args.get("side")

        state         = info_client.user_state(wallet_address)
        raw_positions = state.get("assetPositions", [])

        # Для mark price берём asset_ctxs один раз
        try:
            _, ctxs = info_client.meta_and_asset_ctxs()
        except Exception:
            ctxs = []

        formatted = []
        for p in raw_positions:
            pos  = p.get("position", {})
            coin = pos.get("coin", "").upper()
            szi  = float(pos.get("szi", 0) or 0)

            if szi == 0:
                continue

            pos_side = "LONG" if szi > 0 else "SHORT"
            pos_size = abs(szi)

            if market and coin != market.upper():
                continue
            if side and pos_side != side.upper():
                continue

            entry_px  = float(pos.get("entryPx", 0) or 0)
            liq_px    = float(pos.get("liquidationPx", 0) or 0)
            upnl      = float(pos.get("unrealizedPnl", 0) or 0)
            margin    = pos.get("marginUsed", "0")
            leverage_val = pos.get("leverage", {})
            lev_val   = leverage_val.get("value", 1) if isinstance(leverage_val, dict) else 1

            # funding
            cum_funding      = pos.get("cumFunding", {})
            funding_since_open = float(cum_funding.get("sinceOpen", 0) or 0)

            # mark price из ctxs
            mark_px = entry_px
            if coin in market_meta_cache:
                idx = market_meta_cache[coin]["assetIndex"]
                if idx < len(ctxs):
                    mark_px = float(ctxs[idx].get("markPx", entry_px) or entry_px)

            formatted.append({
                "market":            coin,
                "side":              pos_side,
                "size":              str(pos_size),
                "open_price":        str(entry_px),
                "mark_price":        str(mark_px),
                "unrealised_pnl":    str(upnl),
                "realized_pnl":      "0",
                "margin":            str(margin),
                "liquidation_price": str(liq_px),
                "leverage":          f"{lev_val}x",
                "funding_paid":      str(funding_since_open),
            })

        logger.info(f"Returning {len(formatted)} positions")
        return jsonify(status="OK", data=formatted), 200
    except Exception as e:
        logger.error(f"Positions error: {e}", exc_info=True)
        return jsonify(status="ERROR", message=str(e)), 500


@app.route("/markets/<string:symbol>/orderbook", methods=["GET"])
def get_orderbook(symbol: str):
    try:
        limit = int(request.args.get("limit", 10))
        l2    = info_client.l2_snapshot(symbol.upper())
        levels   = l2.get("levels", [[], []])
        bids_raw = levels[0][:limit]
        asks_raw = levels[1][:limit]

        bids = [{"price": str(b["px"]), "size": str(b["sz"])} for b in bids_raw]
        asks = [{"price": str(a["px"]), "size": str(a["sz"])} for a in asks_raw]

        best_bid   = float(bids[0]["price"]) if bids else None
        best_ask   = float(asks[0]["price"]) if asks else None
        mid        = (best_bid + best_ask) / 2 if best_bid and best_ask else None
        spread     = best_ask - best_bid if best_bid and best_ask else None
        spread_bps = spread / best_bid * 10000 if spread and best_bid else None

        logger.info(f"Orderbook {symbol}: bid={best_bid}, ask={best_ask}, spread_bps={f'{spread_bps:.1f}' if spread_bps else 'N/A'}")
        return jsonify(
            status="OK",
            market=symbol.upper(),
            bids=bids,
            asks=asks,
            summary={
                "best_bid":   best_bid,
                "best_ask":   best_ask,
                "mid_price":  mid,
                "spread":     spread,
                "spread_bps": spread_bps,
                "bids_count": len(bids),
                "asks_count": len(asks),
            },
            timestamp=datetime.utcnow().isoformat(),
        ), 200
    except Exception as e:
        logger.error(f"Orderbook error: {e}", exc_info=True)
        return jsonify(status="ERROR", message=str(e)), 500


@app.route("/markets/<string:symbol>/funding-rate", methods=["GET"])
def get_funding_rate(symbol: str):
    try:
        meta, ctx = get_asset_ctx(symbol)
        raw_rate  = float(ctx.get("funding", 0) or 0)
        rate_pct  = raw_rate * 100

        now_ts       = int(time.time() * 1000)
        next_ts      = (now_ts // 3_600_000 + 1) * 3_600_000
        mins_to_next = (next_ts - now_ts) // 60_000

        logger.info(f"Funding {symbol}: rate={rate_pct:.4f}% 1h, next in {mins_to_next}min")
        return jsonify(
            status="OK",
            market=symbol.upper(),
            asset_index=meta["assetIndex"],
            funding_rate=rate_pct,
            funding_rate_raw=raw_rate,
            funding_rate_8h=rate_pct * 8,
            funding_interval_hours=1,
            next_funding_in_minutes=mins_to_next,
            next_funding_time_ms=next_ts,
        ), 200
    except Exception as e:
        logger.error(f"Funding rate error: {e}", exc_info=True)
        return jsonify(status="ERROR", message=str(e)), 500


@app.route("/funding/history", methods=["GET"])
def get_funding_history():
    try:
        if not wallet_address:
            return jsonify(status="ERROR", message="wallet_address not configured"), 500

        market     = request.args.get("market")
        start_time = int(request.args.get("start_time", 0))
        if start_time == 0:
            start_time = int(time.time() * 1000) - 86_400_000

        raw     = info_client.user_funding(wallet_address, start_time)
        entries = raw if isinstance(raw, list) else []

        total_funding = 0.0
        filtered      = []

        for e in entries:
            delta = e.get("delta", {})
            coin  = delta.get("coin", "").upper()
            usdc  = float(delta.get("usdc", 0) or 0)
            rate  = float(delta.get("fundingRate", 0) or 0)
            ts    = int(e.get("time", 0))

            if market and coin != market.upper():
                continue

            total_funding += usdc
            filtered.append({
                "coin":         coin,
                "usdc":         usdc,
                "funding_rate": rate,
                "time_ms":      ts,
                "time":         datetime.utcfromtimestamp(ts / 1000).isoformat(),
            })

        logger.info(f"Funding history {market or 'ALL'}: {len(filtered)} entries, total={total_funding:.6f}")
        return jsonify(
            status="OK",
            market=market or "ALL",
            accumulated_funding=total_funding,
            entries_count=len(filtered),
            data=filtered,
        ), 200
    except Exception as e:
        logger.error(f"Funding history error: {e}", exc_info=True)
        return jsonify(status="ERROR", message=str(e)), 500


@app.route("/markets/<string:symbol>/max-leverage", methods=["GET"])
def get_max_leverage(symbol: str):
    try:
        upper = symbol.upper()
        if upper in market_meta_cache:
            lev = market_meta_cache[upper]["maxLeverage"]
            return jsonify(status="OK", market=symbol, max_leverage=lev, source="meta_api"), 200
        if upper in KNOWN_MAX_LEVERAGE:
            lev = KNOWN_MAX_LEVERAGE[upper]
            return jsonify(status="OK", market=symbol, max_leverage=lev, source="hardcoded"), 200
        return jsonify(status="OK", market=symbol, max_leverage=20, source="default"), 200
    except Exception as e:
        logger.error(f"Max leverage error: {e}", exc_info=True)
        return jsonify(status="ERROR", message=str(e)), 500


@app.route("/markets/<string:symbol>/calculate-size", methods=["POST"])
def calculate_size(symbol: str):
    try:
        data       = request.get_json()
        margin_usd = float(data.get("margin_usd", 0))
        leverage   = int(data.get("leverage", 1))
        is_buy     = data.get("is_buy", True)

        if margin_usd <= 0:
            return jsonify(status="ERROR", message="margin_usd must be positive"), 400

        meta   = get_market_meta(symbol)
        _, ctx = get_asset_ctx(symbol)

        l2     = info_client.l2_snapshot(symbol.upper())
        levels = l2.get("levels", [[], []])
        bids   = levels[0]
        asks   = levels[1]
        if bids and asks:
            price = float(asks[0]["px"]) if is_buy else float(bids[0]["px"])
        else:
            price = float(ctx.get("markPx", 100) or 100)

        position_value = margin_usd * leverage
        sz_dec         = meta["szDecimals"]
        size_rounded   = round_size(position_value / price, sz_dec)

        logger.info(f"Calculate size {symbol}: margin={margin_usd}, lev={leverage}x, price={price:.4f}, size={size_rounded}")
        return jsonify(
            status="OK",
            market=symbol.upper(),
            max_size=f"{size_rounded:.{sz_dec}f}",
            price=f"{price:.4f}",
            position_value=f"{position_value:.2f}",
            leverage=leverage,
        ), 200
    except Exception as e:
        logger.error(f"Calculate size error: {e}", exc_info=True)
        return jsonify(status="ERROR", message=str(e)), 500


@app.route("/order/market", methods=["POST"])
def open_market_position():
    if not exchange_client:
        return jsonify(status="ERROR", message="Trading not configured"), 503
    try:
        data     = request.get_json()
        symbol   = data.get("market", "").upper()
        side     = data.get("side", "BUY").upper()
        size_str = data.get("size")
        slippage = float(data.get("price_slippage_pct", 1.5)) / 100

        if not symbol or not size_str:
            return jsonify(status="ERROR", message="market and size required"), 400

        meta   = get_market_meta(symbol)
        sz_dec = meta["szDecimals"]
        size   = round_size(float(size_str), sz_dec)

        if size <= 0:
            return jsonify(status="ERROR", message=f"Size too small after rounding: {size}"), 400

        is_buy = side == "BUY"
        logger.info(f"Opening {side} {size} {symbol}")

        result = exchange_client.market_open(symbol, is_buy, size, None, slippage)
        logger.info(f"Order result: {result}")

        status_val = result.get("status", "")
        if status_val == "ok":
            statuses = result.get("response", {}).get("data", {}).get("statuses", [{}])
            filled   = statuses[0].get("filled", {}) if statuses else {}
            return jsonify(
                status="success",
                market=symbol,
                side=side,
                size=str(size),
                avg_px=str(filled.get("avgPx", 0)),
                order_id=str(filled.get("oid", "")),
                total_sz=str(filled.get("totalSz", size)),
                raw=result,
            ), 200
        else:
            return jsonify(status="ERROR", message=str(result)), 500

    except Exception as e:
        logger.error(f"Open position error: {e}", exc_info=True)
        return jsonify(status="ERROR", message=str(e)), 500


@app.route("/positions/close", methods=["POST"])
def close_position():
    if not exchange_client:
        return jsonify(status="ERROR", message="Trading not configured"), 503
    try:
        data         = request.get_json()
        symbol       = data.get("market", "").upper()
        current_side = data.get("current_side", "").upper()
        slippage     = float(data.get("price_slippage_pct", 3.0)) / 100

        if not symbol or not current_side:
            return jsonify(status="ERROR", message="market and current_side required"), 400

        state     = info_client.user_state(wallet_address)
        positions = state.get("assetPositions", [])

        position = None
        for p in positions:
            pos  = p.get("position", {})
            coin = pos.get("coin", "").upper()
            szi  = float(pos.get("szi", 0) or 0)
            if coin != symbol:
                continue
            if (szi > 0 and current_side == "LONG") or (szi < 0 and current_side == "SHORT"):
                position = pos
                break

        if not position:
            available = [
                f"{p['position']['coin']}:{'LONG' if float(p['position']['szi']) > 0 else 'SHORT'}"
                for p in positions if float(p.get("position", {}).get("szi", 0)) != 0
            ]
            return jsonify(
                status="ERROR",
                message=f"No {current_side} position found for {symbol}",
                available_positions=available,
            ), 404

        size       = abs(float(position.get("szi", 0)))
        entry_px   = float(position.get("entryPx", 0) or 0)
        meta       = get_market_meta(symbol)
        sz_dec     = meta["szDecimals"]
        size_round = round_size(size, sz_dec)

        logger.info(f"Closing {current_side} {size_round} {symbol}, entry={entry_px:.4f}")

        # ✅ FIX: helper — проверяем фактический размер позиции
        def _current_position_size():
            try:
                st = info_client.user_state(wallet_address)
                for pp in st.get("assetPositions", []):
                    p_pos = pp.get("position", {})
                    if p_pos.get("coin", "").upper() == symbol:
                        return abs(float(p_pos.get("szi", 0) or 0))
                return 0.0
            except Exception as ex:
                logger.warning(f"_current_position_size error: {ex}")
                return -1.0

        # ✅ FIX: confirm-loop (poll 0.5s × 15s) вместо single sleep(2)
        def _wait_closed(timeout=15.0, poll=0.5):
            elapsed = 0.0
            last = size_round
            while elapsed < timeout:
                time.sleep(poll)
                elapsed += poll
                cur = _current_position_size()
                if cur < 0:
                    continue
                last = cur
                if cur <= 0:
                    return True, 0.0
            return False, last

        max_attempts = 3
        last_result = None
        any_order_id = ""
        last_exit_price = 0.0
        remaining = size_round

        for attempt in range(1, max_attempts + 1):
            if remaining <= 0:
                break

            # Попытка close
            try:
                result = exchange_client.market_close(symbol, remaining, None, slippage)
                last_result = result
            except Exception as call_err:
                logger.error(f"   [a{attempt}] market_close exception: {call_err}", exc_info=True)
                time.sleep(0.5)
                continue

            status_val = result.get("status", "")
            if status_val != "ok":
                logger.warning(f"   [a{attempt}] market_close status={status_val}, result={result}")
                # Может позиция уже закрыта
                cur = _current_position_size()
                if cur == 0:
                    logger.info(f"   [a{attempt}] Position already closed")
                    remaining = 0
                    break
                if cur > 0:
                    remaining = round_size(cur, sz_dec)
                time.sleep(0.5)
                continue

            # status == ok
            try:
                filled = result["response"]["data"]["statuses"][0].get("filled", {})
                exit_price = float(filled.get("avgPx", 0) or 0)
                order_id = str(filled.get("oid", ""))
                if order_id:
                    any_order_id = order_id
                if exit_price > 0:
                    last_exit_price = exit_price
            except (KeyError, IndexError, TypeError):
                exit_price = 0.0
                order_id = ""

            logger.info(f"   [a{attempt}] close submitted, oid={order_id}")

            closed, last_size = _wait_closed(timeout=15.0, poll=0.5)
            if closed:
                logger.info(f"Position {symbol} confirmed CLOSED, entry={entry_px:.4f}, exit={last_exit_price:.4f}")
                return jsonify(
                    status="success",
                    message="Position closed successfully",
                    market=symbol,
                    size=str(size_round),
                    side="SELL" if current_side == "LONG" else "BUY",
                    entry_price=str(entry_px),
                    exit_price=str(last_exit_price),
                    order_id=any_order_id,
                    raw=result,
                ), 200

            logger.warning(f"   [a{attempt}] still open: {last_size}, will retry")
            remaining = round_size(last_size, sz_dec) if last_size > 0 else remaining

        # Финальная проверка
        final = _current_position_size()
        if final == 0:
            logger.info(f"Position {symbol} confirmed CLOSED (final check)")
            return jsonify(
                status="success",
                message="Position closed successfully",
                market=symbol,
                size=str(size_round),
                side="SELL" if current_side == "LONG" else "BUY",
                entry_price=str(entry_px),
                exit_price=str(last_exit_price),
                order_id=any_order_id,
                raw=last_result,
            ), 200

        # Частичное или неудачное закрытие
        logger.error(f"Position {symbol} not fully closed after {max_attempts} attempts. Remaining: {final}")
        return jsonify(
            status="PARTIAL" if any_order_id else "submitted",
            message=f"Close order submitted but position may still be open. Remaining: {final}",
            market=symbol,
            size=str(size_round),
            remaining_size=str(final),
            exit_price=str(last_exit_price),
            order_id=any_order_id,
            raw=last_result,
        ), 200

    except Exception as e:
        logger.error(f"Close position error: {e}", exc_info=True)
        return jsonify(status="ERROR", message=str(e)), 500

@app.route("/user/leverage", methods=["POST"])
def set_leverage():
    if not exchange_client:
        return jsonify(status="ERROR", message="Trading not configured"), 503
    try:
        data     = request.get_json()
        symbol   = data.get("market", "").upper()
        leverage = int(data.get("leverage", 1))
        is_cross = data.get("is_cross", True)

        if not symbol:
            return jsonify(status="ERROR", message="market required"), 400
        if leverage < 1 or leverage > SYSTEM_MAX_LEVERAGE:
            return jsonify(status="ERROR", message=f"leverage must be 1-{SYSTEM_MAX_LEVERAGE}"), 400

        meta    = get_market_meta(symbol)
        max_lev = meta.get("maxLeverage", SYSTEM_MAX_LEVERAGE)
        actual  = min(leverage, max_lev)

        if actual != leverage:
            logger.warning(f"Requested {leverage}x, max for {symbol} is {max_lev}x, using {actual}x")

        logger.info(f"Setting leverage {symbol}: {actual}x, cross={is_cross}")
        result     = exchange_client.update_leverage(actual, symbol, is_cross)
        status_val = result.get("status", "")

        if status_val == "ok":
            return jsonify(
                status="success",
                message=f"Leverage set to {actual}x for {symbol}",
                market=symbol,
                leverage=actual,
                requested_leverage=leverage,
                is_cross=is_cross,
            ), 200
        else:
            return jsonify(status="ERROR", message=str(result)), 500

    except Exception as e:
        logger.error(f"Set leverage error: {e}", exc_info=True)
        return jsonify(status="ERROR", message=str(e)), 500


# ─── Запуск ──────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    init_clients()
    if info_client:
        logger.info("=" * 60)
        logger.info("Loading Hyperliquid markets...")
        logger.info("=" * 60)
        loaded = load_markets()
        logger.info(f"Loaded {loaded} markets")
        sample = list(market_meta_cache.keys())[:10]
        logger.info(f"Sample markets: {', '.join(sample)}")
        logger.info("=" * 60)
        logger.info(f"Wallet: {wallet_address}")
        logger.info(f"Trading: {'ENABLED' if exchange_client else 'READ-ONLY'}")
        logger.info("App ready to serve requests")
    app.run(host="0.0.0.0", port=5002, debug=False)