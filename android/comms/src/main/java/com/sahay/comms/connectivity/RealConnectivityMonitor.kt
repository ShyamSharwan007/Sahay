package com.sahay.comms.connectivity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.sahay.core.contracts.ConnectivityMonitor
import com.sahay.core.contracts.ConnectivityState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * - internet: the default network has working internet (validated by the system, not just "connected to Wi-Fi");
 * - cellular: a SIM is ready and airplane mode is off (so SMS is possible);
 * - mesh fields stay 0/false until the Bluetooth mesh exists.
 * Lives as long as the app, so the callbacks are never unregistered.
 */
@Singleton
class RealConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConnectivityMonitor {

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private val mutableState = MutableStateFlow(
        ConnectivityState(internet = readInternet(), cellular = readCellular(), meshActive = false, meshPeers = 0),
    )
    override val state: StateFlow<ConnectivityState> = mutableState.asStateFlow()

    init {
        watchInternet()
        watchSimAndAirplaneMode()
    }

    private fun watchInternet() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                setInternet(capabilities.hasUsableInternet())
            }

            override fun onLost(network: Network) = setInternet(false)
        }
        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to watch the network: ${e.message}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not watch the network: ${e.message}")   // too many callbacks registered on some devices
        }
    }

    private fun watchSimAndAirplaneMode() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mutableState.update { it.copy(cellular = readCellular()) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(SIM_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun setInternet(online: Boolean) = mutableState.update { it.copy(internet = online) }

    private fun readInternet(): Boolean = try {
        val manager = connectivityManager
        manager?.getNetworkCapabilities(manager.activeNetwork)?.hasUsableInternet() == true
    } catch (e: SecurityException) {
        false
    }

    private fun readCellular(): Boolean = try {
        val airplaneOn = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val hasPhone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        !airplaneOn && hasPhone && telephony != null &&
            (0 until maxOf(telephony.phoneCount, 1)).any { telephony.getSimState(it) == TelephonyManager.SIM_STATE_READY }
    } catch (e: RuntimeException) {
        false
    }

    private fun NetworkCapabilities.hasUsableInternet() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private companion object {
        const val TAG = "RealConnectivityMonitor"
        // Protected system broadcast; the public constant only exists from API 28.
        const val SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"
    }
}
