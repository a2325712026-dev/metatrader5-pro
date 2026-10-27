package com.morris.mttpsl.api

import kotlinx.coroutines.flow.Flow

data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double)
data class Quote(val symbol: String, val bid: Double, val ask: Double, val time: Long)
enum class Side { BUY, SELL }

data class Position(
    val id: String, val symbol: String, val side: Side, val volume: Double,
    val openPrice: Double, val takeProfit: Double?, val stopLoss: Double?,
    val currentPrice: Double = openPrice, val profit: Double = 0.0,
)

data class SymbolSpec(val symbol: String, val digits: Int, val point: Double, val stopsLevelPoints: Int) {
    /** 外汇 5/3 位报价：1 pip = 10 points */
    val pip: Double get() = if (digits == 5 || digits == 3) point * 10 else point
}

data class SymbolQuote(
    val symbol: String, val bid: Double, val ask: Double, val digits: Int, val point: Double,
    val spreadPoints: Int, val changePct: Double, val time: Long,
)

data class AccountInfo(
    val login: String, val server: String, val currency: String, val balance: Double, val equity: Double,
    val margin: Double, val freeMargin: Double, val leverage: Int,
) {
    val profit: Double get() = equity - balance
    val marginLevel: Double? get() = if (margin > 0) equity / margin * 100 else null
}

enum class PendingType(val apiName: String, val label: String, val side: Side, val isStopLimit: Boolean) {
    BUY_LIMIT("BUY_LIMIT", "Buy Limit", Side.BUY, false),
    SELL_LIMIT("SELL_LIMIT", "Sell Limit", Side.SELL, false),
    BUY_STOP("BUY_STOP", "Buy Stop", Side.BUY, false),
    SELL_STOP("SELL_STOP", "Sell Stop", Side.SELL, false),
    BUY_STOP_LIMIT("BUY_STOP_LIMIT", "Buy Stop Limit", Side.BUY, true),
    SELL_STOP_LIMIT("SELL_STOP_LIMIT", "Sell Stop Limit", Side.SELL, true);

    companion object { fun of(api: String) = entries.firstOrNull { it.apiName == api } }
}

enum class ExpirationMode(val label: String) { GTC("GTC 撤销前有效"), DAY("当日有效"), SPECIFIED("指定时间") }
data class Expiration(val mode: ExpirationMode = ExpirationMode.GTC, val utcEpochSec: Long? = null)

data class PendingOrder(
    val ticket: String, val symbol: String, val type: PendingType, val volume: Double,
    val price: Double, val stopLimit: Double?, val takeProfit: Double?, val stopLoss: Double?,
    val expiration: Expiration = Expiration(),
)

data class Deal(
    val ticket: String, val positionId: String, val time: Long, val symbol: String, val side: Side,
    val entry: String, val volume: Double, val price: Double, val profit: Double,
    val commission: Double, val swap: Double,
)

sealed class TradeResult {
    data class Ok(val code: String) : TradeResult()
    data class Error(val message: String) : TradeResult()
}

/** 平台无关接口（默认 MT5 桥接 / 演示数据） */
interface TradingApi {
    suspend fun symbolSpec(symbol: String): SymbolSpec
    /** offset = 从最新 K 线往回跳过的根数（0 = 最新），返回按时间升序 */
    suspend fun candles(symbol: String, timeframe: String, limit: Int, offset: Int = 0): List<Candle>
    fun quotes(symbol: String): Flow<Quote>
    suspend fun quoteList(symbols: List<String>): List<SymbolQuote>
    suspend fun account(): AccountInfo
    suspend fun positions(): List<Position>
    suspend fun orders(): List<PendingOrder>
    suspend fun history(days: Int): List<Deal>
    suspend fun marketOrder(symbol: String, side: Side, volume: Double, sl: Double?, tp: Double?, comment: String = ""): TradeResult
    /** 预估所需预付款（账户货币），不可用时返回 null */
    suspend fun calcMargin(symbol: String, side: Side, volume: Double, price: Double? = null): Double?
    suspend fun modifyPosition(positionId: String, takeProfit: Double?, stopLoss: Double?): TradeResult
    suspend fun closePosition(positionId: String): TradeResult
    suspend fun placePending(
        symbol: String, type: PendingType, volume: Double, price: Double, stopLimit: Double?,
        sl: Double?, tp: Double?, expiration: Expiration, comment: String = "",
    ): TradeResult
    suspend fun modifyOrder(
        ticket: String, price: Double, stopLimit: Double?, sl: Double?, tp: Double?, expiration: Expiration,
    ): TradeResult
    suspend fun cancelOrder(ticket: String): TradeResult
}
