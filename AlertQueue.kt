package com.hitachi.drivermng.service

import com.hitachi.drivermng.data.vo.LongTimeStopData
import com.hitachi.drivermng.data.vo.SosNoticeData
import com.hitachi.drivermng.data.vo.UniqueLinkedQueue

/**
 * アラート表示のための共通キュー
 */
object AlertQueue {
    /** 通知を管理するキュー */
    val queue: UniqueLinkedQueue<AlertItem> = UniqueLinkedQueue()
}

/**
 * キューに格納するアラート情報
 *
 * @param alertTime アラート発生時刻（yyyyMMddHHmm形式）
 * @param type アラート種別
 * @param longTimeStopData 長時間停止データ
 * @param sosNoticeData SOS通知データ
 */
data class AlertItem(
    val alertTime: String,
    val type: Int,
    val longTimeStopData: LongTimeStopData? = null,
    val sosNoticeData: SosNoticeData? = null
)
