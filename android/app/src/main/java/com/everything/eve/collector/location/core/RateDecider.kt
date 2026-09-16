package com.everything.eve.collector.location.core

/**
 * 静止降频判定（FR-1）：滚动窗口内几乎无位移时降低采样请求间隔省电。
 *
 * 纯函数：输入最近点流与当前时刻，输出下一次 LocationManager 请求的
 * minTime 档位（minDistance 恒为 [LocationParams.MIN_DISTANCE_M]，由服务层固定）。
 */
object RateDecider {

    /**
     * 决定下一次定位请求的采样间隔。
     *
     * 判定规则：
     *  1. 取窗口 [now - STILL_WINDOW_MS, now] 内已收点；
     *  2. 窗口内点数 ≥ 2 且最大两两位移 < [LocationParams.STILL_DISPLACEMENT_M]
     *     → 判定静止，返回 [LocationParams.STILL_INTERVAL_MS]（300s）；
     *  3. 其余情况（含窗口内 0/1 个点）维持常规档 [LocationParams.MIN_TIME_MS]（60s）。
     *
     * "最大两两位移"取窗口内所有点对两两距离的最大值：任一对位移超阈值即视为
     * 有移动（比仅相邻点比较更抗抖动，单点漂移不会被误判为恢复移动——
     * 漂移点与簇内其余点的距离若超阈值才升档）。
     *
     * @param recent 最近已收轨迹点（可包含窗口外旧点，本函数自行按窗口过滤；
     *               点序任意，位移与排序无关）。
     * @param now    当前时刻（UTC 毫秒，注入以便单测）。
     * @return 采样间隔（毫秒）。
     */
    fun decideInterval(recent: List<TrackPoint>, now: Long): Long {
        val windowStart = now - LocationParams.STILL_WINDOW_MS
        // 只保留窗口内点；出窗旧点不参与判定（窗口滑动自然恢复常规档）。
        val inWindow = recent.filter { it.ts in windowStart..now }
        if (inWindow.size < 2) return LocationParams.MIN_TIME_MS

        // 窗口内最大两两位移（O(n²)，n 受采样频率限制最多十余点，开销可忽略）。
        var maxDisplacement = 0.0
        for (i in inWindow.indices) {
            for (j in i + 1 until inWindow.size) {
                val d = GeoMath.haversineM(
                    inWindow[i].lat, inWindow[i].lon,
                    inWindow[j].lat, inWindow[j].lon,
                )
                if (d > maxDisplacement) maxDisplacement = d
            }
        }
        return if (maxDisplacement < LocationParams.STILL_DISPLACEMENT_M) {
            LocationParams.STILL_INTERVAL_MS
        } else {
            LocationParams.MIN_TIME_MS
        }
    }
}
