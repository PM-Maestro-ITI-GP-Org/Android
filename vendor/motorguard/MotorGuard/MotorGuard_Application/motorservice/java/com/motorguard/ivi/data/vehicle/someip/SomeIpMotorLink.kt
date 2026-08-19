package com.motorguard.ivi.data.vehicle.someip

import android.util.Log
import com.motorguard.ivi.data.vehicle.api.MotorTelemetry
import com.motorguard.ivi.data.vehicle.api.SignalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the native link for the life of the process and publishes the motor signal off it.
 *
 * One instance, created by [SomeIpVehicleData] and never cancelled — docs/09 §6: the source has to
 * survive the diagnostics fragment being destroyed and rebuilt on every tab switch, so it cannot
 * belong to a fragment or a ViewModel. Reconnection is automatic and is the native side's job;
 * [reconnect] only exists as a manual nudge for a user who is already suspicious.
 */
internal class SomeIpMotorLink(
    private val scope: CoroutineScope,
    config: MotorLinkConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) : MotorLinkNative.Listener {

    private val freshness = MotorFreshness()
    private val _motor = MutableStateFlow<SignalState<MotorTelemetry>>(SignalState.Loading)

    /** Always readable, never throwing, exactly as docs/09 §1.1 requires. */
    val motor: StateFlow<SignalState<MotorTelemetry>> = _motor.asStateFlow()

    private val handle: Long = MotorLinkNative.nativeOpen(
        this,
        config.serviceId,
        config.instanceId,
        config.majorVersion,
        config.eventgroupId,
        config.eventId,
        config.captureMethodId,
        config.clientId,
        config.sdMulticast,
        config.sdPort,
        config.localEventPort,
        config.staticHost,
        config.staticUdpPort,
        config.staticTcpPort,
        config.subscribeTtlSec,
        config.captureTimeoutMs,
        config.androidNetworkHandle,
    )

    val opened: Boolean get() = handle != 0L

    /** For [SomeIpMotorCaptureSource]; 0 when the link never opened. */
    val nativeHandle: Long get() = handle

    private val ticker: Job = scope.launch {
        if (!opened) {
            // No sockets at all — a device with no network stack to speak of. There is nothing to
            // wait for, and Loading would be a lie that never resolves.
            _motor.value = SignalState.Offline
            return@launch
        }
        while (isActive) {
            publish()
            // Freshness is a wall-clock property, so it has to be re-evaluated even when nothing
            // arrives: that is the entire point of Stale. A quarter second is finer than the
            // badge's own resolution and cheap enough to be invisible.
            delay(TICK_MS)
        }
    }

    override fun onEvent(
        faultType: Int,
        severity: Int,
        flags: Int,
        remoteTimestampMs: Long,
        rulHours: Float,
        rulPercent: Float,
    ) {
        val telemetry = MotorEventMapping.telemetry(faultType, severity, flags, rulHours, rulPercent)
        val at = clock()
        // Hop to the source's own scope: the callback arrives on the native link thread, and the
        // contract with the UI is that every emission is main-thread confined.
        scope.launch {
            freshness.onEvent(telemetry, at)
            publish()
        }
    }

    override fun onLink(state: Int) {
        scope.launch {
            if (state == LINK_DOWN) {
                Log.i(MotorLinkNative.TAG, "link down")
                freshness.onLinkDown()
            } else {
                freshness.onLinkUp()
            }
            publish()
        }
    }

    fun reconnect() {
        if (opened) MotorLinkNative.nativeReconnect(handle)
    }

    /**
     * Only for tests and for a host process that really is shutting down. The production instance
     * is deliberately never closed: it lives as long as the app does.
     */
    fun close() {
        ticker.cancel()
        if (opened) MotorLinkNative.nativeClose(handle)
    }

    private fun publish() {
        _motor.value = freshness.state(clock())
    }

    private companion object {
        const val TICK_MS = 250L
        const val LINK_DOWN = 0
    }
}
