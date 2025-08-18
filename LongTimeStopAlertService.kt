package com.hitachi.drivermng.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.*
import android.graphics.Color
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hitachi.drivermng.R
import com.hitachi.drivermng.common.Constant
import com.hitachi.drivermng.common.ErrorLogLevelEnum
import com.hitachi.drivermng.common.LongTimeStopVibration
import com.hitachi.drivermng.common.OppLoggerTypeEnum
import com.hitachi.drivermng.data.vo.AlertItem
import com.hitachi.drivermng.data.vo.AlertQueue
import com.hitachi.drivermng.data.vo.AppData
import com.hitachi.drivermng.data.vo.HistoryItem
import com.hitachi.drivermng.data.vo.LongTimeStopData
import com.hitachi.drivermng.repository.AwsRepository
import com.hitachi.drivermng.service.SosNoticeAlertService.Companion.NOTIFICATION_TYPE_SOS
import com.hitachi.drivermng.util.*
import com.hitachi.drivermng.util.DateUtil.Companion.getCurrentDateWithFormate
import com.hitachi.drivermng.view.MainActivity
import java.io.File
import java.util.*

/**
 *　ロケーション情報に関する処理サービス
 */
class LongTimeStopAlertService : Service() {
    companion object {
        const val LONG_TIME_STOP_INTERVAL = 1 * 60 * 1000
        const val LONG_TIME_STOP_ALERT = "longTimeStopAlert"
        const val ARG_LONG_TIME_STOP_ALERT = "argLongTimeStopAlert"
        const val LONG_TIME_STOP_ALERT_SHOW = "longTimeStopAlertShow"
        const val ARG_LONG_TIME_STOP_ALERT_SHOW = "argLongTimeStopAlert"
        const val NOTIFICATION_TYPE_LONGTIMESTOP = 1
        const val MINUTE_DIV_INTERVAL = 10
    }

    /**
     * Binder クラス
     *
     */
    inner class LongTimeStopAlertServiceBinder : Binder() {
        val service: LongTimeStopAlertService
            get() = this@LongTimeStopAlertService
    }

    /**
     * LongTimeStopAlertServiceBinder
     */
    private val binder = LongTimeStopAlertServiceBinder()
    private val stopInSeconds = AppData.appConfig!!.longTimeStopSoundRingingTime

    /**
     * awsリポジトリ
     */
    private val awsRepository = AwsRepository(this)
    /** ウェイクロック */
    private lateinit var wakeLock: PowerManager.WakeLock
    /** パワーマネージャ */
    private lateinit var powerManager: PowerManager
    // ダイアログのボタン押下状態
    var onDialogResult: ((args: HistoryItem?) -> Unit)? = null
    // 責任者フラグ
    private var isManage = AppData.userInfo?.isManager == true
    //alertBackgroundStartReceiver
    private var alertBackgroundStartReceiver: BroadcastReceiver? =null

    private var isLongTimeStop: Boolean = false
    private var alertDialog = IOSDialog.Builder(AppData.mainContext!!)
    private val alertInfoBucketName = AppData.appConfig!!.alertInfoBucket
    private val alertInfoPhonePath = Constant.BUCKET_KEY_LONG_TIME_ALERT + "/"
    private val repeatAlertHandler = Handler(Looper.getMainLooper())
    private var repeatAlertRunnable: Runnable? = null
    private val noticeReminderInterval = AppData.appConfig!!.sosNoticeReminderInterval * 60 * 1000

    // Handler で定期実行（Timer 置き換え）
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = object : Runnable {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun run() {
            try {
                if (isNeedExecute()) collectLongTimeStopData()
            } catch (e: Exception) {
                Log.d(Constant.LOG_TAG, "長時間停止エラー:" + e.message.toString())
            } finally {
                if (isLongTimeStop) handler.postDelayed(this, LONG_TIME_STOP_INTERVAL.toLong())
            }
        }
    }

    /**
     * サービス起動処理
     *
     */
    override fun onCreate() {
        super.onCreate()
        Log.e(Constant.LOG_TAG, "LongTimeStopサービス起動処理")
        // パワーマネージャの取得
        this.powerManager = this.getSystemService(Context.POWER_SERVICE) as PowerManager
        // パワーマネージャの開始
        this.wakeLock = this.powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.packageName)
        // wakeLockを開始
        wakeLock.acquire()
        // 長時間停止バックグラウンドデータ取得
        alertBackgroundStartReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val isBackgroundStart = intent.getBooleanExtra(ARG_LONG_TIME_STOP_ALERT, false)
                if (isBackgroundStart) {
                    if (!isDialogShowing()) {
                        broadcastAlertShowStatus(true)
                    }
                }
            }
        }
    }

    /**
     * サービスのバインド
     *
     * @param intent インテント
     *
     * @return IBinder
     */
    override fun onBind(intent: Intent): IBinder {
        Log.d(Constant.LOG_TAG, "サービスのバインド")
        return binder
    }

    /**
     * サービスのアンバインド
     *
     * @param intent インテント
     *
     * @return Boolean
     */
    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(Constant.LOG_TAG, "サービスのアンバインド")
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        Log.d(Constant.LOG_TAG, "servicerebind")
        super.onRebind(intent)
    }

    /**
     * サービス終了処理
     */
    override fun onDestroy() {
        Log.d(Constant.LOG_TAG, "onDestroy サービス終了処理")
        //wakeLockの停止
        wakeLock.release()
        super.onDestroy()
    }

    /**
     * サービスを開始する
     *
     * @param intent インテント
     * @param flags flags
     * @param startId start Id
     *
     * @return Int onStartCommandの戻り値
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground()
        }
        return START_NOT_STICKY
    }

    /**
     * アプリが削除された時の処理
     *
     * @param rootIntent インテント
     */
    override fun onTaskRemoved(rootIntent: Intent) {
        Log.d(Constant.LOG_TAG, "サービス onTaskRemoved ")
        this.stopLongTimeStopAlert()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true)
        } else {
            stopSelf()
        }

    }

    /**
     * 通知（Notification）を表示する。
     *
     */
    private fun startForeground() {
        val channelId =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    createNotificationChannel(Constant.ALERT_CHANNEL_ID, Constant.ALERT_CHANNEL_NAME)
                } else {
                    ""
                }

        val notificationMessage = MessageUtil.get(AppData.mainContext!!, R.string.strNotificationMsg)
        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(R.drawable.location_service_icon)
            .setColor(AppData.mainContext!!.getColor(R.color.notification))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentText(notificationMessage)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationMessage))
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(101, notification)
    }

    /**
     * 通知チャンネルを実装する。
     *
     * @param channelId チャンネルID
     * @param channelName チャンネル名
     *
     * @return String チャンネルID
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String): String{
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.setSound(null,null)
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    /**
     * アラート情報を開始する。
     */
    fun startLongTimeStopAlert() {
        // 既に開始された場合は、開始済みとして終了する。
        if (this.isLongTimeStop) return

        this.isLongTimeStop = true

        try {
            alertBackgroundStartReceiver?.let {
                LocalBroadcastManager.getInstance(AppData.mainContext!!).registerReceiver(
                        it,
                        IntentFilter(LONG_TIME_STOP_ALERT)
                )
            }
        } catch (e: SecurityException) {
            Log.e(Constant.LOG_TAG, "長時間停止エラー:" + e.message.toString())
        } catch (e: Exception) {
            ExceptionUtil.analysisException(e)
        }

        // Handler でループ開始
        handler.post(stopRunnable)
    }

    /**
     * アラート情報を停止する。
     */
    fun stopLongTimeStopAlert() {
        if (!this.isLongTimeStop) return
        this.isLongTimeStop = false
        handler.removeCallbacks(stopRunnable)
        alertBackgroundStartReceiver?.let {
            LocalBroadcastManager.getInstance(AppData.mainContext!!).unregisterReceiver(it)
        }
    }

    private fun isNeedExecute(): Boolean {
        // 責任者の場合、1分周期
        val interval = if (isManage) 1 else MINUTE_DIV_INTERVAL
        val execTimer = AppData.userInfo!!.userId.toInt() % interval
        // 現在の時間（分）を取得する
        val minute = Calendar.getInstance().get(Calendar.MINUTE)
        // 同じ余り
        return (minute % interval) == execTimer
    }

    /**
     * アラート情報の取得
     * 　5分毎にアラート情報S3から、
     * ログイン中の社員に一致する当日日付のファイル名を取得し、
     * 取得した情報をつかって画面にアラートを表示する。
     *　
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun collectLongTimeStopData() {
        Thread {
            try {
                showLongTimeStopAlert()
            } catch (e: SecurityException) {
                Log.d(Constant.LOG_TAG, "長時間停止情報処理失敗：" + e.message.toString())
            } catch (e: Exception) {
                val ex = ExceptionUtil.analysisException(e)
                LoggerHelper.writeErrorLogger(ErrorLogLevelEnum.DEBUG, null, ex.code, ex.message)
            }
        }.start()
    }

    /**
     *アラート情報の取得
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun showLongTimeStopAlert() {
        val currentDate = getCurrentDateWithFormate("yyyyMMdd")
        // 責任者の場合、ファイル名の先頭に「局」を検索する
        var prefix = this.alertInfoPhonePath + AppData.userInfo!!.selPostOfficeCode + "_"
        if (!isManage) {
            // 責任者以外の場合は、ファイル名の先頭に「局・部・班・本日の日付」を検索する
            prefix += AppData.userInfo!!.departmentCode + "_" + AppData.userInfo!!.teamCode + "_" + currentDate
        }
        val longTimeStopDataList = awsRepository.getLongTimeStopFileList(alertInfoBucketName ?: "", prefix)
        //アラート確認済みの情報をチェック
        val alertFilePath = "${AppData.mainContext!!.getExternalFilesDir(null)?.absolutePath.toString()}/${AppData.userInfo!!.userId}/$currentDate/"
        val it: MutableIterator<LongTimeStopData> = longTimeStopDataList.iterator()
        while (it.hasNext()) {
            val item: LongTimeStopData = it.next()
            if (File(alertFilePath + item.fileFullName).exists()) {
                it.remove() // remove it from list if file exists locally
            }
        }
        (AppData.mainContext as MainActivity).runOnUiThread {
            val currentSosCount = AlertQueue.queue.count { it.type == NOTIFICATION_TYPE_LONGTIMESTOP }
            val newSosCount = longTimeStopDataList.size
            if (currentSosCount < newSosCount) {
                // 既存キューから長時間停止データを除去し、新規データを統合して時刻順に並べる
                val remain = mutableListOf<AlertItem>()
                AlertQueue.queue.forEach {
                    if (it.type != NOTIFICATION_TYPE_LONGTIMESTOP) remain.add(
                        it
                    )
                }
                val newItems = mutableListOf<AlertItem>()
                longTimeStopDataList.forEach {
                    newItems.add(
                        AlertItem(
                            it.longTimeStopOccurrenceTime,
                            NOTIFICATION_TYPE_LONGTIMESTOP,
                            longTimeStopData = it
                        )
                    )
                }
                val sorted = (remain + newItems).sortedByDescending { item -> item.alertTime }
                AlertQueue.queue.clear()
                sorted.forEach { AlertQueue.queue.offer(it) }
                closeDialog()
                AlertQueue.playAlert(NOTIFICATION_TYPE_LONGTIMESTOP)
            }
        }
    }

    /**
     * 長時間停止の放送を送る
     * @param isFromBackgroundStart バックグラウンドからアプリを起動するかどうか
     */
    fun broadcastAlertStatus(isFromBackgroundStart: Boolean) {
        if (isFromBackgroundStart) {
            val intent = Intent(LONG_TIME_STOP_ALERT)
            intent.putExtra(ARG_LONG_TIME_STOP_ALERT, isFromBackgroundStart)
            LocalBroadcastManager.getInstance(this.application).sendBroadcast(intent)
        }
    }
    /**
     * 長時間アラートの放送を送る
     * @param haveAlertShow 長時間アラートの表示
     */
    fun broadcastAlertShowStatus(haveAlertShow: Boolean) {
        if (haveAlertShow) {
            val intent = Intent(LONG_TIME_STOP_ALERT_SHOW)
            intent.putExtra(ARG_LONG_TIME_STOP_ALERT_SHOW, haveAlertShow)
            LocalBroadcastManager.getInstance(this.application).sendBroadcast(intent)
        }
    }

    /**
     * ダイアログ表示チェック
     */
    fun displayAlertCheck() {
        if (!isDialogShowing()) {
            AlertQueue.displayAlert()
        }
    }

    /**
     * 次長時間停止アラートの表示
     */
    private fun handleNextAlert(){
        try {
            closeDialog()
            val currentItem = AlertQueue.queue.peek()
            if (currentItem != null && currentItem.type == NOTIFICATION_TYPE_LONGTIMESTOP) {
                val currentAlertData = currentItem.longTimeStopData
                if (currentAlertData != null) {
                    val prefix = this.alertInfoPhonePath + currentAlertData.fileFullName
                    val sdf = getCurrentDateWithFormate("yyyyMMdd")
                    val currentDate = sdf.format(Date())
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
     * 長時間停止ダイアログの表示
     */
     fun displayAlert() {
        if (AlertQueue.queue.isEmpty()) return
        val first = AlertQueue.queue.peek() ?: return
        if (first.type != NOTIFICATION_TYPE_LONGTIMESTOP) return
        val showLongTimeStopData = first.longTimeStopData
        if (showLongTimeStopData != null) {
            alertDialog = IOSDialog.Builder(AppData.mainContext!!)
            val dialogTitle = MessageUtil.get(
                AppData.mainContext!!,
                R.string.EJT0023,
                showLongTimeStopData.longTimeStopOccurrenceTime.substring(8, 10),
                showLongTimeStopData.longTimeStopOccurrenceTime.substring(10, 12)
            )
            val dialogMessage = MessageUtil.get(
                AppData.mainContext!!, R.string.EJT0024,
                showLongTimeStopData.deliveryPersonName,
                showLongTimeStopData.longTimeStopStartTime.substring(8, 10),
                showLongTimeStopData.longTimeStopStartTime.substring(10, 12)
            )
            alertDialog.setTitle(dialogTitle)
                .setMessage(dialogMessage)
                .setMessageGravity(Gravity.START)
                .setIcon(R.drawable.alert_icon)
                .setPositiveButton(R.string.strClose, DialogInterface.OnClickListener { _, _ ->
                    onDialogResult?.invoke(null)
                    doNextAlert("btnLongTimeStopAlertClose")
                })
                .setNegativeButton(R.string.strBtnDisplayAdminTool) { _, _ ->
                    onDialogResult?.invoke(
                        HistoryItem(
                            departmentCode = showLongTimeStopData.departmentCode,
                            departmentName = showLongTimeStopData.departmentName,
                            teamCode = showLongTimeStopData.teamCode,
                            teamName = showLongTimeStopData.teamName,
                            userName = AppData.userInfo!!.userId,
                            time = showLongTimeStopData.longTimeStopOccurrenceTime,
                            alertType = "",
                        )
                    )
                    doNextAlert("btnLongTimeStopAlertGoAdminTool")
                }
                .setCancelable(false)
                .show()
        }
    }

    fun isDialogShowing(): Boolean {
        // 長時間停止ダイアログが表示中かどうか
        return alertDialog.isShowing()
    }

    fun closeDialog() {
        if (isDialogShowing()) {
            alertDialog.dismiss()
        }
    }
    private fun doNextAlert(id: String) {
        //次長時間停止アラートの処理
        handleNextAlert()
        // 操作ログ記録と送信
        LoggerHelper.writeOppLogger(
            OppLoggerTypeEnum.OPP_LONG_TIME_STOP_ALERT,
            null,
            AppData.currentFormId,
            id,
            AppData.telNumber,
            FileUtil.getVersionName(),
            AppData.userInfo!!.userId
        )
    }
}
