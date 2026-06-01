import os
import logging
import asyncio
import threading
import time
import json
from decimal import Decimal, ROUND_DOWN
from concurrent.futures import TimeoutError as FutureTimeout
import secrets

from flask import Flask, request, jsonify, Response
from flask_cors import CORS
from dotenv import load_dotenv

import aiohttp
from tenacity import retry, stop_after_attempt, wait_fixed, retry_if_exception_type

load_dotenv()

logging.basicConfig(level=logging.INFO, format='[%(asctime)s] %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# ✅ FIX: убрали DEBUG для aiohttp — раньше спамило логи и тормозило прод.
logging.getLogger("aiohttp").setLevel(logging.WARNING)
logging.getLogger("aiohttp.client").setLevel(logging.WARNING)
logging.getLogger("x10.utils.http").setLevel(logging.WARNING)

app = Flask(__name__)
CORS(app)

API_KEY = os.getenv("EXTENDED_API_KEY")
PUBLIC_KEY = os.getenv("EXTENDED_PUBLIC_KEY")
PRIVATE_KEY = os.getenv("EXTENDED_PRIVATE_KEY")
VAULT_ID = int(os.getenv("EXTENDED_VAULT", "0"))
PORT = int(os.getenv("PORT", "5000"))
EXTENDED_HTTP_BASE = os.getenv("EXTENDED_HTTP_BASE", "https://api.starknet.extended.exchange")

HTTP_PROXY = os.getenv("HTTP_PROXY")
logger.info("Using proxy: %s", HTTP_PROXY or "NONE")

# ---- global loop ----
loop = asyncio.new_event_loop()

def _run_loop():
    asyncio.set_event_loop(loop)
    loop.run_forever()

threading.Thread(target=_run_loop, daemon=True).start()

def _submit(coro, timeout_sec: int | None):
    fut = asyncio.run_coroutine_threadsafe(coro, loop)
    if timeout_sec is None:
        return fut.result()
    try:
        return fut.result(timeout=timeout_sec)
    except FutureTimeout:
        fut.cancel()
        raise

# ---- aiohttp trace ----
# ✅ FIX: trace логирует только медленные / ошибочные запросы (раньше — каждый запрос INFO)
_SLOW_REQUEST_THRESHOLD = 1.5  # сек

async def on_request_start(session, trace_config_ctx, params):
    trace_config_ctx._t0 = time.monotonic()

async def on_request_end(session, trace_config_ctx, params):
    dt = time.monotonic() - getattr(trace_config_ctx, '_t0', 0)
    status = getattr(params.response, 'status', None) if hasattr(params, 'response') else None
    if dt > _SLOW_REQUEST_THRESHOLD or (status and status >= 400):
        logger.warning("AIOHTTP slow/err %s %s status=%s dt=%.2fs", params.method, params.url, status, dt)

async def on_request_exception(session, trace_config_ctx, params):
    logger.error("AIOHTTP EXCEPTION %s %s exc=%r", params.method, params.url, params.exception)

TRACE_CONFIG = aiohttp.TraceConfig()
TRACE_CONFIG.on_request_start.append(on_request_start)
TRACE_CONFIG.on_request_end.append(on_request_end)
TRACE_CONFIG.on_request_exception.append(on_request_exception)

# ---- monkey patch ClientSession (ДО импорта SDK) ----
_original_client_session = aiohttp.ClientSession
def _ClientSession_patched(*args, **kwargs):
    if HTTP_PROXY:
        kwargs.setdefault('proxy', HTTP_PROXY)
    kwargs.setdefault("timeout", aiohttp.ClientTimeout(total=30, connect=10, sock_read=20))
    kwargs.setdefault("trace_configs", [TRACE_CONFIG])
    kwargs.setdefault("headers", {"User-Agent": "X10Bot/1.0"})
    return _original_client_session(*args, **kwargs)

aiohttp.ClientSession = _ClientSession_patched

# ---- SDK ----
from x10.perpetual.accounts import StarkPerpetualAccount
from x10.perpetual.configuration import MAINNET_CONFIG
from x10.perpetual.orders import OrderSide
from x10.perpetual.simple_client.simple_trading_client import BlockingTradingClient

ORDER_TASKS: dict[str, dict] = {}
ORDER_FUTURES: dict[str, object] = {}

# ---- market cache ----
_MARKET_CACHE: dict[str, dict] = {}
# ✅ FIX: TTL цен 30 → 5 сек (перед close раньше попадалась устаревшая цена)
_MARKET_CACHE_TTL_SEC = int(os.getenv("MARKET_CACHE_TTL_SEC", "5"))

# ✅ FIX: persistent aiohttp session с пулом (раньше новый ClientSession на каждый запрос → TLS handshake каждый раз)
_HTTP_SESSION = None
_SDK_LAST_FAIL_TS: float = 0.0
_SDK_REINIT_LOCK = asyncio.Lock()

async def _get_http_session():
    global _HTTP_SESSION
    if _HTTP_SESSION is None or _HTTP_SESSION.closed:
        connector = aiohttp.TCPConnector(
            limit=50,
            limit_per_host=20,
            ttl_dns_cache=300,
            enable_cleanup_closed=True,
        )
        timeout = aiohttp.ClientTimeout(total=30, connect=10, sock_read=20)
        _HTTP_SESSION = _original_client_session(
            connector=connector,
            timeout=timeout,
            headers={"User-Agent": "X10Bot/1.0"},
            trace_configs=[TRACE_CONFIG],
        )
        logger.info("HTTP: persistent ClientSession created (pool=50)")
    return _HTTP_SESSION

async def safe_json(text: str):
    if not text or not text.strip():
        return {}
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return {"error": "invalid_json", "raw_text": text[:500]}

async def _reinit_sdk_if_needed():
    """✅ FIX: авто-reinit BlockingTradingClient. Раньше при потере сессии SDK
    оставался битым до рестарта процесса."""
    global account, blocking_client, _SDK_LAST_FAIL_TS
    async with _SDK_REINIT_LOCK:
        if blocking_client is not None:
            return
        try:
            logger.warning("SDK reinit: starting...")
            account, blocking_client = await _init_clients()
            _SDK_LAST_FAIL_TS = 0.0
            logger.info("SDK reinit: success")
        except Exception:
            logger.exception("SDK reinit: failed")
            _SDK_LAST_FAIL_TS = time.time()
            raise

async def _init_clients():
    if not (API_KEY and PUBLIC_KEY and PRIVATE_KEY and VAULT_ID):
        raise RuntimeError("Missing env vars (EXTENDED_API_KEY/EXTENDED_PUBLIC_KEY/EXTENDED_PRIVATE_KEY/EXTENDED_VAULT)")

    account = StarkPerpetualAccount(
        vault=VAULT_ID,
        private_key=PRIVATE_KEY,
        public_key=PUBLIC_KEY,
        api_key=API_KEY,
    )

    logger.info("INIT: creating BlockingTradingClient ...")
    blocking = await BlockingTradingClient.create(endpoint_config=MAINNET_CONFIG, account=account)
    logger.info("INIT: BlockingTradingClient ready")

    try:
        markets = await blocking.get_markets()
        logger.info("INIT: markets cached in SDK | count=%d", len(markets))
    except Exception:
        logger.exception("INIT: failed to pre-fetch markets")

    return account, blocking

account, blocking_client = _submit(_init_clients(), timeout_sec=60)
logger.info("✅ SDK ready (vault=%s)", VAULT_ID)

@retry(
    stop=stop_after_attempt(3),
    wait=wait_fixed(3),
    retry=retry_if_exception_type((aiohttp.ClientError, asyncio.TimeoutError)),
    reraise=True
)
async def place_with_retry(client, **kwargs):
    return await client.create_and_place_order(**kwargs)

# ✅ FIX: все HTTP вызовы через persistent session и с idempotent helper
async def _extended_get(path: str, params: dict = None):
    url = f"{EXTENDED_HTTP_BASE}{path}"
    headers = {"X-Api-Key": API_KEY, "User-Agent": "X10Bot/1.0"}
    logger.debug("EXTENDED GET: %s params=%s", url, params or {})

    session = await _get_http_session()
    extra = {"proxy": HTTP_PROXY} if HTTP_PROXY else {}
    async with session.get(url, headers=headers, params=params or {}, **extra) as resp:
        text = await resp.text()
        if resp.status >= 400:
            logger.warning("EXTENDED GET %s: status=%s body=%s", path, resp.status, text[:300])
        data = await safe_json(text)
        return resp.status, data

async def _extended_delete(path: str, params: dict = None):
    url = f"{EXTENDED_HTTP_BASE}{path}"
    headers = {"X-Api-Key": API_KEY, "User-Agent": "X10Bot/1.0"}
    logger.debug("EXTENDED DELETE: %s params=%s", url, params or {})

    session = await _get_http_session()
    extra = {"proxy": HTTP_PROXY} if HTTP_PROXY else {}
    async with session.delete(url, headers=headers, params=params or {}, **extra) as resp:
        text = await resp.text()
        if resp.status >= 400:
            logger.warning("EXTENDED DELETE %s: status=%s body=%s", path, resp.status, text[:300])
        data = await safe_json(text)
        return resp.status, data

async def _extended_patch(path: str, json_body: dict):
    url = f"{EXTENDED_HTTP_BASE}{path}"
    headers = {"X-Api-Key": API_KEY, "Content-Type": "application/json", "User-Agent": "X10Bot/1.0"}
    logger.debug("EXTENDED PATCH: %s body=%s", url, json_body)

    session = await _get_http_session()
    extra = {"proxy": HTTP_PROXY} if HTTP_PROXY else {}
    async with session.patch(url, headers=headers, json=json_body, **extra) as resp:
        text = await resp.text()
        if resp.status >= 400:
            logger.warning("EXTENDED PATCH %s: status=%s body=%s", path, resp.status, text[:300])
        data = await safe_json(text)
        return resp.status, data

# ✅ FIX: единая проверка «уже размещён» (раньше string match был хрупким)
def _is_idempotent_already_placed(err: Exception) -> bool:
    err_str = str(err).lower()
    needles = ["already placed", "hash already", "duplicate", "already exists", "externalid already"]
    return any(n in err_str for n in needles)

async def _get_best_price_from_orderbook(market: str, side: OrderSide) -> Decimal | None:
    """
    ✅ НОВЫЙ МЕТОД: получает best_ask (для BUY) или best_bid (для SELL) из стакана.
    Используется для расчёта цены маркет-ордера.
    """
    try:
        status, data = await _extended_get(f"/api/v1/info/markets/{market}/orderbook")
        if status != 200 or not isinstance(data, dict):
            return None
        book = data.get("data", {})
        if side == OrderSide.BUY:
            asks = book.get("ask", [])
            if asks:
                best_ask = Decimal(str(asks[0]["price"]))
                logger.info("Orderbook best_ask for %s: %s", market, best_ask)
                return best_ask
        else:
            bids = book.get("bid", [])
            if bids:
                best_bid = Decimal(str(bids[0]["price"]))
                logger.info("Orderbook best_bid for %s: %s", market, best_bid)
                return best_bid
    except Exception as e:
        logger.warning("_get_best_price_from_orderbook failed for %s: %s", market, e)
    return None

async def _wait_position_closed(market: str, side: str, timeout_sec: float = 25.0) -> bool:
    # ✅ FIX: было 10s и 0.3s — под нагрузкой Extended этого мало и бьёт rate-limit
    start = time.time()
    poll_interval = 0.5

    while time.time() - start < timeout_sec:
        try:
            status, data = await _extended_get("/api/v1/user/positions", params={"market": market, "side": side})

            if status != 200:
                logger.warning("_wait_position_closed: bad status %s, continuing polling", status)
                await asyncio.sleep(poll_interval)
                continue

            positions = data.get("data", []) if isinstance(data, dict) else []

            if not positions:
                logger.info("_wait_position_closed: %s %s disappeared → CLOSED", market, side)
                return True

            size = Decimal(str(positions[0].get("size", "0")))
            if size < Decimal("0.001"):
                logger.info("_wait_position_closed: %s %s size=%s → CLOSED", market, side, size)
                return True

            logger.debug("_wait_position_closed: %s %s size=%s still open, polling...", market, side, size)
            await asyncio.sleep(poll_interval)
        except Exception as e:
            logger.warning("_wait_position_closed: exception during polling: %s", e)
            await asyncio.sleep(poll_interval)

    logger.warning("_wait_position_closed: TIMEOUT after %.1fs for %s %s", timeout_sec, market, side)
    return False

async def _wait_position_opened(market: str, side: str, timeout_sec: float = 20.0) -> bool:
    """
    ✅ НОВЫЙ МЕТОД: polling пока позиция не появится в /positions.
    Нужен потому что Extended может показывать позицию с задержкой ~15 сек после 202.
    """
    start = time.time()
    attempt = 0
    poll_interval = 2.0

    while time.time() - start < timeout_sec:
        attempt += 1
        try:
            status, data = await _extended_get(
                "/api/v1/user/positions",
                params={"market": market, "side": side}
            )

            if status == 200 and isinstance(data, dict):
                positions = data.get("data", [])
                if positions:
                    size = Decimal(str(positions[0].get("size", "0")))
                    if size > Decimal("0"):
                        logger.info("_wait_position_opened: %s %s FOUND on attempt %d (size=%s)",
                                    market, side, attempt, size)
                        return True

            logger.info("_wait_position_opened: %s %s not visible yet (attempt %d), waiting %.1fs...",
                        market, side, attempt, poll_interval)
            await asyncio.sleep(poll_interval)
        except Exception as e:
            logger.warning("_wait_position_opened: exception on attempt %d: %s", attempt, e)
            await asyncio.sleep(poll_interval)

    logger.warning("_wait_position_opened: TIMEOUT after %.1fs for %s %s", timeout_sec, market, side)
    return False

async def _get_market_raw(market: str) -> dict:
    now = time.time()
    cached = _MARKET_CACHE.get(market)
    if cached and now - cached["ts"] < _MARKET_CACHE_TTL_SEC:
        return cached["data"]

    status, data = await _extended_get("/api/v1/info/markets", {"market": market})
    if status != 200 or not data.get("data"):
        raise RuntimeError(f"Market not found or bad response: status={status} body={data}")
    m = data["data"][0]
    _MARKET_CACHE[market] = {"ts": now, "data": m}
    return m

async def get_mark_price(market: str) -> Decimal:
    m = await _get_market_raw(market)
    price_str = m["marketStats"]["markPrice"]
    return Decimal(str(price_str))

async def get_market_precision(market: str) -> tuple[Decimal, Decimal, int, Decimal]:
    m = await _get_market_raw(market)

    asset_precision = int(m.get("assetPrecision", 2))
    tc = m.get("tradingConfig", {}) or {}
    logger.info("PRECISION RAW tradingConfig for %s: %s", market, tc)  # ← добавить
    tick_size = Decimal(str(tc.get("minPriceChange", "0.1")))
    step_size = Decimal(str(tc.get("minOrderSizeChange", "0.001")))
    min_order_size = Decimal(str(tc.get("minOrderSize", "0.001")))

    if tick_size <= 0 or step_size <= 0:
        raise RuntimeError(f"Bad precision values: tick={tick_size} step={step_size} market={market}")

    logger.info("PRECISION %s: tick_size=%s step_size=%s min_order_size=%s asset_precision=%d",
                market, tick_size, step_size, min_order_size, asset_precision)
    return tick_size, step_size, asset_precision, min_order_size

def _round_down(value: Decimal, step: Decimal) -> Decimal:
    return value.quantize(step, rounding=ROUND_DOWN)

async def _place_order_task(external_id: str, market: str, side: OrderSide, size: Decimal, post_only: bool, price_offset_pct: Decimal):
    try:
        ORDER_TASKS[external_id] = {"status": "running", "started": time.time(), "error": None, "order_id": None}

        mark_price = await get_mark_price(market)
        tick_size, step_size, _, min_order_size = await get_market_precision(market)

        if size < min_order_size:
            raise RuntimeError(f"size {size} меньше min_order_size {min_order_size} для {market}")

        if side == OrderSide.BUY:
            price = mark_price * (Decimal("1") - price_offset_pct / Decimal("100"))
        else:
            price = mark_price * (Decimal("1") + price_offset_pct / Decimal("100"))

        price_decimal = _round_down(price, tick_size)
        size_decimal = _round_down(size, step_size)

        if size_decimal <= 0:
            raise RuntimeError(f"rounded size became zero: size={size} step={step_size}")
        if price_decimal <= 0:
            raise RuntimeError(f"rounded price became zero: price={price} tick={tick_size}")

        logger.info("TASK %s LIMIT placing: market=%s side=%s size=%s price=%s (mark=%s tick=%s step=%s) post_only=%s",
                    external_id, market, side.name, size_decimal, price_decimal, mark_price, tick_size, step_size, post_only)

        order_id = None
        final_status = "checking"

        try:
            placed = await asyncio.wait_for(
                place_with_retry(
                    blocking_client,
                    market_name=market,
                    amount_of_synthetic=size_decimal,
                    price=price_decimal,
                    side=side,
                    post_only=post_only,
                    external_id=external_id,
                ),
                timeout=20.0
            )
            order_id = str(getattr(placed, "id", "unknown"))
            final_status = "placed"
        except Exception as e:
            err_str = str(e).lower()
            if "already placed" in err_str or "hash already" in err_str:
                final_status = "placed"
            else:
                logger.warning("TASK %s LIMIT SDK failed: %s → manual check", external_id, err_str)
                await asyncio.sleep(1.5)

        if final_status == "checking":
            status, data = await _extended_get("/api/v1/user/orders/open", params={"externalId": external_id})
            orders = data.get("data", []) if isinstance(data, dict) else []
            if orders:
                real_id = orders[0].get("id")
                status_str = orders[0].get("status", "UNKNOWN")
                order_id = str(real_id) if real_id else "unknown"
                final_status = "placed" if status_str in ["OPEN", "NEW", "ACCEPTED"] else f"open_{status_str}"
            else:
                final_status = "accepted"

        ORDER_TASKS[external_id] = {
            "status": final_status,
            "order_id": order_id,
            "price": str(price_decimal),
            "size": str(size_decimal),
            "type": "LIMIT",
            "finished": time.time(),
        }

    except Exception as e:
        logger.exception("TASK %s LIMIT critical failure", external_id)
        ORDER_TASKS[external_id] = {"status": "error", "error": str(e), "order_id": None}

async def _place_market_order_task(external_id: str, market: str, side: OrderSide, size: Decimal, slippage_pct: Decimal):
    try:
        ORDER_TASKS[external_id] = {"status": "running", "started": time.time(), "error": None, "order_id": None}

        tick_size, step_size, _, min_order_size = await get_market_precision(market)

        if size < min_order_size:
            raise RuntimeError(f"size {size} меньше min_order_size {min_order_size} для {market}")

        # ✅ ИСПРАВЛЕНИЕ: берём цену из стакана, а не mark_price
        book_price = await _get_best_price_from_orderbook(market, side)

        if book_price is not None:
            # BUY: берём best_ask + slippage (гарантированно выше рынка → маркет исполнится)
            # SELL: берём best_bid - slippage (гарантированно ниже рынка → маркет исполнится)
            if side == OrderSide.BUY:
                price = book_price * (Decimal("1") + slippage_pct / Decimal("100"))
            else:
                price = book_price * (Decimal("1") - slippage_pct / Decimal("100"))
            logger.info("TASK %s MARKET price from orderbook: best=%s slippage=%s%% final=%s",
                        external_id, book_price, slippage_pct, price)
        else:
            # fallback на mark_price если стакан недоступен
            mark_price = await get_mark_price(market)
            logger.warning("TASK %s MARKET orderbook unavailable, fallback to mark_price=%s", external_id, mark_price)
            if side == OrderSide.BUY:
                price = mark_price * (Decimal("1") + slippage_pct / Decimal("100"))
            else:
                price = mark_price * (Decimal("1") - slippage_pct / Decimal("100"))

        price_decimal = _round_down(price, tick_size)
        size_decimal = _round_down(size, step_size)

        logger.info("TASK %s MARKET placing: market=%s side=%s size=%s price=%s (slippage=%s%% tick=%s step=%s)",
                    external_id, market, side.name, size_decimal, price_decimal, slippage_pct, tick_size, step_size)

        order_id = None
        final_status = "checking"

        try:
            placed = await asyncio.wait_for(
                place_with_retry(
                    blocking_client,
                    market_name=market,
                    amount_of_synthetic=size_decimal,
                    price=price_decimal,
                    side=side,
                    post_only=False,
                    external_id=external_id,
                ),
                timeout=20.0
            )
            order_id = str(getattr(placed, "id", "unknown"))
            final_status = "filled"
        except Exception as e:
            err_str = str(e).lower()
            if "already placed" in err_str or "hash already" in err_str:
                final_status = "filled"
            else:
                logger.warning("TASK %s MARKET SDK failed: %s → manual check", external_id, err_str)

        await asyncio.sleep(1.5)

        if final_status == "checking":
            status, data = await _extended_get("/api/v1/user/orders/open", params={"externalId": external_id})
            if isinstance(data, dict) and data.get("data"):
                order_id = str(data["data"][0].get("id", "unknown"))
                final_status = "placed"
            else:
                status, data = await _extended_get("/api/v1/user/orders/history", params={"externalId": external_id})
                if isinstance(data, dict) and data.get("data"):
                    order_id = str(data["data"][0].get("id", "unknown"))
                    final_status = "filled"
                else:
                    final_status = "accepted"

        ORDER_TASKS[external_id] = {
            "status": final_status,
            "order_id": order_id,
            "price": str(price_decimal),
            "size": str(size_decimal),
            "type": "MARKET",
            "finished": time.time(),
        }

    except Exception as e:
        logger.exception("TASK %s MARKET critical failure", external_id)
        ORDER_TASKS[external_id] = {"status": "error", "error": str(e), "order_id": None}

async def _get_position_size(market: str, side: str) -> Decimal:
    """✅ NEW: актуальный остаток позиции для дозакрытия."""
    try:
        status, data = await _extended_get("/api/v1/user/positions", params={"market": market, "side": side})
        if status != 200 or not isinstance(data, dict):
            return Decimal("0")
        positions = data.get("data", [])
        if not positions:
            return Decimal("0")
        return Decimal(str(positions[0].get("size", "0")))
    except Exception:
        return Decimal("0")

async def _close_position_task(external_id: str, market: str, side: OrderSide, size: Decimal, current_side: str):
    try:
        ORDER_TASKS[external_id] = {"status": "running", "started": time.time(), "error": None, "order_id": None}

        tick_size, step_size, _, min_order_size = await get_market_precision(market)

        if size < min_order_size:
            raise RuntimeError(f"size {size} меньше min_order_size {min_order_size} для {market}")

        slippage_pct = Decimal("2.0")

        async def _calc_close_price() -> Decimal:
            """✅ FIX: цена пересчитывается перед каждой попыткой."""
            book_price = await _get_best_price_from_orderbook(market, side)
            if book_price is None:
                logger.warning("TASK %s CLOSE orderbook unavailable, fallback to mark_price", external_id)
                book_price = await get_mark_price(market)
            if side == OrderSide.BUY:
                p = book_price * (Decimal("1") + slippage_pct / Decimal("100"))
            else:
                p = book_price * (Decimal("1") - slippage_pct / Decimal("100"))
            return _round_down(p, tick_size)

        order_id = None
        final_status = "checking"
        # ✅ FIX: до 3 попыток + валидация позиции (раньше — голый вызов, 8s timeout)
        max_attempts = 3
        last_err: Exception | None = None
        remaining = _round_down(size, step_size)

        for attempt in range(1, max_attempts + 1):
            if remaining < min_order_size:
                logger.info("TASK %s CLOSE: остаток %s < min_order_size %s → done",
                            external_id, remaining, min_order_size)
                break

            price_decimal = await _calc_close_price()
            attempt_external_id = external_id if attempt == 1 else f"{external_id}-r{attempt}"

            logger.info("TASK %s CLOSE attempt=%d: market=%s side=%s size=%s price=%s",
                        external_id, attempt, market, side.name, remaining, price_decimal)

            try:
                placed = await asyncio.wait_for(
                    blocking_client.create_and_place_order(
                        market_name=market,
                        amount_of_synthetic=remaining,
                        price=price_decimal,
                        side=side,
                        post_only=False,
                        external_id=attempt_external_id,
                    ),
                    timeout=20.0  # ✅ FIX: было 8s
                )
                order_id = str(getattr(placed, "id", "unknown"))
                logger.info("TASK %s CLOSE attempt=%d SDK ok order_id=%s", external_id, attempt, order_id)
                final_status = "filled"
            except asyncio.TimeoutError as e:
                logger.warning("TASK %s CLOSE attempt=%d: SDK timeout", external_id, attempt)
                last_err = e
            except Exception as e:
                last_err = e
                if _is_idempotent_already_placed(e):
                    logger.info("TASK %s CLOSE attempt=%d: already placed (идемпотентно)", external_id, attempt)
                    final_status = "filled"
                else:
                    logger.warning("TASK %s CLOSE attempt=%d SDK failed: %s", external_id, attempt, e)

            # ✅ FIX: после каждой попытки — реально смотрим остаток
            await asyncio.sleep(1.0)
            current_size = await _get_position_size(market, current_side)
            if current_size <= Decimal("0") or current_size < min_order_size:
                logger.info("TASK %s CLOSE: confirmed closed after attempt %d", external_id, attempt)
                final_status = "closed_confirmed"
                break
            remaining = _round_down(current_size, step_size)
            logger.warning("TASK %s CLOSE attempt=%d: остаток %s, ретрай",
                           external_id, attempt, remaining)
            await asyncio.sleep(1.0)

        if final_status == "checking":
            status, data = await _extended_get("/api/v1/user/orders/history", params={"externalId": external_id})
            if isinstance(data, dict) and data.get("data"):
                order_id = str(data["data"][0].get("id", "unknown"))
                final_status = "filled"

        # Финальная валидация
        logger.info("TASK %s CLOSE: VALIDATING position closure for %s %s...",
                    external_id, market, current_side)
        closed = await _wait_position_closed(market, current_side, timeout_sec=25.0)

        if closed:
            logger.info("TASK %s CLOSE: position CONFIRMED closed", external_id)
            final_status = "closed_confirmed"
        else:
            logger.warning("TASK %s CLOSE: NOT confirmed closed (last_err=%s)", external_id, last_err)
            final_status = "closed_timeout"

        ORDER_TASKS[external_id] = {
            "status": final_status,
            "order_id": order_id,
            "price": str(price_decimal),
            "size": str(size_decimal),
            "type": "CLOSE_POSITION",
            "finished": time.time(),
        }

    except Exception as e:
        logger.exception("TASK %s CLOSE critical failure", external_id)
        ORDER_TASKS[external_id] = {"status": "error", "error": str(e), "order_id": None}

# -------------------- routes --------------------

@app.route("/health", methods=["GET"])
def health():
    # ✅ FIX: расширенный healthcheck для оркестратора
    sdk_ok = blocking_client is not None
    session_ok = _HTTP_SESSION is not None and not _HTTP_SESSION.closed
    return jsonify({
        "status": "ok" if (sdk_ok and session_ok) else "degraded",
        "vault": VAULT_ID,
        "proxy": HTTP_PROXY or "NONE",
        "aiohttp_timeout": "total=30 connect=10 sock_read=20",
        "api_key_set": bool(API_KEY),
        "market_cache_ttl_sec": _MARKET_CACHE_TTL_SEC,
        "sdk_ready": sdk_ok,
        "http_session_ready": session_ok,
        "sdk_last_fail_ts": _SDK_LAST_FAIL_TS,
    })

@app.route("/admin/reinit", methods=["POST"])
def admin_reinit():
    """✅ NEW: ручной reinit SDK без рестарта процесса."""
    global blocking_client
    try:
        blocking_client = None
        _submit(_reinit_sdk_if_needed(), timeout_sec=60)
        return jsonify({"status": "ok", "reinit": True})
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route("/order", methods=["POST"])
def place_order():
    body = request.get_json(force=True) or {}
    market = body.get("market", "BTC-USD")
    side_str = str(body.get("side", "BUY")).upper()
    side = OrderSide.BUY if side_str == "BUY" else OrderSide.SELL
    size = Decimal(str(body.get("size", "0.01")))
    post_only = bool(body.get("post_only", False))
    price_offset_pct = Decimal(str(body.get("price_offset_pct", "0.01")))
    external_id = body.get("external_id") or secrets.token_hex(16)

    ORDER_TASKS[external_id] = {"status": "queued", "error": None, "order_id": None}

    fut = asyncio.run_coroutine_threadsafe(
        _place_order_task(external_id, market, side, size, post_only, price_offset_pct),
        loop
    )
    ORDER_FUTURES[external_id] = fut

    return jsonify({
        "status": "accepted",
        "external_id": external_id,
        "market": market,
        "side": side.name,
        "size": str(size),
        "post_only": post_only,
        "price_offset_pct": str(price_offset_pct),
        "type": "LIMIT"
    }), 202

@app.route("/order/market", methods=["POST"])
def place_market_order():
    body = request.get_json(force=True) or {}
    market = body.get("market", "BTC-USD")
    side_str = str(body.get("side", "BUY")).upper()
    side = OrderSide.BUY if side_str == "BUY" else OrderSide.SELL
    size = Decimal(str(body.get("size", "0.01")))
    slippage_pct = Decimal(str(body.get("price_slippage_pct", "2.0")))
    external_id = body.get("external_id") or secrets.token_hex(16)

    ORDER_TASKS[external_id] = {"status": "queued", "error": None, "order_id": None}

    fut = asyncio.run_coroutine_threadsafe(
        _place_market_order_task(external_id, market, side, size, slippage_pct),
        loop
    )
    ORDER_FUTURES[external_id] = fut

    return jsonify({
        "status": "accepted",
        "external_id": external_id,
        "market": market,
        "side": side.name,
        "size": str(size),
        "slippage_pct": str(slippage_pct),
        "type": "MARKET"
    }), 202

@app.route("/positions/close", methods=["POST"])
def close_position():
    body = request.get_json(force=True) or {}
    market = body.get("market")
    current_side = str(body.get("current_side")).upper()
    if not market or not current_side:
        return jsonify({"status": "error", "message": "market and current_side required"}), 400

    status, data = _submit(_extended_get("/api/v1/user/positions", params={"market": market}), timeout_sec=30)
    if status != 200:
        return jsonify({"status": "error", "http_status": status, "response": data}), status

    positions = data.get("data", []) if isinstance(data, dict) else []
    position = next((p for p in positions if p.get("side") == current_side), None)
    if not position:
        return jsonify({"status": "error", "message": "position not found"}), 404

    size = Decimal(str(position.get("size", "0")))
    if size <= 0:
        return jsonify({"status": "error", "message": "zero size position"}), 400

    close_side = OrderSide.SELL if current_side == "LONG" else OrderSide.BUY
    external_id = body.get("external_id") or secrets.token_hex(16)

    ORDER_TASKS[external_id] = {"status": "queued", "error": None, "order_id": None}

    fut = asyncio.run_coroutine_threadsafe(
        _close_position_task(external_id, market, close_side, size, current_side),
        loop
    )
    ORDER_FUTURES[external_id] = fut

    return jsonify({
        "status": "accepted",
        "external_id": external_id,
        "market": market,
        "close_side": close_side.name,
        "size": str(size),
        "type": "CLOSE_POSITION"
    }), 202

@app.route("/order/status/<external_id>", methods=["GET"])
def order_status(external_id: str):
    data = ORDER_TASKS.get(external_id)
    if not data:
        return jsonify({"status": "error", "message": "unknown external_id"}), 404
    return jsonify({"status": "ok", "external_id": external_id, "task": data})

@app.route("/order/kill/<external_id>", methods=["POST"])
def kill_order_task(external_id: str):
    fut = ORDER_FUTURES.get(external_id)
    if not fut:
        return jsonify({"status": "error", "message": "unknown external_id"}), 404
    cancelled = fut.cancel()
    return jsonify({"status": "ok", "external_id": external_id, "cancel_called": cancelled})

@app.route("/order/cancel", methods=["POST"])
def cancel_order():
    body = request.get_json(force=True) or {}
    external_id = body.get("external_id")
    if not external_id:
        return jsonify({"status": "error", "message": "external_id required"}), 400

    try:
        status, data = _submit(_extended_delete("/api/v1/user/order", params={"externalId": external_id}), timeout_sec=30)
        if status == 200:
            return jsonify({"status": "ok", "external_id": external_id, "response": data})
        return jsonify({"status": "error", "http_status": status, "response": data}), status
    except Exception as e:
        logger.exception("cancel_order failed")
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route("/orders/open", methods=["GET"])
def orders_open():
    market = request.args.get("market")
    external_id = request.args.get("externalId")

    try:
        params = {}
        if market:
            params["market"] = market
        if external_id:
            params["externalId"] = external_id
        status, data = _submit(_extended_get("/api/v1/user/orders/open", params), timeout_sec=30)
        return jsonify(data), status
    except Exception as e:
        logger.exception("orders_open failed")
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route("/orders/history", methods=["GET"])
def orders_history():
    market = request.args.get("market")
    external_id = request.args.get("externalId")

    try:
        params = {}
        if market:
            params["market"] = market
        if external_id:
            params["externalId"] = external_id
        status, data = _submit(_extended_get("/api/v1/user/orders/history", params), timeout_sec=30)
        return jsonify(data), status
    except Exception as e:
        logger.exception("orders_history failed")
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route("/markets", methods=["GET"])
def get_markets():
    async def _fetch():
        markets_dict = await blocking_client.get_markets()
        return {name: {"id": m.id, "base": m.base_currency, "quote": m.quote_currency} for name, m in markets_dict.items()}

    try:
        markets = _submit(_fetch(), timeout_sec=15)
        return jsonify({"status": "ok", "count": len(markets), "markets": markets})
    except Exception as e:
        logger.exception("get_markets failed")
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route("/positions", methods=["GET"])
def positions():
    """
    ✅ ИСПРАВЛЕНО: добавлен polling для ожидания появления позиции.
    Параметр wait=true включает ожидание (до 20 сек).
    """
    market = request.args.get("market")
    side = request.args.get("side")
    wait = request.args.get("wait", "false").lower() == "true"

    try:
        params = {}
        if market:
            params["market"] = market
        if side:
            params["side"] = side.upper()

        if wait and market and side:
            # Polling режим: ждём появления позиции
            async def _poll():
                found = await _wait_position_opened(market, side.upper(), timeout_sec=20.0)
                # Возвращаем финальный результат
                s, d = await _extended_get("/api/v1/user/positions", params=params)
                return s, d, found

            status, data, found = _submit(_poll(), timeout_sec=25)
            if isinstance(data, dict):
                data["_waited"] = True
                data["_found"] = found
            return jsonify(data), status
        else:
            status, data = _submit(_extended_get("/api/v1/user/positions", params), timeout_sec=30)
            return jsonify(data), status

    except Exception as e:
        logger.exception("positions failed")
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route("/user/leverage", methods=["PATCH"])
def set_leverage():
    body = request.get_json(force=True) or {}
    market = body.get("market")
    leverage = body.get("leverage")
    if not market or leverage is None:
        return jsonify({"status": "ERROR", "message": "market and leverage required"}), 400

    try:
        status, data = _submit(
            _extended_patch("/api/v1/user/leverage", json_body={"market": market, "leverage": str(leverage)}),
            timeout_sec=30
        )
        return jsonify(data), status
    except Exception as e:
        logger.exception("set_leverage failed")
        return jsonify({"status": "ERROR", "message": str(e)}), 500

@app.route("/balance", methods=["GET"])
def get_balance():
    try:
        status, data = _submit(_extended_get("/api/v1/user/balance"), timeout_sec=30)
        return jsonify(data), status
    except Exception as e:
        logger.exception("get_balance failed")
        return jsonify({"status": "ERROR", "message": str(e)}), 500

@app.route("/market/info/<market_name>", methods=["GET"])
def get_market_info(market_name: str):
    try:
        async def _fetch():
            tick_size, step_size, asset_precision, min_order_size = await get_market_precision(market_name)
            return {
                "market": market_name,
                "tick_size": str(tick_size),
                "step_size": str(step_size),
                "min_order_size": str(min_order_size),
                "asset_precision": asset_precision,
            }

        result = _submit(_fetch(), timeout_sec=15)
        return jsonify(result), 200
    except Exception as e:
        logger.exception("get_market_info failed")
        return jsonify({"error": str(e)}), 500

@app.route("/market/price/<market_name>", methods=["GET"])
def get_market_price(market_name: str):
    try:
        async def _fetch():
            return str(await get_mark_price(market_name))

        mark_price = _submit(_fetch(), timeout_sec=15)
        return Response(mark_price, mimetype='text/plain'), 200
    except Exception as e:
        logger.exception("get_market_price failed")
        return Response("0.0", mimetype='text/plain'), 500

@app.route("/api/v1/info/markets", methods=["GET"])
def get_markets_info_proxy():
    market = request.args.get("market")
    try:
        params = {}
        if market:
            params["market"] = market
        status, data = _submit(_extended_get("/api/v1/info/markets", params), timeout_sec=15)
        return jsonify(data), status
    except Exception as e:
        logger.exception("get_markets_info_proxy failed")
        return jsonify({"status": "ERROR", "message": str(e)}), 500

@app.route("/funding/history", methods=["GET"])
def get_funding_history():
    market = request.args.get("market")
    side = request.args.get("side")
    from_time = request.args.get("fromTime")
    limit = request.args.get("limit", "100")

    try:
        params = {}
        if market:
            params["market"] = market
        if side:
            params["side"] = side.upper()
        if from_time:
            params["fromTime"] = from_time
        params["limit"] = limit

        logger.info("GET funding history: params=%s", params)

        status, data = _submit(
            _extended_get("/api/v1/user/funding/history", params),
            timeout_sec=30
        )

        if status == 200 and isinstance(data, dict) and data.get("data"):
            payments = data.get("data", [])
            from_time_ms = int(from_time) if from_time else 0
            filtered_payments = [
                p for p in payments
                if p.get("paidTime", 0) >= from_time_ms
            ]

            total_received = 0.0
            total_paid = 0.0

            for payment in filtered_payments:
                fee = float(payment.get("fundingFee", 0))
                if fee > 0:
                    total_received += fee
                else:
                    total_paid += abs(fee)

            data["summary"] = {
                "total_received": total_received,
                "total_paid": total_paid,
                "net_funding": total_received - total_paid,
                "payments_count": len(filtered_payments)
            }

            logger.info("Extended funding history: total=%d filtered=%d received=%.4f paid=%.4f net=%.4f",
                       len(payments), len(filtered_payments),
                       total_received, total_paid, total_received - total_paid)

        return jsonify(data), status

    except Exception as e:
        logger.exception("get_funding_history failed")
        return jsonify({"status": "ERROR", "message": str(e)}), 500

@app.route("/api/v1/info/markets/<market>/orderbook", methods=["GET"])
def get_order_book(market: str):
    try:
        status, data = _submit(
            _extended_get(f"/api/v1/info/markets/{market}/orderbook"),
            timeout_sec=10
        )

        if status == 200 and isinstance(data, dict):
            logger.info("Order book %s: bids=%d asks=%d",
                       market,
                       len(data.get("data", {}).get("bid", [])),
                       len(data.get("data", {}).get("ask", [])))

        return jsonify(data), status
    except Exception as e:
        logger.exception("get_order_book failed")
        return jsonify({"status": "ERROR", "message": str(e)}), 500

@app.route("/api/v1/info/markets/<market>/stats", methods=["GET"])
def get_market_stats(market: str):
    try:
        status, data = _submit(
            _extended_get(f"/api/v1/info/markets/{market}/stats"),
            timeout_sec=10
        )

        if status == 200 and isinstance(data, dict):
            stats = data.get("data", {})
            logger.info("Market stats %s: mark=%s bid=%s ask=%s",
                       market,
                       stats.get("markPrice"),
                       stats.get("bidPrice"),
                       stats.get("askPrice"))

        return jsonify(data), status
    except Exception as e:
        logger.exception("get_market_stats failed")
        return jsonify({"status": "ERROR", "message": str(e)}), 500

@app.route("/api/v1/info/markets/<market>/trades", methods=["GET"])
def get_recent_trades(market: str):
    try:
        status, data = _submit(
            _extended_get(f"/api/v1/info/markets/{market}/trades"),
            timeout_sec=10
        )

        if status == 200 and isinstance(data, dict):
            trades = data.get("data", [])
            logger.info("Recent trades %s: count=%d", market, len(trades))

        return jsonify(data), status
    except Exception as e:
        logger.exception("get_recent_trades failed")
        return jsonify({"status": "ERROR", "message": str(e)}), 500

@app.route("/market/<market>/execution-price", methods=["POST"])
def estimate_execution_price(market: str):
    try:
        body = request.get_json(force=True) or {}
        size = Decimal(str(body.get("size", "0")))
        side = str(body.get("side", "BUY")).upper()

        if size <= 0:
            return jsonify({"status": "ERROR", "message": "invalid size"}), 400

        status, book_data = _submit(
            _extended_get(f"/api/v1/info/markets/{market}/orderbook"),
            timeout_sec=10
        )

        if status != 200 or not isinstance(book_data, dict):
            return jsonify({"status": "ERROR", "message": "failed to get order book"}), status

        book = book_data.get("data", {})
        levels = book.get("bid" if side == "SELL" else "ask", [])

        if not levels:
            return jsonify({"status": "ERROR", "message": "no liquidity"}), 404

        remaining = size
        total_cost = Decimal("0")
        filled_qty = Decimal("0")

        for level in levels:
            level_qty = Decimal(str(level.get("qty", "0")))
            level_price = Decimal(str(level.get("price", "0")))

            if remaining <= Decimal("0"):
                break

            fill_qty = min(remaining, level_qty)
            total_cost += fill_qty * level_price
            filled_qty += fill_qty
            remaining -= fill_qty

        if filled_qty <= Decimal("0"):
            return jsonify({"status": "ERROR", "message": "insufficient liquidity"}), 400

        avg_price = total_cost / filled_qty
        insufficient = remaining > Decimal("0")

        result = {
            "status": "OK",
            "market": market,
            "side": side,
            "requested_size": str(size),
            "filled_size": str(filled_qty),
            "remaining_size": str(remaining),
            "avg_execution_price": str(avg_price),
            "total_cost": str(total_cost),
            "insufficient_liquidity": insufficient,
            "levels_used": len([l for l in levels if Decimal(str(l.get("qty", "0"))) > 0])
        }

        logger.info("Execution price %s %s %.2f: avg=%.6f insufficient=%s",
                   market, side, float(size), float(avg_price), insufficient)

        return jsonify(result), 200

    except Exception as e:
        logger.exception("estimate_execution_price failed")
        return jsonify({"status": "ERROR", "message": str(e)}), 500

@app.route('/positions/history', methods=['GET'])
def get_positions_history():
    market = request.args.get('market')
    side = request.args.get('side')
    try:
        params = {}
        if market:
            params['market'] = market
        if side:
            params['side'] = side.upper()

        status, data = _submit(
            _extended_get('/api/v1/user/positions/history', params=params),
            timeout_sec=30
        )
        return jsonify(data), status
    except Exception as e:
        logger.exception("get_positions_history failed")
        return jsonify({"status": "error", "message": str(e)}), 500



if __name__ == "__main__":
    logger.info("🌐 Starting on port %s | API_KEY=%s | VAULT=%s", PORT, bool(API_KEY), VAULT_ID)
    app.run(host="0.0.0.0", port=PORT, debug=False, threaded=True)