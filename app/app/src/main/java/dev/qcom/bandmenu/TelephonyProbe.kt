package dev.qcom.bandmenu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import dev.qcom.bandmenu.network.SysInfo

/**
 * Read-only Android-side view of NR registration/display state.
 *
 * This deliberately runs beside (not inside) the QMI NAS reader: the same
 * subscription is identified by its real Android subscriptionId, allowing
 * QMI and Android observations to be compared without assuming SIM slot == subId.
 */
class TelephonyProbe(context: Context) {
    private val appContext = context.applicationContext
    private val callbacks = linkedMapOf<Int, Pair<TelephonyManager, TelephonyCallback>>()
    private val displayInfoBySubId = mutableMapOf<Int, String>()
    private val serviceStateBySubId = mutableMapOf<Int, String>()
    private val lock = Any()

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            AppLog.i(TAG, "Android telephony display callback requires API 31+; device API=${Build.VERSION.SDK_INT}")
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            AppLog.w(TAG, "READ_PHONE_STATE not granted; Android NR telemetry unavailable")
            return
        }

        synchronized(lock) {
            if (callbacks.isNotEmpty()) return
            try {
                val subscriptionManager = appContext.getSystemService(SubscriptionManager::class.java)
                val subscriptions = subscriptionManager.activeSubscriptionInfoList.orEmpty()
                AppLog.i(TAG, "START elapsedMs=${SystemClock.elapsedRealtime()} activeSubscriptions=${subscriptions.size} defaultDataSubId=${SubscriptionManager.getDefaultDataSubscriptionId()}")
                if (subscriptions.isEmpty()) {
                    AppLog.w(TAG, "No active Android subscriptions returned")
                    return
                }

                subscriptions.forEach { info ->
                    val subId = info.subscriptionId
                    val slot = info.simSlotIndex
                    val tm = appContext.getSystemService(TelephonyManager::class.java).createForSubscriptionId(subId)
                    AppLog.i(TAG, "SUB slot=$slot subId=$subId carrier=${info.carrierName} operator=${runCatching { tm.networkOperator }.getOrNull()} dataNetworkType=${runCatching { tm.dataNetworkType }.getOrNull()} serviceState=${runCatching { tm.serviceState?.toString() }.getOrNull()}")
                    val callback = object : TelephonyCallback(),
                        TelephonyCallback.DisplayInfoListener,
                        TelephonyCallback.ServiceStateListener {
                        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                            synchronized(lock) { displayInfoBySubId[subId] = "networkType=${info.networkType}(${ratName(info.networkType)}) overrideNetworkType=${info.overrideNetworkType}(${overrideName(info.overrideNetworkType)}) details=$info" }
                            AppLog.i(TAG, "DISPLAY elapsedMs=${SystemClock.elapsedRealtime()} slot=$slot subId=$subId networkType=${info.networkType}(${ratName(info.networkType)}) overrideNetworkType=${info.overrideNetworkType}(${overrideName(info.overrideNetworkType)}) displayInfo=$info")
                        }

                        override fun onServiceStateChanged(serviceState: android.telephony.ServiceState) {
                            synchronized(lock) { serviceStateBySubId[subId] = serviceState.toString() }
                            AppLog.i(TAG, "SERVICE elapsedMs=${SystemClock.elapsedRealtime()} slot=$slot subId=$subId state=${serviceState.state} roaming=${serviceState.roaming} details=$serviceState")
                        }
                    }
                    tm.registerTelephonyCallback(appContext.mainExecutor, callback)
                    callbacks[subId] = tm to callback
                }
            } catch (e: SecurityException) {
                AppLog.e(TAG, "Cannot register Android telephony callbacks: permission/security exception", e)
            } catch (e: Exception) {
                AppLog.e(TAG, "Cannot start Android telephony probe", e)
            }
        }
    }


    /**
     * Synchronous Android-side snapshot included in the copyable diagnostic report.
     * Logcat callbacks are useful during development but are not part of the user's
     * exported QMI report, so collect the current public TelephonyManager state here.
     */
    fun snapshotText(): String {
        val elapsed = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "ANDROID_TELEPHONY elapsedMs=$elapsed unavailable=requires_API_30"
        }
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "ANDROID_TELEPHONY elapsedMs=$elapsed unavailable=READ_PHONE_STATE_not_granted"
        }
        return try {
            val subscriptionManager = appContext.getSystemService(SubscriptionManager::class.java)
            val subscriptions = subscriptionManager.activeSubscriptionInfoList.orEmpty()
                .sortedBy { it.simSlotIndex }
            val lines = mutableListOf(
                "ANDROID_TELEPHONY elapsedMs=$elapsed activeSubscriptions=${subscriptions.size} defaultDataSubId=${SubscriptionManager.getDefaultDataSubscriptionId()}"
            )
            if (subscriptions.isEmpty()) {
                lines += "ANDROID_TELEPHONY no_active_subscriptions"
            }
            subscriptions.forEach { sub ->
                val subId = sub.subscriptionId
                val tm = appContext.getSystemService(TelephonyManager::class.java)
                    .createForSubscriptionId(subId)
                val networkType = runCatching { tm.networkType.toString() }.getOrElse { "error:${it.javaClass.simpleName}" }
                val dataNetworkType = runCatching { tm.dataNetworkType.toString() }.getOrElse { "error:${it.javaClass.simpleName}" }
                val operator = runCatching { tm.networkOperator }.getOrNull() ?: "unavailable"
                val operatorName = runCatching { tm.networkOperatorName }.getOrNull() ?: "unavailable"
                val voiceNetworkType = runCatching { tm.voiceNetworkType.toString() }.getOrElse { "error:${it.javaClass.simpleName}" }
                val dataEnabled = runCatching { tm.isDataEnabled.toString() }.getOrElse { "error:${it.javaClass.simpleName}" }
                val displayInfo = synchronized(lock) {
                    displayInfoBySubId[subId] ?: "callback_not_received_yet"
                }
                val carrierConfig = runCatching {
                    val manager = appContext.getSystemService(CarrierConfigManager::class.java)
                    val bundle = manager?.getConfigForSubId(subId)
                    bundle?.getString(CarrierConfigManager.KEY_5G_ICON_CONFIGURATION_STRING)
                        ?.let { "5g_icon_configuration_string=${it.replace("\n", " ")}" }
                        ?: "5g_icon_configuration_string=not_set_or_unavailable"
                }.getOrElse { "carrier_config_error=${it.javaClass.simpleName}:${it.message}" }
                val serviceState = runCatching { tm.serviceState?.toString() ?: "null" }
                    .getOrElse { "unavailable:${it.javaClass.simpleName}:${it.message}" }
                val callbackServiceState = synchronized(lock) {
                    serviceStateBySubId[subId] ?: "callback_not_received_yet"
                }
                lines += "ANDROID_SIM slot=${sub.simSlotIndex} subId=$subId carrier=${sub.carrierName} operator=$operator operatorName=$operatorName networkType=$networkType dataNetworkType=$dataNetworkType voiceNetworkType=$voiceNetworkType dataEnabled=$dataEnabled displayInfoCallback={$displayInfo} serviceState=$serviceState serviceStateCallback={$callbackServiceState} $carrierConfig"
            }
            lines.joinToString("\n")
        } catch (e: Exception) {
            "ANDROID_TELEPHONY elapsedMs=$elapsed snapshot_error=${e.javaClass.simpleName}:${e.message}"
        }
    }

    /** Log QMI's view beside Android's asynchronous telephony callbacks. */
    fun logQmiRound(sim: Int, snapshot: dev.qcom.bandmenu.network.NetworkSnapshot, sysInfo: SysInfo?) {
        val nrCells = snapshot.cells.filter { it.rat == dev.qcom.bandmenu.network.Rat.NR }
            .joinToString(";") { cell -> "${cell.title}[${cell.fields.joinToString(",") { "${it.label}=${it.value}" }}]" }
            .ifEmpty { "none" }
        val rrc = snapshot.rrc.joinToString(";") { "${it.label}=${it.value}" }.ifEmpty { "none" }
        val nrStatus = sysInfo?.status?.entries?.joinToString(";") { "${it.key}=${it.value.status}/${it.value.trueStatus}" } ?: "unavailable"
        AppLog.i(TAG, "QMI_ROUND elapsedMs=${SystemClock.elapsedRealtime()} sim=$sim mode=${snapshot.mode} modeLabel=${snapshot.modeLabel} NR_status=$nrStatus ENDC_available=${sysInfo?.endcAvailable} DCNR_restricted=${sysInfo?.dcnrRestricted} NR_ARFCN=${sysInfo?.nrArfcn} NR_PCI=${sysInfo?.nrPci} NR_NCI=${sysInfo?.nrNci} NR_cells=$nrCells RRC=$rrc roundMs=${snapshot.roundMs}")
    }

    fun stop() {
        synchronized(lock) {
            callbacks.values.forEach { (tm, callback) ->
                runCatching { tm.unregisterTelephonyCallback(callback) }
            }
            val count = callbacks.size
            callbacks.clear()
            if (count > 0) AppLog.i(TAG, "STOP elapsedMs=${SystemClock.elapsedRealtime()} callbacks=$count")
        }
    }

    private fun ratName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        else -> "OTHER"
    }

    private fun overrideName(type: Int): String = when (type) {
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE -> "NONE"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE_CA"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE_ADVANCED_PRO"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR_NSA"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE -> "NR_NSA_MMWAVE"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "NR_ADVANCED"
        else -> "OTHER"
    }

    companion object {
        private const val TAG = "QBM-Telephony"
    }
}
