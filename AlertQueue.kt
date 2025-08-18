package com.hitachi.drivermng.data.vo

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.hitachi.drivermng.R
import com.hitachi.drivermng.common.LongTimeStopVibration
import com.hitachi.drivermng.service.LongTimeStopAlertService
import com.hitachi.drivermng.service.SosNoticeAlertService
import com.hitachi.drivermng.util.MediaPlayerUtil
import com.hitachi.drivermng.util.NotificationUtil
import com.hitachi.drivermng.util.VibratorUtil
import com.hitachi.drivermng.view.MainActivity

/**
 * アラート表示のための共通キュー
 */
object AlertQueue {
    /** 通知を管理するキュー */
    val queue: UniqueLinkedQueue<AlertItem> = UniqueLinkedQueue()
    /** 鳴動タイマ */
    private val repeatAlertHandler = Handler(Looper.getMainLooper())
    private var repeatAlertRunnable: Runnable? = null
    private val sosNoticeReminderInterval = AppData.appConfig!!.sosNoticeReminderInterval * 60 * 1000

    /**
     * キューの先頭アラートを表示
     */
    fun displayAlert() {
        if (queue.isEmpty()) return
        when (queue.peek()?.type) {
            LongTimeStopAlertService.NOTIFICATION_TYPE_LONGTIMESTOP ->
                (AppData.mainContext as? MainActivity)?.runOnUiThread {
                    AppData.sosNoticeAlertService?.closeDialog()
                    AppData.longTimeStopAlertService?.displayAlert()
                }
            SosNoticeAlertService.NOTIFICATION_TYPE_SOS ->
                (AppData.mainContext as? MainActivity)?.runOnUiThread {
                    AppData.longTimeStopAlertService?.closeDialog()
                    AppData.sosNoticeAlertService?.displayAlert()
                }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun playAlert(alertType: Int) {
        if (queue.isNotEmpty()) {
            val first = queue.peek()
            if (first != null) {
                if (!NotificationUtil.isForeground(AppData.mainContext!!)) {
                    if (NotificationUtil.notificationIsActive(alertType)) {
                        if (getRepetitionSetting(alertType)) {
                            playMediaAndVibration(alertType)
                        }
                    } else {
                        playMediaAndVibration(alertType)
                    }
                    NotificationUtil.createNotification(AppData.mainContext!!, alertType)
                } else {
                    playMediaAndVibration(alertType)
                    if (alertType == 1) {
                        AppData.longTimeStopAlertService?.broadcastAlertShowStatus(true)
                    } else if (alertType == 2) {
                        AppData.sosNoticeAlertService?.checkSosAlertShow()
                    }
                }
                cancelRepeatAlert()
                startRepeatAlert(alertType)
            }
        }
    }

    /**
     * サウンド＆バイブを実行
     */
    private fun playMediaAndVibration(alertType: Int) {
        // 音声再生
        MediaPlayerUtil.play(AppData.mainContext!!, R.raw.long_time_stop_alert, getSoundRingingTime(alertType)) {}
        // バイブ
        if (getVibrationSetting(alertType) == LongTimeStopVibration.ALWAYS_VIBRATE.value.toInt()) {
            VibratorUtil.vibrate(AppData.mainContext!!, VibratorUtil.LENGTH_SHORT)
        } else if (getVibrationSetting(alertType) == LongTimeStopVibration.NO_SOUND_VIBRATE.value.toInt()) {
            val audio = AppData.mainContext!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                VibratorUtil.vibrate(AppData.mainContext!!, VibratorUtil.LENGTH_SHORT)
            }
        }
    }

    /**
     * ダイアログが閉じられるまで指定間隔で繰り返し通知を行う。
     */
    private fun startRepeatAlert(alertType: Int) {
        if (repeatAlertRunnable != null) return
        repeatAlertRunnable = object : Runnable {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun run() {
                if (AppData.longTimeStopAlertService?.isDialogShowing() == true || AppData.sosNoticeAlertService?.isDialogShowing() == true) {
                    playAlert(alertType)
                    repeatAlertHandler.postDelayed(this, (sosNoticeReminderInterval).toLong())
                } else {
                    cancelRepeatAlert()
                }
            }
        }
        repeatAlertHandler.postDelayed(repeatAlertRunnable!!, (sosNoticeReminderInterval).toLong())
    }

    /**
     * ダイアログが閉じられた際に繰り返し通知を停止する。
     */
    private fun cancelRepeatAlert() {
        repeatAlertRunnable?.let {
            repeatAlertHandler.removeCallbacks(it)
            repeatAlertRunnable = null
        }
    }

    private fun getRepetitionSetting(alertType: Int): Boolean {
        val config = AppData.appConfig ?: return false
        val repetitionMap = mapOf(
            1 to config.longTimeStopRepetition,
            2 to config.sosNoticeRepetition
        )
        return repetitionMap[alertType] ?: false
    }

    private fun getSoundRingingTime(alertType: Int): Int {
        val config = AppData.appConfig ?: return 0
        return when (alertType) {
            1 -> config.longTimeStopSoundRingingTime
            2 -> config.sosNoticeSoundRingingTime
            else -> 0
        }
    }

    private fun getVibrationSetting(alertType: Int): Int {
        val config = AppData.appConfig ?: return 0
        return when (alertType) {
            1 -> config.longTimeStopVibration
            2 -> config.sosNoticeVibration
            else -> 0
        }
    }
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
