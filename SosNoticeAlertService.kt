package com.hitachi.drivermng.service

import android.app.*
import android.content.*
import android.media.AudioManager
import android.os.*
import android.util.Log
import android.view.Gravity
import androidx.annotation.RequiresApi
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hitachi.drivermng.R
import com.hitachi.drivermng.common.*
import com.hitachi.drivermng.data.vo.*
import com.hitachi.drivermng.repository.AwsRepository
import com.hitachi.drivermng.util.*
import com.hitachi.drivermng.util.DateUtil.Companion.getCurrentDateWithFormate
import com.hitachi.drivermng.view.MainActivity
import java.io.File
import java.util.*

/**
 * 緊急通知（SOS）を管理するサービス
 */
class SosNoticeAlertService : Service() {
    companion object {
        const val SOS_NOTICE_INTERVAL = 1 * 60 * 1000
        const val NOTIFICATION_TYPE_SOS = 2
        const val MINUTE_DIV_INTERVAL = 10
    }

    inner class SosNoticeAlertServiceBinder : Binder() {
        val service: SosNoticeAlertService
            get() = this@SosNoticeAlertService
    }

    private val binder = SosNoticeAlertServiceBinder()
    private val sosInSeconds = AppData.appConfig!!.sosNoticeSoundRingingTime
    private val awsRepository = AwsRepository(this)
    private var alertBackgroundStartReceiver: BroadcastReceiver? = null
    private var isSosNotice: Boolean = false
    private var alertDialog = IOSDialog.Builder(AppData.mainContext!!)
    private val alertInfoBucketName = AppData.appConfig!!.alertInfoBucket
    private val alertInfoLogPath = Constant.SOS_NOTICE_ALERT + "/"
    private val repeatAlertHandler = Handler(Looper.getMainLooper())
    private var repeatAlertRunnable: Runnable? = null
    private val sosNoticeReminderInterval = AppData.appConfig!!.sosNoticeReminderInterval * 60 * 1000
    // Handler で定期実行（Timer 置き換え）
    private val handler = Handler(Looper.getMainLooper())
    private val sosRunnable = object : Runnable {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun run() {
            try {
                if (isNeedExecute()) collectSosNoticeData()
            } catch (e: Exception) {
                Log.d(Constant.LOG_TAG, "SOS緊急通知エラー:" + e.message)
            } finally {
                if (isSosNotice) handler.postDelayed(this, SOS_NOTICE_INTERVAL.toLong())
            }
        }
    }
    // ダイアログのボタン押下状態
    var onDialogResult: ((args: HistoryItem?) -> Unit)? = null
    // 責任者フラグ
    private var isManage = AppData.userInfo?.isManager == true
    /**
     * サービス起動時処理
     */
    override fun onCreate() {
        super.onCreate()
        Log.e(Constant.LOG_TAG, "SosNoticeサービス起動処理")
    }

    /**
     * バインド処理
     */
    override fun onBind(intent: Intent): IBinder {
        Log.d(Constant.LOG_TAG, "サービスのバインド")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(Constant.LOG_TAG, "サービスのアンバインド")
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        Log.d(Constant.LOG_TAG, "サービスのリバインド")
        super.onRebind(intent)
    }

    /**
     * サービス破棄処理
     */
    override fun onDestroy() {
        Log.d(Constant.LOG_TAG, "onDestroy サービス終了処理")
        stopSosNoticeAlert()
        super.onDestroy()
    }

    /**
     * SOS通知処理の開始
     */
    fun startSosNoticeAlert() {
        if (this.isSosNotice) return
        this.isSosNotice = true

        // Handler でループ開始
        handler.post(sosRunnable)
    }

    /**
     * SOS通知処理の停止
     */
    fun stopSosNoticeAlert() {
        if (!this.isSosNotice) return
        this.isSosNotice = false

        handler.removeCallbacks(sosRunnable)

        alertBackgroundStartReceiver?.let {
            LocalBroadcastManager.getInstance(AppData.mainContext!!).unregisterReceiver(it)
        }
    }

    private fun isNeedExecute(): Boolean {
        // 責任者の場合、1分周期
        val interval = if (isManage) 1 else MINUTE_DIV_INTERVAL
        val execTimer = AppData.userInfo!!.userId.toInt() % interval
        val minute = Calendar.getInstance().get(Calendar.MINUTE)
        return (minute % interval) == execTimer
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun collectSosNoticeData() {
        Thread {
            try {
                showSosNoticeAlert()
            } catch (e: Exception) {
                val ex = ExceptionUtil.analysisException(e)
                LoggerHelper.writeErrorLogger(ErrorLogLevelEnum.DEBUG, null, ex.code, ex.message)
            }
        }.start()
    }

    /**
     * SOS通知の表示処理（通知またはダイアログ）
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun showSosNoticeAlert() {
        val currentDate = getCurrentDateWithFormate("yyyyMMdd")
        // 責任者の場合、ファイル名の先頭に「局」を検索する
        var prefix = this.alertInfoLogPath + AppData.userInfo!!.selPostOfficeCode + "_"
        if (!isManage) {
            //　責任者以外の場合は、ファイル名の先頭に「局・部・班・本日の日付」を検索する
            prefix += AppData.userInfo!!.departmentCode + "_" + AppData.userInfo!!.teamCode + "_" + currentDate
        }
        val sosNoticeDataList = awsRepository.getSosNoticeFileList(alertInfoBucketName, prefix)

        val alertFilePath = "${AppData.mainContext!!.getExternalFilesDir(null)?.absolutePath.toString()}/${AppData.userInfo!!.userId}/$currentDate/"
        val it: MutableIterator<SosNoticeData> = sosNoticeDataList.iterator()
        while (it.hasNext()) {
            val item = it.next()
            if (File(alertFilePath + item.fileFullName).exists()) {
                it.remove()
            }
        }

        (AppData.mainContext as MainActivity).runOnUiThread {
            // 既存キューからSOSデータを除去し、新規データを統合して時刻順に並べる
            val remain = mutableListOf<AlertItem>()
            AlertQueue.queue.forEach { if (it.type != NOTIFICATION_TYPE_SOS) remain.add(it) }
            val newItems = mutableListOf<AlertItem>()
            sosNoticeDataList.forEach {
                newItems.add(
                    AlertItem(
                        it.sosNoticeStartTime,
                        NOTIFICATION_TYPE_SOS,
                        sosNoticeData = it
                    )
                )
            }
            val sorted = (remain + newItems).sortedBy { item -> item.alertTime }
            AlertQueue.queue.clear()
            sorted.forEach { AlertQueue.queue.offer(it) }

            if (AlertQueue.queue.isNotEmpty()) {
                val first = AlertQueue.queue.peek()
                if (first.type == NOTIFICATION_TYPE_SOS) {
                    if (isDialogShowing()) {
                        alertDialog.dismiss()
                    }
                    if (!NotificationUtil.isForeground(AppData.mainContext!!)) {
                        if (NotificationUtil.notificationIsActive(NOTIFICATION_TYPE_SOS)) {
                            if (AppData.appConfig!!.sosNoticeRepetition) {
                                playSosAlert()
                            }
                        } else {
                            playSosAlert()
                        }
                        NotificationUtil.createNotification(
                            AppData.mainContext!!,
                            NOTIFICATION_TYPE_SOS
                        )
                    } else {
                        if (isDialogShowing()) {
                            if (AppData.appConfig!!.sosNoticeRepetition) {
                                playSosAlert()
                            }
                        } else {
                            playSosAlert()
                            sosAlertShow()
                        }
                    }
                } else {
                    // 長時間停止通知が先頭の場合はそちらを表示
                    AppData.longTimeStopAlertService?.displayAlertCheck()
                }
            }
        }
    }

    /**
     * サウンド＆バイブを実行
     */
    private fun playSosAlert() {
        // 音声再生
        MediaPlayerUtil.play(AppData.mainContext!!, R.raw.long_time_stop_alert, sosInSeconds) {}
        // バイブ
        if (AppData.appConfig!!.sosNoticeVibration == LongTimeStopVibration.ALWAYS_VIBRATE.value.toInt()) {
            VibratorUtil.vibrate(AppData.mainContext!!, VibratorUtil.LENGTH_SHORT)
        } else if (AppData.appConfig!!.sosNoticeVibration == LongTimeStopVibration.NO_SOUND_VIBRATE.value.toInt()) {
            val audio = AppData.mainContext!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                VibratorUtil.vibrate(AppData.mainContext!!, VibratorUtil.LENGTH_SHORT)
            }
        }
    }

    /**
     * SOS緊急通知のダイアログ表示チェック
     */
    fun checkSosAlertShow() {
        if (!isDialogShowing()){
            sosAlertShow()
        }
    }
    /**
     * SOS緊急通知ダイアログの表示
     */
    private fun sosAlertShow() {
        if (AlertQueue.queue.isNotEmpty()) {
            val first = AlertQueue.queue.peek()
            if (first.type != NOTIFICATION_TYPE_SOS) {
                AppData.longTimeStopAlertService?.displayAlertCheck()
                return
            }
            (AppData.mainContext as MainActivity).runOnUiThread {
                displayAlert()
            }
        }
    }

    /**
     * 次のSOS通知表示処理
     */
    private fun handleNextAlert(isFromOtherDialog: Boolean) {
        try {
            if (isDialogShowing()) {
                alertDialog.dismiss()
            }
            val currentItem = AlertQueue.queue.peek()
            if (currentItem != null && currentItem.type == NOTIFICATION_TYPE_SOS) {
                val currentAlertData = currentItem.sosNoticeData
                if (!isFromOtherDialog && currentAlertData != null) {
                    val prefix = this.alertInfoLogPath + currentAlertData.fileFullName
                    val currentDate = getCurrentDateWithFormate("yyyyMMdd")
                    val alertFilePath = "${AppData.mainContext!!.getExternalFilesDir(null)?.absolutePath.toString()}/${AppData.userInfo!!.userId}/$currentDate/"
                    FileUtil.write(prefix, alertFilePath, currentAlertData.fileFullName, false)
                }
                // 処理済みのデータをキューから削除し次を確認
                AlertQueue.queue.poll()
                val next = AlertQueue.queue.peek()
                if (next != null) {
                    if (next.type == NOTIFICATION_TYPE_SOS) {
                        displayAlert()
                    } else {
                        AppData.longTimeStopAlertService?.displayAlertCheck()
                    }
                } else {
                    AppData.longTimeStopAlertService?.displayAlertCheck()
                }
            }
        } catch (e: Exception) {
            val ex = ExceptionUtil.analysisException(e)
            LoggerHelper.writeErrorLogger(ErrorLogLevelEnum.DEBUG, null, ex.code, ex.message)
        }
    }

    /**
     * SOS通知ダイアログの表示
     */
    private fun displayAlert() {
        if (AlertQueue.queue.isNotEmpty()) {
            val first = AlertQueue.queue.peek()
            if (first.type != NOTIFICATION_TYPE_SOS) {
                AppData.longTimeStopAlertService?.displayAlertCheck()
                return
            }
            alertDialog = IOSDialog.Builder(AppData.mainContext!!)
            val showSosNoticeData = first.sosNoticeData!!
            val dialogTitle = MessageUtil.get(
                AppData.mainContext!!, R.string.EJT0063,
                showSosNoticeData.sosNoticeStartTime.substring(8, 10),
                showSosNoticeData.sosNoticeStartTime.substring(10, 12)
            )
            val dialogMessage = MessageUtil.get(
                AppData.mainContext!!, R.string.EJT0064,
                showSosNoticeData.departmentName,
                showSosNoticeData.teamName,
                showSosNoticeData.deliveryPersonName,
                showSosNoticeData.sosNoticeStartTime.substring(8, 10),
                showSosNoticeData.sosNoticeStartTime.substring(10, 12)
            )
            alertDialog.setTitle(dialogTitle)
                .setMessage(dialogMessage)
                .setMessageGravity(Gravity.START)
                .setIcon(R.drawable.alert_icon)
                .setPositiveButton(R.string.strClose) { _, _ ->
                    onDialogResult?.invoke(null)
                    doNextAlert("btnSosNoticeAlertClose")
                }
                .setNegativeButton(R.string.strBtnDisplayAdminTool) { _, _ ->
                    onDialogResult?.invoke(
                        HistoryItem(
                            departmentCode = showSosNoticeData.departmentCode,
                            departmentName = showSosNoticeData.departmentName,
                            teamCode = showSosNoticeData.teamCode,
                            teamName = showSosNoticeData.teamName,
                            userName = AppData.userInfo!!.userId,
                            time = showSosNoticeData.sosNoticeStartTime,
                            alertType = "",
                        )
                    )
                    doNextAlert("btnSosNoticeAlertGoAdminTool")
                }
                .setCancelable(false)
                .show()
            startRepeatAlert()
        }
    }

    /**
     * ダイアログが閉じられるまで指定間隔で繰り返し通知を行う。
     */
    private fun startRepeatAlert() {
        if (repeatAlertRunnable != null) return
        repeatAlertRunnable = object : Runnable {
            override fun run() {
                if (isDialogShowing()) {
                    playSosAlert()
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
    private fun isDialogShowing(): Boolean {
        // 緊急通知ダイアログが表示中かどうか
        return alertDialog.isShowing()
    /**
     * ログインユーザーが本日閉じた通知ファイルを取得する
     * パスが同じであるため、長時間停止のファイルも一緒に取得される
     */
    fun getTodayUserFiles(rootPath: String): List<String> {
        val currentDate = getCurrentDateWithFormate("yyyyMMdd")
        val result = mutableListOf<String>()
        val userDir = File(rootPath, AppData.userInfo!!.userId)
        val dateDir = File(userDir, currentDate)
        if (!dateDir.exists() || !dateDir.isDirectory) return result
        dateDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                result.add(file.name)
            }
        }
        return result
    }

    private fun doNextAlert(id: String) {
        cancelRepeatAlert()
        handleNextAlert(false)
        LoggerHelper.writeOppLogger(
            OppLoggerTypeEnum.OPP_SOS_NOTICE_ALERT,
            null,
            AppData.currentFormId,
            id,
            AppData.telNumber,
            FileUtil.getVersionName(),
            AppData.userInfo!!.userId
        )
    }
}
