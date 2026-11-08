package dev.qcom.bandmenu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager

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
                            AppLog.i(TAG, "DISPLAY elapsedMs=${SystemClock.elapsedRealtime()} slot=$slot subId=$subId networkType=${info.networkType}(${ratName(info.networkType)}) overrideNetworkType=${info.overrideNetworkType}(${overrideName(info.overrideNetworkType)}) displayInfo=$info")
                        }

                        override fun onServiceStateChanged(serviceState: android.telephony.ServiceState) {
                            AppLog.i(TAG, "SERVICE elapsedMs=${SystemClock.elapsedRealtime()} slot=${info.simSlotIndex} subId=$subId state=${serviceState.state} voiceRat=${serviceState.voiceNetworkType}(${ratName(serviceState.voiceNetworkType)}) dataRat=${serviceState.dataNetworkType}(${ratName(serviceState.dataNetworkType)}) roaming=${serviceState.roaming} details=$serviceState")
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


    /** Log QMI's view beside Android's asynchronous telephony callbacks. */
    fun logQmiRound(sim: Int, snapshot: dev.qcom.bandmenu.network.NetworkSnapshot) {
        val nrCells = snapshot.cells.filter { it.rat == dev.qcom.bandmenu.network.Rat.NR }
            .joinToString(";") { cell -> "${cell.title}[${cell.fields.joinToString(",") { "${it.label}=${it.value}" }}]" }
            .ifEmpty { "none" }
        val rrc = snapshot.rrc.joinToString(";") { "${it.label}=${it.value}" }.ifEmpty { "none" }
        AppLog.i(TAG, "QMI_ROUND elapsedMs=${SystemClock.elapsedRealtime()} sim=$sim mode=${snapshot.mode} modeLabel=${snapshot.modeLabel} NR_cells=$nrCells RRC=$rrc roundMs=${snapshot.roundMs}")
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
        TelephonyManager.NETWORK_TYPE_LTE_CA -> "LTE_CA"
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
