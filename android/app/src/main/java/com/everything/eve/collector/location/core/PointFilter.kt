package com.everything.eve.collector.location.core

/**
 * 采样点精度过滤（FR-1）：精度超差或无精度信息的点不进入缓冲。
 *
 * 纯函数、无副作用，由前台服务 onLocationChanged 调用。
 */
object PointFilter {

    /**
     * 判定一个定位点是否可接受。
     *
     * @param acc 系统定位精度半径（米）；Android 的 Location.hasAccuracy() 为 false
     *            时上层传 null。
     * @return true = 接受（精度非空且 ≤ [LocationParams.MAX_ACCURACY_M]）；
     *         false = 丢弃。无精度信息视为不可用，保守丢弃（宁缺毋滥，
     *         避免低质点污染轨迹与后续停留点检测）。
     */
    fun accept(acc: Float?): Boolean = acc != null && acc <= LocationParams.MAX_ACCURACY_M
}
