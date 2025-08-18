package com.hitachi.drivermng.service

import android.app.*
import android.content.*
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
    private val awsRepository = AwsRepository(this)
    private var alertBackgroundStartReceiver: BroadcastReceiver? = null
    private var isSosNotice: Boolean = false
    private var alertDialog = IOSDialog.Builder(AppData.mainContext!!)
    private val alertInfoBucketName = AppData.appConfig!!.alertInfoBucket
    private val alertInfoLogPath = Constant.SOS_NOTICE_ALERT + "/"

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

    // ダイアログのボタン押下時の情報
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
            val currentSosCount = AlertQueue.queue.count { it.type == NOTIFICATION_TYPE_SOS }
            val newSosCount = sosNoticeDataList.size
            if (currentSosCount < newSosCount) {
                // 既存キューからSOSデータを除去し、新規データを統合して時刻順に並べる
                val remain = mutableListOf<AlertItem>()
                AlertQueue.queue.forEach { if (it.type != NOTIFICATION_TYPE_SOS) remain.add(it) }
                val newItems = sosNoticeDataList.map {
                    AlertItem(
                        it.sosNoticeStartTime,
                        NOTIFICATION_TYPE_SOS,
                        sosNoticeData = it
                    )
                }
                val sorted = (remain + newItems).sortedByDescending { item -> item.alertTime }
                AlertQueue.queue.clear()
                sorted.forEach { AlertQueue.queue.offer(it) }
                closeDialog()
                AlertQueue.playAlert(NOTIFICATION_TYPE_SOS)
            }
        }
    }

    /**
     * SOS緊急通知のダイアログ表示チェック
     */
    fun checkSosAlertShow() {
        if (!isDialogShowing()) {
            AlertQueue.displayAlert()
        }
    }

    /**
     * 次のSOS通知表示処理
     */
    private fun handleNextAlert() {
        try {
            closeDialog()
            val currentItem = AlertQueue.queue.peek()
            if (currentItem != null && currentItem.type == NOTIFICATION_TYPE_SOS) {
                val currentAlertData = currentItem.sosNoticeData
                if (currentAlertData != null) {
                    val prefix = this.alertInfoLogPath + currentAlertData.fileFullName
                    val currentDate = getCurrentDateWithFormate("yyyyMMdd")
                    val alertFilePath = "${AppData.mainContext!!.getExternalFilesDir(null)?.absolutePath.toString()}/${AppData.userInfo!!.userId}/$currentDate/"
                    FileUtil.write(prefix, alertFilePath, currentAlertData.fileFullName, false)
                }
                // 処理済みのデータをキューから削除し次を確認
                AlertQueue.queue.poll()
                AlertQueue.displayAlert()
            }
        } catch (e: Exception) {
            val ex = ExceptionUtil.analysisException(e)
            LoggerHelper.writeErrorLogger(ErrorLogLevelEnum.DEBUG, null, ex.code, ex.message)
        }
    }

    /**
     * SOS通知ダイアログの表示
     */
     fun displayAlert() {
        if (AlertQueue.queue.isEmpty()) return
        val first = AlertQueue.queue.peek() ?: return
        if (first.type != NOTIFICATION_TYPE_SOS) return
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
    }

     fun isDialogShowing(): Boolean {
         // 緊急通知ダイアログが表示中かどうか
         return alertDialog.isShowing()
     }

    fun closeDialog() {
        if (isDialogShowing()) {
            alertDialog.dismiss()
        }
    }

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
        handleNextAlert()
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
