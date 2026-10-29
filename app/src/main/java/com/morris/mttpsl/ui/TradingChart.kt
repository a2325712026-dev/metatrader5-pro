package com.morris.mttpsl.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morris.mttpsl.api.Candle
import com.morris.mttpsl.api.PendingOrder
import com.morris.mttpsl.api.Side
import com.morris.mttpsl.api.SymbolSpec
import kotlin.math.abs

object ChartColors {
    val Bg = Color(0xFF000000)
    val Grid = Color(0xFF1E2329)
    val Bull = Color(0xFF00C853)
    val Bear = Color(0xFFFF1744)
    val Bid = Color(0xFFB0BEC5)
    val Ask = Color(0xFFE57373)
    val Entry = Color(0xFFFFB300)
    val StopLimit = Color(0xFFBA68C8)
    val Tp = Color(0xFF4CAF50)
    val Sl = Color(0xFFF44336)
    val Axis = Color(0xFF9AA0A6)
}

/**
 * 拖动中的实时价格（每条线一个 State）。由图表与下单面板共享：
 * 拖动时只更新这些 State → 图表只重绘、面板只重组显示价格的那一个 Text，
 * 松手后才提交到 ViewModel。
 */
@Stable
class LiveLines {
    val v = List(4) { mutableDoubleStateOf(Double.NaN) }
    val dragging = mutableIntStateOf(-1)
    /** 拖动中返回实时价，否则返回已提交的价格 */
    fun value(id: Int, committed: Double?): Double? =
        if (dragging.intValue >= 0) v[id].doubleValue.takeIf { !it.isNaN() } ?: committed else committed
}

/** 可拖动的线（id = LineId.*） */
@Immutable
data class DragLine(val id: Int, val price: Double?, val visible: Boolean, val color: Color, val name: String)

/** 只读的线（已有持仓/挂单） */
@Immutable
data class StaticLine(
    val price: Double, val color: Color, val label: String, val positionId: String? = null,
    /** 持仓的 TP/SL 线：posTicket = 持仓 id，kind = [LineId.TP]/[LineId.SL]（可上下拖动改价）；其他线为 null / -1 */
    val posTicket: String? = null, val kind: Int = -1,
)

/** 图上直接拖动挂单线时的实时状态（只在拖动期间有值；绘制读取它 → 只重绘不重组） */
@Stable
class LiveOrder {
    /** true = 正在拖动的是持仓的 TP/SL 线（ticket 是持仓 id） */
    var isPosition by mutableStateOf(false)
    var ticket by mutableStateOf<String?>(null)
    var kind by mutableIntStateOf(-1)
    var price by mutableDoubleStateOf(Double.NaN)
}

private val Dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
private val ShortDash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
private const val AXIS_W = 150f

/** 视口与布局尺寸：手势直接改它，绘制阶段读取 → 平移/缩放只触发重绘，不重组 */
@Stable
class ChartView {
    var vp by mutableStateOf(Viewport())
    var w by mutableFloatStateOf(1f)   // 绘图区宽（不含价格轴）
    var h by mutableFloatStateOf(1f)
    var cross by mutableStateOf<Offset?>(null)
}

@Composable
fun TradingChart(
    candles: List<Candle>,
    spec: SymbolSpec,
    bid: Double?,
    ask: Double?,
    lines: List<DragLine>,          // 固定 4 个，按 LineId 索引
    side: Side,
    pendingMode: Boolean,
    staticLines: List<StaticLine>,
    onCommit: (Map<Int, Double>) -> Unit,
    live: LiveLines = remember { LiveLines() },
    modifier: Modifier = Modifier,
    viewportKey: Any = Unit,               // 品种/周期变化时重置视口
    onNeedHistory: () -> Unit = {},
    /** TP/SL 线标签的预计盈亏文字（USD）：参数 = 线价格、挂单时的实时开仓价（其它模式为 null） */
    pnlLabel: ((Double, Double?) -> String?)? = null,
    /** 点击持仓线（或其 ✕ 按钮）→ 参数为持仓 ticket；不影响 TP/SL 拖动与平移缩放 */
    onPositionTap: (String) -> Unit = {},
    /** 已有挂单（委托订单）：开仓价线、Stop Limit 限价线、TP/SL 线都可在图上直接上下拖动 */
    orders: List<PendingOrder> = emptyList(),
    /** 松手后待确认的改价提案：对应的线停在新价，直到确认/取消 */
    orderMove: OrderMoveProposal? = null,
    /** 松手回调（ticket, LineId 种类, 新价）→ ViewModel 校验并进入确认步骤；不合法时线自动回到原价 */
    onOrderMove: (String, Int, Double) -> Unit = { _, _, _ -> },
    /** 拖动中实时校验：返回错误文字（线变红并提示）或 null */
    orderCheck: ((String, Int, Double) -> String?)? = null,
    /** 挂单 TP/SL 的预计盈亏：(方向, 手数, 开仓价, 平仓价) → 文字（USD） */
    orderPnl: ((Side, Double, Double, Double) -> String?)? = null,
    /** 松手后待确认的持仓 TP/SL 改价提案：对应的线停在新价，直到确认/取消 */
    positionMove: PositionMoveProposal? = null,
    /** 持仓 TP/SL 线松手回调（持仓 id, LineId.TP/SL, 新价）→ ViewModel 校验并进入确认步骤 */
    onPositionLineMove: (String, Int, Double) -> Unit = { _, _, _ -> },
    /** 拖动持仓 TP/SL 线时的实时校验：错误文字（线变红）或 null */
    positionCheck: ((String, Int, Double) -> String?)? = null,
    /** 拖动持仓 TP/SL 线时标签的数值（MT5：“+726.30 USD, 7263 points”）：参数 = 持仓 id、线价格 */
    positionPnl: ((String, Double) -> String?)? = null,
) {
    val posPnlState = rememberUpdatedState(positionPnl)
    val posMoveState = rememberUpdatedState(positionMove)
    val posMoveCb = rememberUpdatedState(onPositionLineMove)
    val posCheckState = rememberUpdatedState(positionCheck)
    val ordersState = rememberUpdatedState(orders)
    val moveState = rememberUpdatedState(orderMove)
    val orderMoveCb = rememberUpdatedState(onOrderMove)
    val orderCheckState = rememberUpdatedState(orderCheck)
    val orderPnlState = rememberUpdatedState(orderPnl)
    val liveOrder = remember(viewportKey) { LiveOrder() }
    val tapState = rememberUpdatedState(onPositionTap)
    val staticState = rememberUpdatedState(staticLines)
    val pnlState = rememberUpdatedState(pnlLabel)
    val view = remember(viewportKey) { ChartView() }
    val committed = lines.map { if (it.visible) it.price else null }
    val highs = remember(candles) { DoubleArray(candles.size) { candles[it].high } }
    val lows = remember(candles) { DoubleArray(candles.size) { candles[it].low } }
    // 生效价格区间：依赖视口（State）→ 只在读取它的绘制/手势中求值
    val range = remember(candles, committed, spec, view) {
        derivedStateOf {
            val vp = view.vp
            val vis = vp.visible(candles.size, view.w)
            val auto = if (vis.isEmpty()) PriceRange((bid ?: 1.0) + spec.pip * 20, (bid ?: 1.0) - spec.pip * 20)
            else Viewport.autoFit(highs, lows, vis.first, vis.last,
                if (vp.following) committed.filterNotNull() else emptyList(), spec.pip * 5)
            vp.range(auto)
        }
    }
    // 新 K 线追加：回看时保持画面不动；接近左边缘时加载更早历史
    val lastTime = remember(viewportKey) { longArrayOf(Long.MIN_VALUE) }
    LaunchedEffect(candles) {
        val prev = lastTime[0]
        if (prev != Long.MIN_VALUE) view.vp = view.vp.onAppended(candles.count { it.time > prev })
        lastTime[0] = candles.lastOrNull()?.time ?: prev
    }
    val needHist = rememberUpdatedState(onNeedHistory)
    LaunchedEffect(view, candles.size) {
        snapshotFlow { view.vp.visible(candles.size, view.w).let { if (it.isEmpty()) 0 else it.first } }
            .collect { if (it < 30) needHist.value() }
    }
    val dragging = live.dragging
    SideEffect {
        if (dragging.intValue < 0) lines.forEach { live.v[it.id].doubleValue = it.price ?: Double.NaN }
    }
    val linesState = rememberUpdatedState(lines)
    val candlesState = rememberUpdatedState(candles)
    val bidState = rememberUpdatedState(bid)
    val askState = rememberUpdatedState(ask)
    val sideState = rememberUpdatedState(side)
    val pendingState = rememberUpdatedState(pendingMode)
    val commitState = rememberUpdatedState(onCommit)
    val tm = rememberTextMeasurer()
    val style = remember { TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold) }
    val scope = rememberCoroutineScope()
    val fling = remember { arrayOfNulls<Job>(1) }
    val lastTap = remember { longArrayOf(0L) }
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Box(modifier.fillMaxSize().clipToBounds().onSizeChanged {
        view.w = (it.width - AXIS_W).coerceAtLeast(1f); view.h = it.height.toFloat().coerceAtLeast(1f)
    }) {
        CandleLayer(candles, view, range, spec.digits)
        Canvas(
            Modifier.fillMaxSize().pointerInput(view) {
                val tol = 24.dp.toPx() // 线的触控区：±24dp
                val ambiguousPx = 6.dp.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                  try { // 手势里任何意外异常只记录、放弃本次手势，不闪退（取消异常照常传播）
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling[0]?.cancel()
                    val r = range.value
                    val h = size.height.toFloat()
                    val n = candlesState.value.size
                    val w = view.w
                    // ---------- 0) 统一命中裁决（见 [ChartHit]）：编辑线 / 挂单线 / 持仓线·✕ ----------
                    val ls = linesState.value
                    val statics = staticState.value.filter { it.positionId != null }
                    val posWs = FloatArray(statics.size); val posHs = FloatArray(statics.size)
                    statics.forEachIndexed { i, st -> val lay = tm.measure(st.label, style); posWs[i] = lay.size.width.toFloat(); posHs[i] = lay.size.height.toFloat() }
                    val posYs = FloatArray(statics.size) { r.y(statics[it].price, h) }
                    val editYs = FloatArray(4) { Float.NaN }
                    ls.forEach { l ->
                        val v = live.v.getOrNull(l.id)?.doubleValue ?: Double.NaN
                        if (l.id in 0..3 && l.visible && !v.isNaN()) editYs[l.id] = r.y(v, h)
                    }
                    var orderRefs: List<OrderLineRef> = emptyList()
                    if (down.position.x < w) {
                        // 挂单线 + 持仓的 TP/SL 线（都可上下拖动）；等待确认的提案按新价参与命中
                        val pendingRefs = ordersState.value.flatMap { o ->
                            OrderLines.linesOf(moveState.value?.takeIf { it.ticket == o.ticket }
                                ?.let { m -> OrderLines.withPrice(o, m.kind, m.newPrice) } ?: o)
                        }
                        val posRefs = staticState.value.filter { it.posTicket != null && it.kind >= 0 }.map { st ->
                            val pm = posMoveState.value?.takeIf { it.id == st.posTicket && it.kind == st.kind }
                            OrderLineRef(st.posTicket!!, st.kind, pm?.newPrice ?: st.price, positionLine = true)
                        }
                        orderRefs = pendingRefs + posRefs
                    }
                    val orderYs = FloatArray(orderRefs.size) { r.y(orderRefs[it].price, h) }
                    val hit = ChartHit.resolve(down.position.x, down.position.y, w, h, editYs, orderYs, posYs, posWs, posHs,
                        OrderLines.TOUCH_DP.dp.toPx(), 16.dp.toPx(), 6.dp.toPx())
                    val tapHit = hit.tap
                    // ---------- 0a) ✕ 按钮：没有挂单线可抓时，精确点击 ----------
                    if (tapHit is ChartHit.Tap.Close && hit.drag !is ChartHit.Drag.Order) {
                        down.consume()
                        var up = false; var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if ((c.position - down.position).getDistance() > slop) moved = true
                            if (!c.pressed) { up = true; c.consume(); break }
                            c.consume()
                        }
                        if (up && !moved) statics.getOrNull(tapHit.index)?.positionId?.let { tapState.value(it) }
                        return@awaitEachGesture
                    }
                    // ---------- 1) TP/SL 编辑线拖动（严格更近于挂单线时才抢）----------
                    val editHit = hit.drag as? ChartHit.Drag.Edit
                    var target = editHit?.id ?: 0
                    if (editHit != null) {
                        val dist = FloatArray(4) { if (editYs[it].isNaN()) Float.MAX_VALUE else abs(editYs[it] - down.position.y) }
                        val ambiguous = dist[LineId.TP] < tol && dist[LineId.SL] < tol &&
                            abs(dist[LineId.TP] - dist[LineId.SL]) < ambiguousPx &&
                            (target == LineId.TP || target == LineId.SL)
                        down.consume()
                        val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            if (ambiguous) {
                                val tpIsUp = sideState.value == Side.BUY
                                target = if ((over < 0) == tpIsUp) LineId.TP else LineId.SL
                            }
                        } ?: return@awaitEachGesture
                        val line = live.v[target]
                        val grab = (if (line.doubleValue.isNaN()) down.position.y else r.y(line.doubleValue, h)) - down.position.y
                        val follow = target == LineId.ENTRY
                        val startVals = DoubleArray(4) { live.v[it].doubleValue }
                        dragging.intValue = target
                        fun apply(yy: Float) {
                            val p = r.price((yy + grab).coerceIn(0f, h), h) // 线在价格空间，用拖动开始时的区间换算
                            if (p.isNaN() || p.isInfinite()) return
                            line.doubleValue = p
                            if (follow) {
                                val d = p - startVals[LineId.ENTRY]
                                for (i in 1 until 4) if (!startVals[i].isNaN()) live.v[i].doubleValue = startVals[i] + d
                            }
                        }
                        apply(start.position.y)
                        verticalDrag(start.id) { c -> apply(c.position.y); c.consume() }
                        dragging.intValue = -1
                        val out = HashMap<Int, Double>()
                        if (!line.doubleValue.isNaN()) out[target] = line.doubleValue
                        if (follow) for (i in 1 until 4) if (!live.v[i].doubleValue.isNaN()) out[i] = live.v[i].doubleValue
                        commitState.value(out)
                        return@awaitEachGesture
                    }
                    // ---------- 1b) 已有挂单线：候选（真正的拖动在下面“等待”阶段按竖直位移判定，水平位移仍是平移）----------
                    val orderCand = (hit.drag as? ChartHit.Drag.Order)?.index ?: -1
                    // ---------- 2) 价格轴：竖直拖动 = 拉伸/压缩价格刻度 ----------
                    if (down.position.x >= w) {
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val d = c.positionChange()
                            if (!moved && abs(c.position.y - down.position.y) > slop) moved = true
                            if (moved && d.y != 0f) view.vp = view.vp.scaleY(kotlin.math.exp(d.y * 0.004f), range.value)
                            c.consume()
                        }
                        if (!moved) doubleTap(down.uptimeMillis, lastTap) { view.vp = view.vp.resetY() }
                        return@awaitEachGesture
                    }
                    // ---------- 3) 绘图区：平移 / 双指缩放 / 长按十字线 ----------
                    val vt = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var moved = false; var multi = false
                    var dragOrder = false; var dragStartY = 0f
                    // 长按等待阶段：移动超过 slop、多指或抬起都会提前结束。
                    // 触点在挂单线上时不启用长按十字线（允许按住后再拖）：竖直位移占优 → 拖线；否则照常平移。
                    val ended = withTimeoutOrNull(if (orderCand >= 0) Long.MAX_VALUE / 4 else viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) return@withTimeoutOrNull true
                            if (pressed.size >= 2) { multi = true; return@withTimeoutOrNull false }
                            val c = pressed.first()
                            val dp = c.position - down.position
                            if (dp.getDistance() > slop) {
                                if (orderCand >= 0 && OrderLines.isVerticalDrag(dp.x, dp.y, slop * 0.5f)) { dragOrder = true; dragStartY = c.position.y }
                                else moved = true
                                return@withTimeoutOrNull false
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    }
                    if (dragOrder) { // 拖动挂单线（开仓价 / 限价 / TP / SL）
                        val ref = orderRefs[orderCand]
                        val grab = r.y(ref.price, h) - down.position.y
                        liveOrder.isPosition = ref.positionLine; liveOrder.ticket = ref.ticket; liveOrder.kind = ref.kind; liveOrder.price = ref.price
                        fun apply(yy: Float) {
                            liveOrder.price = OrderLines.snap(r.price(OrderLines.clampY(yy + grab, h), h), spec)
                        }
                        apply(dragStartY)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            apply(c.position.y); c.consume()
                        }
                        val tk = ref.ticket; val kd = ref.kind; val fin = liveOrder.price
                        if (ref.positionLine) posMoveCb.value(tk, kd, fin) else orderMoveCb.value(tk, kd, fin)
                        // 让 ViewModel 的提案先进入下一帧再清除实时状态，避免线闪回旧价；不合法/取消时随后吸附回原位
                        scope.launch { delay(40); if (liveOrder.ticket == tk) liveOrder.ticket = null }
                        return@awaitEachGesture
                    }
                    if (ended == true) { // 单击（没有移动）
                        // ✕ → 平仓面板；持仓线（±16dp 或标签内，且不比挂单线更远）→ 平仓面板；否则按双击处理
                        val pid = when (tapHit) {
                            is ChartHit.Tap.Close -> statics.getOrNull(tapHit.index)?.positionId
                            is ChartHit.Tap.Position -> statics.getOrNull(tapHit.index)?.positionId
                            null -> null
                        }
                        if (pid != null) { lastTap[0] = 0; tapState.value(pid); return@awaitEachGesture }
                        if (orderCand < 0) doubleTap(down.uptimeMillis, lastTap) { view.vp = view.vp.resetY().copy(scroll = 0f) }
                        return@awaitEachGesture
                    }
                    if (ended == null) { // 长按 → 十字线
                        view.cross = down.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            view.cross = Offset(c.position.x.coerceIn(0f, w), c.position.y.coerceIn(0f, h)); c.consume()
                        }
                        view.cross = null
                        return@awaitEachGesture
                    }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val nn = candlesState.value.size
                        if (pressed.size >= 2) {
                            multi = true
                            val z = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val cen = ev.calculateCentroid()
                            var v = view.vp
                            if (z != 1f) v = v.zoom(z, cen.x, nn, w)
                            v = v.panX(pan.x, nn, w)
                            view.vp = v
                        } else if (!multi) {
                            val c = pressed.first()
                            val d = c.positionChange()
                            vt.addPosition(c.uptimeMillis, c.position)
                            view.vp = view.vp.panX(d.x, nn, w).panY(d.y, h, range.value)
                        }
                        ev.changes.forEach { it.consume() }
                    }
                    if (!multi) {
                        val vel = vt.calculateVelocity()
                        if (abs(vel.x) > 300f || (view.vp.manualY && abs(vel.y) > 300f)) {
                            fling[0] = scope.launch {
                              try {
                                // 惯性：按 x 速度衰减；手动价格模式下 y 同步按比例衰减
                                val ratio = if (view.vp.manualY && vel.x != 0f) vel.y / vel.x else 0f
                                var last = 0f
                                val useY = abs(vel.x) <= 300f
                                Animatable(0f).animateDecay(if (useY) vel.y else vel.x, exponentialDecay(1.5f)) {
                                    val d = value - last; last = value
                                    val nn = candlesState.value.size
                                    view.vp = if (useY) view.vp.panY(d, view.h, range.value)
                                    else view.vp.panX(d, nn, view.w).panY(d * ratio, view.h, range.value)
                                }
                              } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { com.morris.mttpsl.CrashLog.record(e) }
                            }
                        }
                    }
                  } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                  catch (e: Exception) { com.morris.mttpsl.CrashLog.record(e) }
                }
            }
        ) {
            val r = range.value
            val h = size.height
            val d = spec.digits
            val w = size.width - AXIS_W
            fun y(p: Double) = r.y(p, h)
            staticLines.forEach { s ->
                // 持仓 TP/SL 线：拖动中 / 等待确认时显示新价（加粗实线 + 价签），不合法变红
                var price = s.price; var activeLine = false; var dragLive = false
                if (s.posTicket != null) {
                    val pm = posMoveState.value
                    if (pm != null && pm.id == s.posTicket && pm.kind == s.kind) { price = pm.newPrice; activeLine = true }
                    if (liveOrder.isPosition && liveOrder.ticket == s.posTicket && liveOrder.kind == s.kind && !liveOrder.price.isNaN()) { price = liveOrder.price; activeLine = true; dragLive = true }
                }
                val bad = dragLive && posCheckState.value?.invoke(s.posTicket!!, s.kind, price) != null
                val lineColor = if (bad) OrderRed else s.color
                val yy = y(price)
                if (activeLine) drawLine(lineColor, Offset(0f, yy), Offset(w, yy), if (dragLive) 5f else 4f)
                else drawLine(s.color.copy(alpha = 0.85f), Offset(0f, yy), Offset(w, yy), 1.5f, pathEffect = ShortDash)
                // MT5：拖动 / 待确认时标签为 “TP, +726.30 USD, 7263 points”（价格显示在右侧价签）
                val text = if (activeLine) {
                    val nm = if (s.kind == LineId.TP) "TP" else "SL"
                    val v = posPnlState.value?.invoke(s.posTicket!!, price)
                    (if (v != null) "$nm, $v" else "$nm  " + "%.${d}f".format(price)) + if (bad) "  ✕ 价格无效" else ""
                } else s.label
                val layout = tm.measure(text, style.copy(color = Color.White))
                drawRect(lineColor, Offset(4f, yy - layout.size.height - 4f), Size(layout.size.width + 12f, layout.size.height + 4f))
                drawText(layout, topLeft = Offset(10f, yy - layout.size.height - 2f))
                if (activeLine) priceTag(tm, style, yy.coerceIn(22f, h - 22f), "%.${d}f".format(price), lineColor, Color.Black)
                if (s.positionId != null) { // MT5 风格：标签右侧 ✕ 平仓按钮
                    val lr = PositionLines.labelRect(layout.size.width.toFloat(), layout.size.height.toFloat(), yy)
                    val cb = PositionLines.closeButtonRect(lr)
                    drawRect(Color(0xFF2A2F37), Offset(cb.left, cb.top), Size(cb.width, cb.height))
                    drawRect(s.color, Offset(cb.left, cb.top), Size(cb.width, cb.height), style = Stroke(1.5f))
                    val x = tm.measure("✕", style.copy(color = Color.White))
                    drawText(x, topLeft = Offset(cb.left + (cb.width - x.size.width) / 2f, cb.top + (cb.height - x.size.height) / 2f))
                }
            }
            drawOrders(tm, style, ordersState.value, moveState.value, liveOrder, r, h, w, spec, orderPnlState.value, orderCheckState.value)
            val b = bidState.value
            val a = askState.value
            if (a != null) drawLine(ChartColors.Ask.copy(alpha = 0.6f), Offset(0f, y(a)), Offset(w, y(a)), 1f)
            if (b != null) {
                drawLine(ChartColors.Bid, Offset(0f, y(b)), Offset(w, y(b)), 1.5f, pathEffect = Dash)
                priceTag(tm, style, y(b), "%.${d}f".format(b), ChartColors.Bid, Color.Black)
            }
            val ls = linesState.value
            val buy = sideState.value == Side.BUY
            val pending = pendingState.value
            val exitRef = if (buy) b else a
            val entryRef = if (buy) a else b
            for (l in ls) {
                val p = live.v[l.id].doubleValue
                if (!l.visible || p.isNaN()) continue
                val ref: Double? = when (l.id) {
                    LineId.ENTRY -> entryRef
                    LineId.STOP_LIMIT -> live.v[LineId.ENTRY].doubleValue.takeIf { !it.isNaN() }
                    else -> if (pending) {
                        val sl = live.v[LineId.STOP_LIMIT].doubleValue
                        if (ls[LineId.STOP_LIMIT].visible && !sl.isNaN()) sl else live.v[LineId.ENTRY].doubleValue
                    } else exitRef
                }
                val yy = y(p)
                val active = dragging.intValue == l.id
                drawLine(l.color, Offset(0f, yy), Offset(w, yy), if (active) 5f else 2.5f)
                val pnlFn = pnlState.value
                val tpsl = (l.id == LineId.TP || l.id == LineId.SL) && pnlFn != null
                val lineText = if (tpsl) {
                    // MT5：“TP, +726.30 USD, 7263 points”
                    pnlFn!!(p, if (pending) ref?.takeIf { !it.isNaN() } else null)?.let { "${l.name}, $it" } ?: ("${l.name} %.${d}f".format(p))
                } else if (ref == null || ref.isNaN()) "${l.name} %.${d}f".format(p) else
                    "${l.name} %.${d}f".format(p) + "  %d points".format(Math.round(abs(p - ref) / spec.point))
                val lay = tm.measure(lineText, style.copy(color = Color.White))
                drawRect(l.color, Offset(4f, yy - lay.size.height - 4f), Size(lay.size.width + 12f, lay.size.height + 4f))
                drawText(lay, topLeft = Offset(10f, yy - lay.size.height - 2f))
                // 线在可视区外时，把价签钉在上/下边缘
                priceTag(tm, style, yy.coerceIn(22f, h - 22f), "%.${d}f".format(p), l.color, Color.Black)
            }
            view.cross?.let { c ->
                drawLine(Color.White.copy(alpha = 0.6f), Offset(0f, c.y), Offset(w, c.y), 1f, pathEffect = ShortDash)
                drawLine(Color.White.copy(alpha = 0.6f), Offset(c.x, 0f), Offset(c.x, h), 1f, pathEffect = ShortDash)
                priceTag(tm, style, c.y, "%.${d}f".format(r.price(c.y, h)), Color.White, Color.Black)
                val cs = candlesState.value
                val i = Math.round(view.vp.indexAt(c.x, cs.size, w))
                if (i in cs.indices) {
                    val k = cs[i]
                    val txt = "${timeFmt.format(Date(k.time))}  O ${"%.${d}f".format(k.open)} H ${"%.${d}f".format(k.high)} L ${"%.${d}f".format(k.low)} C ${"%.${d}f".format(k.close)}"
                    val lay = tm.measure(txt, style.copy(color = Color.Black))
                    val lx = (c.x - lay.size.width / 2f).coerceIn(0f, (w - lay.size.width).coerceAtLeast(0f))
                    drawRect(Color.White, Offset(lx - 4f, h - lay.size.height - 8f), Size(lay.size.width + 8f, lay.size.height + 6f))
                    drawText(lay, topLeft = Offset(lx, h - lay.size.height - 5f))
                }
            }
        }
        // 回看历史时显示"回到最新"按钮
        val showJump by remember(view) { derivedStateOf { view.vp.scroll > 2f } }
        if (showJump) {
            IconButton(
                onClick = { fling[0]?.cancel(); view.vp = view.vp.copy(scroll = 0f) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = (AXIS_W / 2.6f).dp + 8.dp, bottom = 12.dp)
                    .size(40.dp).background(Color(0xCC2A2F37), CircleShape),
            ) { Icon(Icons.Filled.KeyboardDoubleArrowRight, "回到最新", tint = Color.White) }
        }
    }
}

private val OrderRed = Color(0xFFFF5252)

/**
 * 画已有挂单（委托订单）的线：开仓/触发价（琥珀）、Stop Limit 限价（紫）、TP（绿）、SL（红）。
 * 正在拖动 / 等待确认的线加粗实线 + 价格标签 + 轴上价签；TP/SL 标签显示以当前（拖动中的）开仓价计算的预计盈亏（USD）。
 */
private fun DrawScope.drawOrders(
    tm: TextMeasurer, style: TextStyle, orders: List<PendingOrder>, move: OrderMoveProposal?, live: LiveOrder,
    r: PriceRange, h: Float, w: Float, spec: SymbolSpec,
    pnl: ((Side, Double, Double, Double) -> String?)?, check: ((String, Int, Double) -> String?)?,
) {
    val d = spec.digits
    val liveTicket = live.ticket.takeIf { !live.isPosition }
    for (o0 in orders) {
        var o = o0
        var activeKind = -1
        if (move != null && move.ticket == o.ticket) { o = OrderLines.withPrice(o, move.kind, move.newPrice); activeKind = move.kind }
        if (liveTicket != null && liveTicket == o.ticket && live.kind >= 0 && !live.price.isNaN()) {
            o = OrderLines.withPrice(o, live.kind, live.price); activeKind = live.kind
        }
        val dragging = liveTicket != null && liveTicket == o.ticket
        val base = if (o.type.isStopLimit) (o.stopLimit ?: o.price) else o.price
        for (ref in OrderLines.linesOf(o)) {
            val yy = r.y(ref.price, h)
            val active = ref.kind == activeKind
            if (!active && (yy < -4f || yy > h + 4f)) continue
            val invalid = dragging && active && check?.invoke(o.ticket, ref.kind, ref.price) != null
            val color = when {
                invalid -> OrderRed
                ref.kind == LineId.STOP_LIMIT -> ChartColors.StopLimit
                ref.kind == LineId.TP -> ChartColors.Tp
                ref.kind == LineId.SL -> ChartColors.Sl
                else -> ChartColors.Entry
            }
            if (active) drawLine(color, Offset(0f, yy), Offset(w, yy), if (dragging) 5f else 4f)
            else drawLine(color.copy(alpha = 0.85f), Offset(0f, yy), Offset(w, yy), 1.5f, pathEffect = ShortDash)
            val priceTxt = "%.${d}f".format(ref.price)
            val usd = if (ref.kind == LineId.TP || ref.kind == LineId.SL) pnl?.invoke(o.type.side, o.volume, base, ref.price) else null
            val text = buildString {
                append("#${o.ticket} ")
                when (ref.kind) {
                    LineId.ENTRY -> append("${o.type.label.lowercase()} ${o.volume}${if (o.type.isStopLimit) " 触发" else ""}")
                    LineId.STOP_LIMIT -> append("限价 ${o.volume}")
                    LineId.TP -> append("TP")
                    else -> append("SL")
                }
                val tpsl = ref.kind == LineId.TP || ref.kind == LineId.SL
                if (active && !(tpsl && usd != null)) append("  $priceTxt")
                if (usd != null) append(", $usd")
                if (invalid) append("  ✕ 价格无效")
            }
            val layout = tm.measure(text, style.copy(color = Color.White))
            drawRect(color, Offset(4f, yy - layout.size.height - 4f), Size(layout.size.width + 12f, layout.size.height + 4f))
            drawText(layout, topLeft = Offset(10f, yy - layout.size.height - 2f))
            if (active) priceTag(tm, style, yy.coerceIn(22f, h - 22f), priceTxt, color, Color.Black)
        }
    }
}

private inline fun doubleTap(t: Long, last: LongArray, action: () -> Unit) {
    if (t - last[0] < 300) { action(); last[0] = 0 } else last[0] = t
}

private fun DrawScope.priceTag(tm: TextMeasurer, style: TextStyle, y: Float, text: String, bg: Color, fg: Color) {
    val x = size.width - AXIS_W + 4f
    drawRect(bg, Offset(x, y - 22f), Size(AXIS_W - 6f, 44f))
    drawText(tm, text, Offset(x + 8f, y - 18f), style.copy(color = fg))
}

/** K 线层：在 drawWithCache 中构建 Path，只在 K 线 / 视口 / 区间 / 尺寸变化时重建（缓存块读取的 State 即为 key） */
@Composable
private fun CandleLayer(candles: List<Candle>, view: ChartView, range: State<PriceRange>, digits: Int) {
    val tm = rememberTextMeasurer()
    val axisStyle = remember { TextStyle(fontSize = 10.sp, color = ChartColors.Axis) }
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val vp = view.vp
            val r = range.value
            val w = size.width - AXIS_W
            val h = size.height
            val n = candles.size
            val bw = vp.barW
            fun y(p: Double) = r.y(p, h)
            val upWick = Path(); val dnWick = Path(); val upBody = Path(); val dnBody = Path()
            for (i in vp.visible(n, w)) {
                val c = candles[i]
                val x = vp.x(i, n, w)
                val bull = c.close >= c.open
                (if (bull) upWick else dnWick).apply { moveTo(x, y(c.high)); lineTo(x, y(c.low)) }
                val top = y(maxOf(c.open, c.close)); val bot = maxOf(y(minOf(c.open, c.close)), top + 1f)
                (if (bull) upBody else dnBody).addRect(Rect(x - bw * 0.35f, top, x + bw * 0.35f, bot))
            }
            val gridN = 6
            val labels = (0..gridN).map { i ->
                val p = r.hi - r.span * i / gridN
                y(p) to tm.measure("%.${digits}f".format(p), axisStyle)
            }
            val wick = Stroke(if (bw < 6f) 1f else 1.2f)
            onDrawBehind {
                drawRect(ChartColors.Bg)
                labels.forEach { (yy, layout) ->
                    drawLine(ChartColors.Grid, Offset(0f, yy), Offset(w, yy), 1f)
                    drawText(layout, topLeft = Offset(w + 10f, yy - layout.size.height / 2f))
                }
                drawLine(ChartColors.Grid, Offset(w, 0f), Offset(w, size.height), 1f)
                clipRect(right = w) {
                    drawPath(upWick, ChartColors.Bull, style = wick); drawPath(dnWick, ChartColors.Bear, style = wick)
                    drawPath(upBody, ChartColors.Bull); drawPath(dnBody, ChartColors.Bear)
                }
            }
        }
    )
}
