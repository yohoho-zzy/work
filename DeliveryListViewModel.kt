package com.hitachi.tms2application.viewmodel

import android.annotation.SuppressLint
import android.app.Activity
import android.location.Location
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView
import com.hitachi.tms2application.R
import com.hitachi.tms2application.adapter.BaseWorkAdapter
import com.hitachi.tms2application.adapter.DeliveryDataAdapter
import com.hitachi.tms2application.adapter.PointWithSlipsAdapter
import com.hitachi.tms2application.common.ActionEnum
import com.hitachi.tms2application.common.CodeType
import com.hitachi.tms2application.common.Constant.DATE_FORMAT_YYYYMMDD_1
import com.hitachi.tms2application.common.OppLogType
import com.hitachi.tms2application.common.SlipStatus
import com.hitachi.tms2application.common.SlipType
import com.hitachi.tms2application.common.SymbologyType
import com.hitachi.tms2application.common.WorkEvent
import com.hitachi.tms2application.data.api.DeliverySlip
import com.hitachi.tms2application.data.api.DeliverySlipResponse
import com.hitachi.tms2application.data.api.MoveDeliverySlip
import com.hitachi.tms2application.data.api.RouteUpdate
import com.hitachi.tms2application.data.api.UpdateDeliveryRouteReq
import com.hitachi.tms2application.data.model.DeliveryData
import com.hitachi.tms2application.data.model.PointWithSlips
import com.hitachi.tms2application.data.model.PopupMenuItem
import com.hitachi.tms2application.data.model.SlipNoItem
import com.hitachi.tms2application.data.model.ToastData
import com.hitachi.tms2application.data.vo.AppData
import com.hitachi.tms2application.util.APPDialog
import com.hitachi.tms2application.util.AWSTransfer
import com.hitachi.tms2application.util.ApiUtil
import com.hitachi.tms2application.util.DateUtil
import com.hitachi.tms2application.util.DialogUtil
import com.hitachi.tms2application.util.Expansions.format
import com.hitachi.tms2application.util.LocationServiceUtil
import com.hitachi.tms2application.util.PopupMenuUtil
import com.hitachi.tms2application.util.ProgressDialog
import com.hitachi.tms2application.view.PointInspectionFragment.Companion.ARGS_FROM_SELECT_DIALOG
import jp.co.asterisk.asreader.a24d.sdk.AsReader
import jp.co.asterisk.asreader.barcodemanager.SymbologyInfoModel
import jp.co.asterisk.asreader.barcodemanager.define.BarcodeConst
import jp.co.asterisk.asreader.barcodemanager.define.Symbology
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.coroutines.resume

/**
 * 配送順リスト画面(TMS_T_M009)ViewModelクラス
 *
 * @since 1.0.0
 *
 * @param context Activity
 */
class DeliveryListViewModel(context: Activity) : BaseViewModel(context) {
    /**
     * ユーザ名
     */
    val userName = MutableLiveData<String>().apply { value = AppData.userInfo!!.name }

    /**
     * コース
     */
    val routeName = MutableLiveData<String>().apply { value = "" }
    // プルダウンリスト
    val filterOptions = mutableListOf("全件表示", "未配のみ表示", "スキップのみ表示")
    // プルダウンリストで選択されたフィルタの値
    val filterSelection = MutableLiveData<String>().apply { value = filterOptions[AppData.deliveryFilterIndex] }

    // コース中の得意先数
    val pointCount = MutableLiveData<Int>().apply { value = 0 }
    // 配送する得意先のすべての伝票枚数
    val slipCount = MutableLiveData<Int>().apply { value = 0 }
    // 配送する得意先のすべての商品数
    val productCount = MutableLiveData<Int>().apply { value = 0 }
    // 得意先検品が完了した得意先数
    val deliveredPoint = MutableLiveData<Int>().apply { value = 0 }
    // 得意先検品が完了した伝票枚数
    val deliveredSlip = MutableLiveData<Int>().apply { value = 0 }
    // 得意先検品が完了した商品数
    val deliveredProduct = MutableLiveData<Int>().apply { value = 0 }
    // 配送順リスト用のアダプター
    lateinit var deliveryListAdapter: DeliveryDataAdapter
    // 画面上に表示するダイアログ
    private var appDialog: APPDialog.Builder? = null
    // 得意先検品が完了していない状態で得意先検品画面に遷移する際に選択された得意先の緯度経度と現在位置が設定ファイルで指定されている距離以上に離れている場合、ダイアログを表示する
    private var distanceDialog: APPDialog? = null
    private val pointWithSlipsList: MutableList<PointWithSlips> = mutableListOf()
    @SuppressLint("StaticFieldLeak")
    private lateinit var slipSubView: View
    private lateinit var subViewAdapter: PointWithSlipsAdapter
    lateinit var workResultAdapter: BaseWorkAdapter

    /**
     * 初期処理
     * @param showSelectDialog 得意先検品から戻った場合に、再度選択ダイアログを表示するかどうかを示すフラグ
     *
     */
    fun init(showSelectDialog: Boolean) {
        // データの初期化
        initProgressData()

        // 画面項目の設定
        routeName.value = AppData.selectedRoute?.routeName
        // すべての得意先検品が完了していて、終点に到着したと判断された場合、業務終了を促すアラートを出す
        //		以下の条件を満たした場合、終点に到着したと判断する
        //				① 現在位置が終点の緯度経度から設定ファイルで指定された一定距離内である
        //				② ①の状態が設定ファイルで指定された一定時間継続している
        //		アラート通知は以下のダイアログを表示すると共に鳴動（アラート音＋バイブレーション）させる
        if (AppData.deliveryDataList.all { it.isStart || it.isEnd || it.isDelivered || it.isSkipped}) {
            AppData.locationService?.let {
                it.onArriveEnd {
                    it.rumbleAlert()
                    APPDialog.Builder(context)
                        .setMessage(R.string.DIA0040)
                        .setPrimaryButton(R.string.strBtnCancel) { it.stopAutoArrive() }
                        .setSecondaryButton(R.string.strBtnEnd) {
                            it.stopAutoArrive()
                            stopReaderDecode()
                            changeFragment(ActionEnum.DELIVERY_RESULTS)
                        }
                        .show()
                }
            }
        }

        // 得意先検品から戻った場合、再度当該ダイアログに復帰する
        if (showSelectDialog) {
            selectInspectionPoint()
        } else {
            selectedFacility = null
        }
        setNavigationBack()
        // 伝票変更時の処理
        onSlipChanged { slipNo, isCancel ->
            val index = deleteSlip(slipNo, isCancel)
            deliveryListAdapter.notifyItemChanged(index)
            initProgressData()
        }

        // 位置情報サービスの開始
        // 本画面に初回遷移したタイミングでのみ、位置情報の収集を開始する。(以降、業務結果確認画面を表示するまで、継続して位置情報の収集を実施する）
        // 位置情報の取得状況によって、現在地情報取得中のテキストの内容を更新する
        LocationServiceUtil.startLocationService()
    }

    private fun setNavigationBack() {
        onNavigationClick {
            // 荷合わせ検品を実施済みフラグ
            val isPackCompleted = AppData.deliveryDataList.first().isDelivered
            // コース内のいずれかの得意先の得意先検品が完了している状態
            val isPointInspected = AppData.deliveryDataList.any { facility -> facility.pointList.any { point -> point.isDelivered } }
            // 荷合わせ検品を実施済みまたはコース内のいずれかの得意先の得意先検品が完了している状態の場合
            // 以下の確認ダイアログを表示する
            // 上記以外の場合は、コース選択のダイアログを表示する
            // ただし、当該画面に入ったのちに何の操作もしていない場合は確認ダイアログは表示せずにコース選択画面に遷移する
            if (isPackCompleted || isPointInspected) {
                appDialog = APPDialog.Builder(context)
                    .setMessage(R.string.DIA0035)
                    .setPrimaryButton(R.string.strBtnCancel)
                    .setSecondaryButton(R.string.strBtnEnd){
                        changeFragment(ActionEnum.DELIVERY_RESULTS)
                    }
                appDialog?.show()
            } else {
                if (AppData.isDataSent) {
                    appDialog = APPDialog.Builder(context)
                        .setMessage(R.string.DIA0036)
                        .setPrimaryButton(R.string.strBtnCancel)
                        .setSecondaryButton(R.string.strBtnCourseSelect) {
                            AppData.isDataSent = false
                            viewModelScope.launch { backToSelectCourse() }
                        }
                    appDialog?.show()
                } else {
                    viewModelScope.launch { backToSelectCourse() }
                }
            }

        }
    }

    /**
     * コース選択画面に戻るための処理。
     *
     * 復旧データのクリアおよび AppSync の停止を行います
     * 業務イベントログとして「運行開始（戻る）」イベントを送信します
     *
     * @throws Exception エラーダイアログを表示する
     */
    private suspend fun backToSelectCourse() {
        try {
            // 復旧データクリアし、AppSyncを停止する
            if (AppData.routeList.isEmpty()) {
                val date = Date().format(DATE_FORMAT_YYYYMMDD_1)
                AppData.routeList = fetchRouteList(date)
            }
            AppData.appSyncService?.stop()
            // 業務イベントログ送信
            // E0901 運行開始（戻る）
            CoroutineScope(Dispatchers.IO).launch {
                AWSTransfer.deleteDeliveryFiles()
                AWSTransfer.sendWorkEventLog(WorkEvent.DELIVERY_RETURN)
            }
            changeFragment(ActionEnum.SELECT_COURSE)
        } catch (e: Exception) {
            showDialogWithException(e) { ProgressDialog.hide() }
        }
    }


    /**
     * 施設がタップされた場合、確認ダイアログを表示し、詳細情報を表示する対象を確定させる
     */
    fun selectPointDetail() {
        val facility = selectedFacility!!
        fun areAllSamePointInfo(): Boolean {
            return facility.pointList.all { point -> point.pointInfo.pointId == facility.pointInfo.pointId }
        }

        if (facility.isStart || facility.isEnd || areAllSamePointInfo()) {
            selectedHub = facility.pointList.first()
            changeFragment(ActionEnum.FACILITY_DETAIL)
        } else {
            appDialog = APPDialog.Builder(context)
            appDialog!!.addSubView(
                DialogUtil.createPointSelectDialog(facility, false) { data, facilityFlag ->
                    appDialog?.close()
                    selectedHub = facility.pointList.find { p -> p.pointInfo.pointId == facility.pointInfo.pointId } ?: data
                    // 表示する情報が施設の場合は「施設詳細情報」、施設以外の場合は「得意先詳細情報
                    if (facilityFlag) {
                        changeFragment(ActionEnum.FACILITY_DETAIL)
                    } else {
                        changeFragment(ActionEnum.POINT_DETAIL)
                    }
                }
            ).setMessage(R.string.DIA0020)
                .setPrimaryButton(R.string.strBtnCancel)
                .show()
        }
    }

    /**
     * 得意先アイコン処理
     * 複数の得意先を配下にもつ施設のアクションアイコンがタップされた場合、以下の確認ダイアログを表示し、得意先検品の対象を確定させる
     *
     */
    fun selectInspectionPoint() {
        selectedFacility?.let {
            appDialog = APPDialog.Builder(context)
            appDialog!!.addSubView(
                    DialogUtil.createPointSelectDialog(it, true) { data, _ ->
                        selectedPoint = data as DeliveryData.Point
                        // 得意先検品完了時の表示情報を保持している場合に限る（復旧した場合や他のユーザが引き継いで同一コースを選択した場合は表示不能）
                        if (selectedPoint!!.isRecover) {
                            showPointFinishDialog()
                        } else {
                            // 距離をチェックして、得意先検品画面に遷移
                            showPackingGroupDialog {
                                // 操作ログを記録する
                                CoroutineScope(Dispatchers.IO).launch {
                                    AWSTransfer.writeOppLog(OppLogType.GENERIC, "btnPointInspect")
                                }
                                val args = mutableMapOf(ARGS_FROM_SELECT_DIALOG to "true")
                                changeFragment(ActionEnum.POINT_INSPECTION, args = args)
                                appDialog?.close()
                            }
                        }
                    }
                )
                .setMessage(R.string.DIA0022)
                .setPrimaryButton(R.string.strBtnCancel) { selectedFacility = null }
                .show()
        }
    }

    /**
     * 得意先検品画面へ遷移、業務イベントログ送信
     */
    private fun sendWorkEventLog() {
        if (!selectedPoint!!.isDelivered) {
            CoroutineScope(Dispatchers.IO).launch {
                // 業務イベントログ送信
                // E1004 到着
                AWSTransfer.sendWorkEventLog(WorkEvent.ARRIVAL)
                // E1304 荷卸し開始
                AWSTransfer.sendWorkEventLog(WorkEvent.UNLOADING_START)
            }
        }
    }

    /**
     * タップした得意先、または上記のダイアログで施設経由で選択された得意先と同一名寄せコードを持つ得意先は、以下のダイアログを表示し、同時に得意先検品を実施する
     * ただし、同じ施設内で連続して存在し、いずれも得意先検品が完了していない得意先に限る
     *  @param onFinished 処理が完了した後に実行されるコールバック関数
     */
    fun showPackingGroupDialog(onFinished: () -> Unit) {
        var packingGroupPoints: MutableList<String> = mutableListOf()
        if (selectedPoint!!.origin.route!!.packingGroupPointId.isNotEmpty()) {
            packingGroupPoints = selectedFacility!!.pointList.filter { point -> point.origin.route!!.packingGroupPointId == selectedPoint!!.origin.route!!.packingGroupPointId && !point.isSkipped && !point.isDelivered }
                .map { it.pointInfo.pointName} .toMutableList()
        }

        if (packingGroupPoints.isNotEmpty() && packingGroupPoints.size > 1) {
            distanceDialog = APPDialog.Builder(context)
                .setMessage(R.string.DIA0080)
                .addSubView(DialogUtil.packingGroupDialog(packingGroupPoints))
                .setSecondaryButton(R.string.strOK) { checkDistance{ onFinished() } }
                .show()
        } else {
            checkDistance{ onFinished() }
        }
    }

    /**
     * 得意先検品が完了していない状態で得意先検品画面に遷移する際に選択された得意先の緯度経度と現在位置が設定ファイルで指定されている距離以上に離れている場合
     * ダイアログを表示する
     *  @param onFinished 処理が完了した後に実行されるコールバック関数
     */
    private fun checkDistance(onFinished: () -> Unit) {
        selectedPoint?.let {
            // 得意先検品が完了の場合、距離をチェックしない
            if (it.isDelivered) {
                sendWorkEventLog()
                onFinished()
                return
            }
            // 現在地情報を取得
            val currentLocation = AppData.locationService?.currentLocationInfo
            if (currentLocation != null) {
                // 現在位置と得意先の距離を計算
                val results = FloatArray(3)
                Location.distanceBetween(
                    currentLocation.mpLat,
                    currentLocation.mpLon,
                    it.pointInfo.unloadingLatitude,
                    it.pointInfo.unloadingLongitude,
                    results)
                val distance = results[0]
                if (distance >= AppData.appConfig!!.deliveryPointAlertDistance) {
                    // 得意先の緯度経度と現在位置が設定ファイルで指定されている距離以上に離れている場合、以下のダイアログを表示する
                    distanceDialog = APPDialog.Builder(context)
                        .setMessage(R.string.DIA0030)
                        .setPrimaryButton(R.string.strBtnCancel)
                        .setSecondaryButton(R.string.strOK) {
                            sendWorkEventLog()
                            onFinished()
                        }
                        .show()
                } else {
                    sendWorkEventLog()
                    onFinished()
                }
            } else {
                sendWorkEventLog()
                onFinished()
            }
        }
    }

    /**
     * メニュー配送コース確認押下
     * 計画コース画面に遷移する
     */
    private fun deliveryCourseConfirmation() {
        // 操作ログを記録する
        CoroutineScope(Dispatchers.IO).launch {
            AWSTransfer.writeOppLog(OppLogType.GENERIC, "btnConfirm")
        }
        changeFragment(ActionEnum.PLANNED_ROUTE)
    }

    /**
     * メニュー配送順変更/スキップ押下
     * 配送順変更画面を表示し
     */
    private fun deliveryChange() {
        // 操作ログを記録する
        CoroutineScope(Dispatchers.IO).launch {
            AWSTransfer.writeOppLog(OppLogType.GENERIC, "btnDeliveryChange")
        }
        changeFragment(ActionEnum.DELIVERY_CHANGE)
    }

    /**
     * 得意先チェック
     * @param data 伝票情報レスポンス
     */
    private fun checkPoint(data: DeliverySlipResponse): Boolean {
        // スキャンされたバーコードが伝票ではない場合、以下のダイアログを表示する
        if (data.pointList.isEmpty()) {
            distanceDialog = showInspectionError(R.string.DIA0076)
            return false
        }
        return true
    }

    private fun switchAddSlipDialog(visible: Boolean) {
        val messageView = slipSubView.findViewById<LinearLayout>(R.id.ll_warning)
        messageView.visibility = if (visible) View.VISIBLE else View.GONE
        pointWithSlipsList.forEach { p -> p.slips.forEach { slip -> slip.isWarning = visible } }
        subViewAdapter.notifyItemRangeChanged(0, subViewAdapter.itemCount)
        if (slipSubView.parent != null) { (slipSubView.parent as ViewGroup).removeView(slipSubView) }
        appDialog?.close()
        if (visible) {
            appDialog = APPDialog.Builder(context)
                .addSubView(slipSubView)
                .setAutoDismiss(false)
                .setMessage(R.string.DIA0062)
                .setPrimaryButton(R.string.strBtnBack) { switchAddSlipDialog(false) }
                .setSecondaryButton(R.string.strBtnForcedShutdown) { moveSlip() }

        } else {
            startReaderDecode()
            appDialog = APPDialog.Builder(context)
            if (AppData.isAsCameraXLicenseCertified) {
                val cameraIcon = DialogUtil.generateCameraXIcon {
                    launchAsCameraXScanView { symbologyType, decodeData ->
                        addSlip(decodeData, symbologyType) { changeFragment(ActionEnum.DELIVERY_LIST) }
                    }
                }
                appDialog!!.addSubView(cameraIcon)
            }
            appDialog!!.addSubView(slipSubView)
                .setAutoDismiss(false)
                .setMessage(R.string.DIA0023)
                .setPrimaryButton(R.string.strBtnCancel) {
                    stopReaderDecode()
                    appDialog?.close()
                }
                .setSecondaryButton(R.string.strBtnFinish) {
                    stopReaderDecode()
                    val isAllScanned = pointWithSlipsList.all { point -> point.slips.all { slip -> slip.isChecked } }
                    if (isAllScanned) {
                        moveSlip()
                    } else {
                        switchAddSlipDialog(true)
                    }
                }
        }
        appDialog?.show()
    }

    private fun moveSlip() {
        fun move() {
            viewModelScope.launch {
                try {
                    ProgressDialog.show(R.string.IKK0026)
                    /**
                     * 追加された伝票の得意先によって以下の処理を行う
                     * 追加された伝票をセンタに連携する
                     * コース情報をセンタに連携する
                     * 得意先を追加した場合、配送コース更新のレスポンスの到着予定時刻、出発予定時刻で配送順リストを再表示する
                     *
                     */
                    val slipNos: MutableList<String> = mutableListOf()
                    val list = AppData.deliveryDataList.map { f ->
                        f.copy(pointList = f.pointList.map { p -> p.copy(origin = p.origin.copy(deliverySlipList = p.origin.deliverySlipList.toMutableList())) }.toMutableList())
                    }.toMutableList()
                    var needUpdateRoute = false
                    for (i in pointWithSlipsList.indices) {
                        val item = pointWithSlipsList[i]
                        val pointId = item.data.point.pointId
                        val deliverySlipList: MutableList<DeliverySlip> = mutableListOf()
                        item.slips.forEach { slip ->
                            if (slip.isChecked) {
                                slip.data?.slipType = SlipType.ADDITIONAL
                                deliverySlipList.add(slip.data!!)
                                slipNos.add(slip.slipNo)
                            }
                        }
                        if (deliverySlipList.isEmpty()) continue
                        val matchedPoints = list.flatMap { f -> f.pointList }.filter { p -> p.pointInfo.pointId == pointId }
                        if (matchedPoints.isEmpty()) {
                            val newPoint = DeliveryData.Point.of(item.data.copy(deliverySlipList = deliverySlipList))
                            newPoint.isNew = true
                            val packingGroupPointId = item.data.point.packingGroupPointId
                            // １．得意先が配送コースに無い場合　
                            val packingFacility = if (packingGroupPointId.isEmpty()) null else list.find { f ->
                                f.pointList.any { p -> p.origin.route!!.packingGroupPointId == packingGroupPointId && !p.isDelivered }
                            }
                            if (packingFacility != null) {
                                // ① 梱包名寄せが同一の得意先がコース内にある場合、その得意先の直後に得意先を追加し、伝票を追加する
                                val index = packingFacility.pointList.indexOfFirst { p -> !p.isDelivered }
                                if (index >= 0) {
                                    packingFacility.pointList.add(index + 1, newPoint)
                                    packingFacility.setDrugInfo()
                                } else {
                                    list.add(list.size - 1, packingFacility.copy(pointList = mutableListOf(newPoint)))
                                }
                            } else {
                                // ② 上記以外 終点の直前に得意先を追加し、伝票を追加する
                                val newFacility = DeliveryData.Facility(
                                    isStart = false,
                                    isEnd = false,
                                    parentPointId = pointId,
                                    pointInfo = item.data.point,
                                    imageList = newPoint.imageList,
                                    pointList = mutableListOf(newPoint)
                                ).apply {
                                    setInitEt()
                                    setDrugInfo()
                                }
                                list.add(list.size - 1, newFacility)
                            }
                            needUpdateRoute = true
                        } else {
                            val point = matchedPoints.find { p -> !p.isDelivered }
                            // ２.得意先が配送コースにある場合
                            if (point == null) {
                                val facility = list.find { f -> f.pointList.any { p -> p.pointInfo.pointId == pointId } }!!
                                val isSamePoint = facility.pointInfo.pointId == pointId
                                val routeStatus = if (deliverySlipList.all { slip -> slip.status != SlipStatus.UN_INSPECTED.code }) "10" else "01"
                                val origin = if (isSamePoint) matchedPoints.last().origin.copy(point = facility.pointInfo) else matchedPoints.last().origin
                                val copyOrigin = origin.copy(deliverySlipList = deliverySlipList).apply { route?.status = routeStatus }
                                val copyPoint = if (isSamePoint) DeliveryData.Point.of(copyOrigin).copy(imageList = facility.imageList) else DeliveryData.Point.of(copyOrigin)
                                copyPoint.isNew = true
                                val copyFacility = facility.copy(pointList = mutableListOf(copyPoint)).apply { setDrugInfo() }
                                // ① 得意先検品が未完了の得意先がない、終点の直前に得意先を追加し、伝票を追加する
                                list.add(list.size - 1, copyFacility)
                                needUpdateRoute = true
                            } else {
                                // ② 上記以外、配送コース順の若い得意先に伝票を追加する
                                point.origin.deliverySlipList.addAll(deliverySlipList)
                            }
                        }
                    }
                    val data = MoveDeliverySlip(
                        routeId = AppData.selectedRoute!!.routeId,
                        deliverySlipList = slipNos,
                        getThumbnailFlag = "0"
                    )
                    withContext(Dispatchers.IO) {
                        // 1-5_伝票移動API
                        ApiUtil.moveDeliverySlip(data)
                        if (needUpdateRoute) {
                            var order = 0
                            val pointList = list.flatMap { it.pointList }
                            var maxInitOrder = pointList.filter { p -> !p.isNew }.maxOf { p -> p.origin.route?.initOrder ?: 0 }
                            val route = pointList.map { point ->
                                val initOrder = if (point.isNew) ++maxInitOrder else point.origin.route?.initOrder ?: 0
                                val skipFlag = point.origin.route?.skipFlag ?: "0"
                                point.isNew = false
                                RouteUpdate(initOrder, ++order, skipFlag)
                            }.toMutableList()
                            val request = UpdateDeliveryRouteReq(
                                route = route,
                                routeId = AppData.selectedRoute!!.routeId,
                                getDeliverySlipFlag = "0",
                                getPointFlag = "0",
                                getThumbnailFlag = "0"
                            )
                            val updateResult = ApiUtil.updateDeliveryRoute(request)
                            updateResult.data.deliveryPointList.sortBy { it.route!!.order }
                            var index = 0
                            list.forEach { f ->
                                f.pointList.forEach { p ->
                                    p.origin.route = updateResult.data.deliveryPointList[index++].route
                                }
                            }
                        }
                    }
                    AppData.deliveryDataList = list
                    deliveryListAdapter.update(list)
                    initProgressData()
                    AppData.isDataSent = true
                    appDialog?.close()
                } catch (e: Exception) {
                    showDialogWithException(e) { startReaderDecode() }
                } finally {
                    ProgressDialog.hide()
                }
            }
        }
        // ・荷合わせ検品が完了していない伝票が追加された場合だけ、以下の確認ダイアログを表示する
        if (pointWithSlipsList.any { item -> item.slips.any { slip -> slip.isChecked && slip.data?.status == SlipStatus.UN_INSPECTED.code } }) {
            appDialog?.close()
            appDialog = APPDialog.Builder(context)
                .setMessage(R.string.DIA0064)
                .setIconShow(true)
                .setSecondaryButton(R.string.strOK) { move() }
            appDialog?.show()
        } else { move() }
    }

    /**
     * 1-2_伝票番号検索APIにおいて、201のエラーが返る場合
     * ダイアログを表示するが、処理は正常処理として扱う
     * @return OKボタンを押下した場合、trueを返し、処理を継続する
     * */
    private suspend fun showWarning() = suspendCancellableCoroutine { continuation ->
        appDialog?.close()
        appDialog = APPDialog.Builder(context)
            .setMessage(R.string.DIA0075)
            .setIconShow(true)
            .setSecondaryButton(R.string.strOK) {
                continuation.resume(true)
            }
        appDialog?.show()
    }

    private suspend fun addSlip(decodeData: String, symbology: SymbologyType, cameraHandle: ((isFinish: Boolean) -> Unit)? = null) {
        try {
            val isValid = symbology == SymbologyType.QR && (decodeData.length == 11 || decodeData.first() == '0' && decodeData.last() == '0')
            val slipNo = if (isValid) {
                if (decodeData.length == 11) {
                    decodeData.take(9)
                } else {
                    decodeData.slice(1..9)
                }
            } else {
                ""
            }
            val isExist = isValid && isSlipExist(slipNo)
            playBeep(decodeData, isValid && !isExist)
            // スキャンされたバーコードが伝票ではない場合、以下のダイアログを表示する
            if (!isValid) {
                distanceDialog = showInspectionError(R.string.DIA0050)
                return
            }
            // スキャンされた伝票がすでにコース内に存在する場合、以下のダイアログを表示する
            if (isExist) {
                distanceDialog = showInspectionError(R.string.DIA0063)
                return
            }
            stopReaderDecode()

            if (pointWithSlipsList.isEmpty()) {
                cameraHandle?.invoke(true)
                ProgressDialog.show(R.string.IKK0025)
                val entity = withContext(Dispatchers.IO) { ApiUtil.searchDeliverySlipNo(slipNo, "01,10,20,30") }
                ProgressDialog.hide()
                if (entity.isSuccessful) {
                    if (!checkPoint(entity.data)) return
                    if (entity.statusCode == 201) {
                        showWarning()
                    }
                    entity.data.pointList.forEach { point ->
                        val slips = point.deliveryPointList.flatMap { p -> p.deliverySlipList.sortedWith(compareBy({ it.deliveryDatePlan }, { it.deliverySlipNo })) }.map { slip ->
                            val date = DateUtil.strDateFormat(slip.deliveryDatePlan, "yyyyMMdd", "M月dd日")
                            SlipNoItem(slip.deliverySlipNo, "${slip.deliverySlipNo}($date)", slipNo == slip.deliverySlipNo, data = slip)
                        }.filter { item -> !isSlipExist(item.slipNo) }.toMutableList()
                        if (slips.isNotEmpty()) {
                            val numOfScanned = slips.count { slip -> slip.isChecked }
                            val data = point.deliveryPointList.first().copy(point = point.point)
                            pointWithSlipsList.add(PointWithSlips(data, numOfScanned, slips))
                        }
                    }
                    showInspectToast(ToastData(CodeType.SLIP_NO, getString(R.string.TOA0001, slipNo)))
                    subViewAdapter.notifyItemRangeInserted(0, pointWithSlipsList.size)
                    switchAddSlipDialog(false)
                }
            } else {
                var isSlipExist = false
                for (i in pointWithSlipsList.indices) {
                    val point = pointWithSlipsList[i]
                    val indexOfSlip = point.slips.indexOfFirst { slip -> slip.slipNo == slipNo }
                    if (indexOfSlip >= 0) {
                        val slip = point.slips[indexOfSlip]
                        if (!slip.isChecked) {
                            slip.isChecked = true
                            point.numOfScanned++
                            subViewAdapter.notifyItemChanged(i)
                        }
                        showInspectToast(ToastData(CodeType.SLIP_NO, getString(R.string.TOA0001, slipNo)))
                        isSlipExist = true
                        cameraHandle?.invoke(pointWithSlipsList.all { point -> point.numOfScanned == point.slips.size })
                        break
                    }
                }
                if (!isSlipExist) {
                    distanceDialog = showInspectionError(R.string.DIA0077)
                    return
                }
            }
            startReaderDecode()
        } catch (e: Exception) {
            showDialogWithException(e) { startReaderDecode() }
        } finally {
            ProgressDialog.hide()
        }
    }

    /**
     * 伝票追加処理
     * バーコードリーダを起動し
     * 正しい伝票がスキャンされた場合、その伝票番号をセンタに問合せる
     */
    fun startAddSlip() {
        stopReaderDecode()
        AsReader.getInstance().barcodeManager.setSymbologyAllEnable(BarcodeConst.MemoryType.MEMORY_TYPE_PERMANENT, false)

        // 操作ログを記録する
        viewModelScope.launch(Dispatchers.IO) {
            AWSTransfer.writeOppLog(OppLogType.GENERIC, "addSlip")
        }
        startReaderDecode()
        appDialog = APPDialog.Builder(context)
            .setMessage(R.string.DIA0019)
            .setPrimaryButton(R.string.strBtnCancel) {
//                removeCameraFragment()
                stopReaderDecode()
            }
        pointWithSlipsList.clear()
        slipSubView = DialogUtil.createScanDialog(pointWithSlipsList)
        subViewAdapter = slipSubView.findViewById<RecyclerView>(R.id.point_with_slips).adapter as PointWithSlipsAdapter

        if (AppData.isAsCameraXLicenseCertified) {
            val cameraIcon = DialogUtil.generateCameraXIcon {
                launchAsCameraXScanView { symbologyType, decodeData ->
                    addSlip(decodeData, symbologyType) { isFinish ->
                        if (isFinish) {
                            changeFragment(ActionEnum.DELIVERY_LIST)
                        } else {
                            restartCameraDecode()
                        }
                    }
                }
            }
            appDialog?.addSubView(cameraIcon)
        }
        appDialog?.show()
        onReceivedBarcodeDecodeData { decodeData, symbology  -> addSlip(decodeData, symbology) }
    }

    /**
     * メニュー伝票表示押下
     * 伝票一覧のダイアログを表示する
     */
    private fun slipListShow() {
        val subView = DialogUtil.createSlipListDialog()
        appDialog = APPDialog.Builder(context)
            .addSubView(subView)
            .setSecondaryButton(R.string.strBtnClose)
        appDialog?.show()
    }

    /**
     * メニュー押下
     * メニュー項目をリスト形式で表示し、各項目に対応するアクションを設定します
     */
    fun showPopupMenu(view: View) {
        val menuItems = mutableListOf(
            PopupMenuItem("配送コース確認", R.drawable.baseline_course),
            PopupMenuItem("配送順変更/スキップ", R.drawable.baseline_up_down_arrows),
            PopupMenuItem("伝票表示", R.drawable.baseline_note)
        )
        PopupMenuUtil.show(
            view = view,
            resourceId = R.layout.popup_icon_item,
            menuItems = menuItems,
            onClick = { index ->
                when (index) {
                    // 配送コース確認押下
                    0 -> deliveryCourseConfirmation()
                    // 配送順変更/スキップ押下
                    1 -> deliveryChange()
                    // 伝票表示押下
                    2 -> slipListShow()
                }
            }
        )
    }

    /**
     * 配送先件数、伝票枚数、納品数を表示する
     * 得意先検品が完了した得意先数/コース中の得意先数：始発と終点、施設を除いた配送リスト上の得意先件数で表示する（始発と終点、施設を除く）
     * 得意先検品が完了した伝票枚数/配送する得意先のすべての伝票枚数：未受領の伝票は除いて集計する
     * 得意先検品が完了した商品数/配送する得意先のすべての商品数：未受領の伝票は除いて集計する
     */
    private fun initProgressData() {
        var pointCount = 0
        var slipCount = 0
        var productCount = 0
        var deliveredPoint = 0
        var deliveredSlip = 0
        var deliveredProduct = 0
        AppData.deliveryDataList.forEach { facility ->
            if (!facility.isStart && !facility.isEnd) { pointCount += facility.pointList.size }
            slipCount += facility.slipCount
            productCount += facility.productCount
            facility.pointList.forEach { point ->
                if (point.isDelivered) deliveredPoint++
                point.origin.deliverySlipList.forEach { slip ->
                    if (slip.isValid) {
                        if (point.isDelivered && (slip.status == SlipStatus.DELIVERED_RECEIVED.code || slip.status == SlipStatus.RETURNED.code)) deliveredSlip++
                        slip.deliverySlipRowBranchList.forEach { slipRow ->
                            if (point.isDelivered && (slip.status == SlipStatus.DELIVERED_RECEIVED.code || slip.status == SlipStatus.RETURNED.code)) deliveredProduct += slipRow.quantity
                        }
                    }
                }
            }
        }
        this.pointCount.postValue(pointCount)
        this.slipCount.postValue(slipCount)
        this.productCount.postValue(productCount)
        this.deliveredPoint.postValue(deliveredPoint)
        this.deliveredSlip.postValue(deliveredSlip)
        this.deliveredProduct.postValue(deliveredProduct)
    }

    /**
     * 復旧または他のユーザによって引き継がれたコースの為、完了した得意先検品の情報を表示することはできません
     */
    fun showPointFinishDialog() {
        appDialog = APPDialog.Builder(context)
            .setMessage(R.string.DIA0068)
            .setSecondaryButton(R.string.strOK)
        appDialog?.show()
    }

    fun onHiddenChanged(hidden: Boolean) {
        appDialog?.let {
            if (hidden) {
                it.hide()
            } else {
                it.show(false)
                setNavigationBack()
            }
        }
    }
    fun onDestroy() {
        appDialog?.close()
        distanceDialog?.dismiss()
        PopupMenuUtil.dismiss()
    }
}
