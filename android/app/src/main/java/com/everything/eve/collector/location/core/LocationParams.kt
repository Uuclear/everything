package com.everything.eve.collector.location.core

/**
 * 位置轨迹（阶段 4a）全局常量，spec FR-1 / FR-3 / FR-4 的唯一权威取值。
 *
 * 本包为纯 Kotlin 纯函数核心：禁止引入任何 Android Framework / 加密库依赖，
 * 所有分支逻辑必须可 JVM 单测（NFR-3）。服务层（Task 6）只能引用本包常量，
 * 不得在服务内散落魔数。
 */
object LocationParams {

    // ---- 采样请求参数（FR-1）----

    /** 常规采样最小时间间隔（毫秒）：60s。 */
    const val MIN_TIME_MS = 60_000L

    /** 采样最小位移（米）：25m。 */
    const val MIN_DISTANCE_M = 25f

    /** 可接受的最大定位精度（米）：accuracy 超过该值的点直接丢弃。 */
    const val MAX_ACCURACY_M = 100f

    // ---- 静止降频（FR-1）----

    /** 静止判定滚动窗口（毫秒）：10 分钟。 */
    const val STILL_WINDOW_MS = 10 * 60_000L

    /** 静止判定位移阈值（米）：窗口内最大两两位移低于该值视为静止。 */
    const val STILL_DISPLACEMENT_M = 50.0

    /** 静止档采样间隔（毫秒）：300s。 */
    const val STILL_INTERVAL_MS = 300_000L

    // ---- 分块（FR-3）----

    /** 单块最大点数：达到即结算封块。 */
    const val MAX_POINTS_PER_BLOCK = 100

    /** 单块最大时间跨度（毫秒）：距块首点达 1 小时即结算封块。 */
    const val MAX_BLOCK_SPAN_MS = 60 * 60_000L

    // ---- 明文缓冲过期（FR-4）----

    /** 明文点滞留上限（毫秒）：24 小时未加密封块的点必须删除。 */
    const val POINT_EXPIRY_MS = 24 * 60 * 60_000L
}
