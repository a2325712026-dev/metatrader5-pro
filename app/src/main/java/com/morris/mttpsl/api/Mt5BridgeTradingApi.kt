package com.morris.mttpsl.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 连接用户自己的 Windows MT5 桥接服务 (server/，FastAPI + MetaTrader5 Python 包)。
 * serverUrl / apiKey 在 App 设置中运行时输入，不写入代码。
 */
/** /order 请求体（单独函数便于单元测试确认 tp/sl 已发送；0 = 不设置） */
internal fun orderBody(symbol: String, side: Side, volume: Double, sl: Double?, tp: Double?, comment: String = ""): JSONObject =
    JSONObject().put("symbol", symbol).put("side", side.name).put("volume", volume)
        .put("sl", sl ?: 0.0).put("tp", tp ?: 0.0).put("comment", comment)

/** 一条 K 线是否可用：时间 > 0，价格有限且 > 0，high ≥ low（坏数据直接丢弃，避免图表出现 NaN / 除零） */
internal fun validCandle(c: Candle) =
    c.time > 0 && listOf(c.open, c.high, c.low, c.close).all { it.isFinite() && it > 0.0 } && c.high >= c.low

/** 报价是否可用：bid/ask 有限且 > 0 */
internal fun validQuote(bid: Double, ask: Double) = bid.isFinite() && ask.isFinite() && bid > 0.0 && ask > 0.0

/** 解析一条 WebSocket 报价；格式不对（缺字段 / 非 JSON / 价格为 0 或 NaN）返回 null，由调用方忽略 */
internal fun parseTick(symbol: String, text: String): Quote? = try {
    val o = JSONObject(text)
    val bid = o.getDouble("bid"); val ask = o.getDouble("ask")
    if (validQuote(bid, ask)) Quote(symbol, bid, ask, o.optLong("time_msc", System.currentTimeMillis())) else null
} catch (_: Exception) { null }

/** 重连等待：1s, 2s, 4s … 最长 15s */
internal fun nextBackoff(ms: Long): Long = (ms * 2).coerceIn(1000L, 15_000L)

/** 逐项解析：某一项缺字段 / 格式错误只丢弃该项，不让整个列表（持仓 / 挂单 / 历史）失败 */
internal inline fun <T> JSONArray.mapTolerant(f: (JSONObject) -> T?): List<T> =
    (0 until length()).mapNotNull { i -> try { f(getJSONObject(i)) } catch (_: Exception) { null } }

class Mt5BridgeTradingApi(
    serverUrl: String,
    private val apiKey: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build(),
) : TradingApi {
    private val base = normalizeServerUrl(serverUrl)
    private val json = "application/json".toMediaType()

    private fun req(path: String) = Request.Builder().url("$base$path").header("X-API-Key", apiKey)

    private suspend fun call(r: Request): String = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, body.take(300))
            body
        }
    }
    private suspend fun get(path: String) = call(req(path).get().build())
    private suspend fun post(path: String, b: JSONObject) = call(req(path).post(b.toString().toRequestBody(json)).build())
    private suspend fun delete(path: String) = call(req(path).delete().build())

    private fun tradeResult(s: String): TradeResult {
        val o = JSONObject(s)
        return if (o.optBoolean("ok")) TradeResult.Ok(o.optString("retcode"))
        else TradeResult.Error(o.optString("comment", o.optString("detail", "error")) +
            (if (o.isNull("retcode")) "" else " (${o.optString("retcode")})"))
    }
    private suspend fun trade(block: suspend () -> String): TradeResult =
        try { tradeResult(block()) } catch (e: Exception) { TradeResult.Error(e.message ?: "network error") }

    private fun JSONObject.priceOrNull(k: String) = optDouble(k, 0.0).takeIf { it > 0 }
    private fun JSONObject.putExp(e: Expiration) = apply {
        put("expiration_mode", e.mode.name)
        e.utcEpochSec?.let { put("expiration", it) }
    }

    override suspend fun symbolSpec(symbol: String): SymbolSpec {
        val o = JSONObject(get("/symbol/$symbol"))
        val digits = o.getInt("digits"); val point = o.getDouble("point")
        // point = 0 / digits 离谱 会导致后面处处除零 / BigDecimal 崩溃 → 当作服务器数据错误上报
        if (!point.isFinite() || point <= 0.0 || digits !in 0..10) throw IllegalStateException("服务器返回的品种参数无效: digits=$digits point=$point")
        return SymbolSpec(symbol, digits, point, o.optInt("trade_stops_level", 0))
    }

    override suspend fun candles(symbol: String, timeframe: String, limit: Int, offset: Int): List<Candle> {
        val a = JSONArray(get("/candles?symbol=$symbol&timeframe=$timeframe&count=$limit" + if (offset > 0) "&start=$offset" else ""))
        return a.mapTolerant { c ->
            Candle(c.getLong("time") * 1000, c.getDouble("open"), c.getDouble("high"), c.getDouble("low"), c.getDouble("close"))
                .takeIf(::validCandle)
        }.sortedBy { it.time }
    }

    /** 单次 WebSocket 连接；断开 / 出错时以异常结束 */
    private fun socketQuotes(symbol: String): Flow<Quote> = callbackFlow {
        val wsUrl = base.replaceFirst("http", "ws") + "/ws/ticks?symbol=$symbol"
        val ws = http.newWebSocket(req("").url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseTick(symbol, text)?.let { trySend(it) } // 坏报文直接忽略，不终止连接
            }
            // 服务器主动关闭：先回应关闭帧，再结束本次连接 → 外层 quotes() 自动重连（之前不处理 onClosing，断线后报价流会一直挂着）
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); close(IllegalStateException("WebSocket closing: $code $reason")) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close(IllegalStateException("WebSocket closed: $code $reason")) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
        })
        awaitClose { ws.cancel() }
    }

    /** 报价流：连接断开 / 失败后自动重连（指数退避，收到报价后重置）；只有取消才会结束 */
    override fun quotes(symbol: String): Flow<Quote> = flow {
        var wait = 1000L
        while (true) {
            try {
                socketQuotes(symbol).collect { wait = 1000L; emit(it) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* 重连 */ }
            delay(wait); wait = nextBackoff(wait)
        }
    }

    override suspend fun quoteList(symbols: List<String>): List<SymbolQuote> {
        val a = JSONArray(get("/quotes?symbols=${symbols.joinToString(",")}"))
        return a.mapTolerant { o ->
            val bid = o.getDouble("bid"); val ask = o.getDouble("ask")
            if (!validQuote(bid, ask)) null
            else SymbolQuote(o.getString("symbol"), bid, ask, o.optInt("digits", 5),
                o.optDouble("point", 0.00001), o.optInt("spread", 0), o.optDouble("change", 0.0), o.optLong("time_msc", 0))
        }
    }

    override suspend fun account(): AccountInfo {
        val o = JSONObject(get("/account"))
        return AccountInfo(o.optString("login"), o.optString("server"), o.optString("currency"),
            o.getDouble("balance"), o.getDouble("equity"), o.getDouble("margin"), o.getDouble("margin_free"),
            o.optInt("leverage"))
    }

    override suspend fun positions(): List<Position> {
        val a = JSONArray(get("/positions"))
        return a.mapTolerant { p ->
            Position(
                p.getString("ticket"), p.getString("symbol"),
                if (p.getString("type") == "BUY") Side.BUY else Side.SELL,
                p.getDouble("volume"), p.getDouble("price_open"),
                p.priceOrNull("tp"), p.priceOrNull("sl"),
                p.optDouble("price_current", p.getDouble("price_open")), p.optDouble("profit", 0.0),
            ).takeIf { it.volume > 0 && it.openPrice.isFinite() && it.openPrice > 0 }
        }
    }

    override suspend fun orders(): List<PendingOrder> {
        val a = JSONArray(get("/orders"))
        return a.mapTolerant { o ->
            val type = PendingType.of(o.getString("type")) ?: return@mapTolerant null
            val mode = runCatching { ExpirationMode.valueOf(o.optString("type_time", "GTC")) }.getOrDefault(ExpirationMode.GTC)
            PendingOrder(o.getString("ticket"), o.getString("symbol"), type, o.getDouble("volume"),
                o.getDouble("price"), o.priceOrNull("stoplimit"), o.priceOrNull("tp"), o.priceOrNull("sl"),
                Expiration(mode, o.optLong("expiration", 0).takeIf { it > 0 }))
                .takeIf { it.price.isFinite() && it.price > 0 }
        }
    }

    override suspend fun history(days: Int): List<Deal> {
        val a = JSONArray(get("/history?days=$days"))
        return a.mapTolerant { d ->
            Deal(d.getString("ticket"), d.optString("position_id"), d.getLong("time"), d.getString("symbol"),
                if (d.getString("type") == "BUY") Side.BUY else Side.SELL, d.optString("entry"),
                d.getDouble("volume"), d.getDouble("price"), d.getDouble("profit"),
                d.optDouble("commission", 0.0), d.optDouble("swap", 0.0))
        }
    }

    override suspend fun marketOrder(symbol: String, side: Side, volume: Double, sl: Double?, tp: Double?, comment: String) = trade {
        post("/order", orderBody(symbol, side, volume, sl, tp, comment))
    }

    override suspend fun calcMargin(symbol: String, side: Side, volume: Double, price: Double?): Double? = try {
        JSONObject(get("/margin?symbol=$symbol&side=${side.name}&volume=$volume" + (price?.let { "&price=$it" } ?: "")))
            .optDouble("margin", Double.NaN).takeIf { !it.isNaN() }
    } catch (e: Exception) { null }

    override suspend fun modifyPosition(positionId: String, takeProfit: Double?, stopLoss: Double?) = trade {
        post("/positions/$positionId/modify", JSONObject().put("tp", takeProfit ?: 0.0).put("sl", stopLoss ?: 0.0))
    }

    override suspend fun closePosition(positionId: String) = trade { post("/positions/$positionId/close", JSONObject()) }

    override suspend fun placePending(
        symbol: String, type: PendingType, volume: Double, price: Double, stopLimit: Double?,
        sl: Double?, tp: Double?, expiration: Expiration, comment: String,
    ) = trade {
        post("/pending", JSONObject().put("symbol", symbol).put("type", type.apiName).put("volume", volume)
            .put("price", price).put("stoplimit", stopLimit ?: 0.0).put("sl", sl ?: 0.0).put("tp", tp ?: 0.0)
            .put("comment", comment).putExp(expiration))
    }

    override suspend fun modifyOrder(
        ticket: String, price: Double, stopLimit: Double?, sl: Double?, tp: Double?, expiration: Expiration,
    ) = trade {
        post("/orders/$ticket/modify", JSONObject().put("price", price).put("stoplimit", stopLimit ?: 0.0)
            .put("sl", sl ?: 0.0).put("tp", tp ?: 0.0).putExp(expiration))
    }

    override suspend fun cancelOrder(ticket: String) = trade { delete("/orders/$ticket") }
}
