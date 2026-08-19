package com.motorguard.ivi.data.vehicle.someip

import android.util.Log

/**
 * Where the diagnostics unit is and what it answers to.
 *
 * The identity values are the assignment recorded in `motorservice/README.md` — docs/10 §2 left
 * the service ID blank and §10 lists it as the first thing to settle. They are constants here
 * rather than resources because both ends have to agree on them exactly, and a number that can be
 * overridden per-device is a number that will differ per-device.
 *
 * The *endpoint* values are the opposite case and can be overridden with system properties, set
 * with `adb shell setprop` before the app starts:
 *
 * ```
 * persist.motorguard.someip.host      static peer address; disables discovery when set
 * persist.motorguard.someip.udp_port  its event port     (default 30502)
 * persist.motorguard.someip.tcp_port  its capture port   (default 30503)
 * persist.motorguard.someip.sd_addr   SD multicast group (default 224.244.224.245)
 * persist.motorguard.someip.sd_port   SD port            (default 30490)
 * ```
 *
 * The static-host escape hatch earns its place on the first bring-up: multicast is what a bench
 * switch, a hypervisor bridge or a Wi-Fi link drops silently, and being able to say "it works
 * pointed straight at the unit but not through discovery" is a diagnosis rather than a shrug.
 */
internal data class MotorLinkConfig(
    val serviceId: Int = 0x1241,
    val instanceId: Int = 0x0001,
    val majorVersion: Int = 1,
    val eventgroupId: Int = 0x0001,
    val eventId: Int = 0x8001,
    val captureMethodId: Int = 0x0002,
    /** Ours on the bus; only has to be unique among this service's clients. */
    val clientId: Int = 0x1341,

    val sdMulticast: String = "224.244.224.245",
    val sdPort: Int = 30490,
    /** 0 asks the kernel; the port is advertised in the subscribe, so nothing needs to guess it. */
    val localEventPort: Int = 0,

    val staticHost: String = "",
    val staticUdpPort: Int = 30502,
    val staticTcpPort: Int = 30503,

    val subscribeTtlSec: Int = 16,
    /** docs/09 §5.3: give up at 20 s. A spinner that never resolves is the worst failure to render. */
    val captureTimeoutMs: Int = 20_000,
    /** docs/10 §6: the window the unit is asked for. It may clamp, and reports what it did. */
    val requestedCaptureSec: Float = 10f,

    /**
     * The Ethernet [android.net.Network]'s handle, or 0 if none was up when this was built —
     * see [SomeIpVehicleData]. Every socket the native link opens is bound to it, which is what
     * keeps discovery and events on the wire the diagnostics unit is actually reachable on
     * instead of whatever ConnectivityManager calls the default network (Wi-Fi, on this board).
     */
    val androidNetworkHandle: Long = 0L,
) {
    companion object {
        /**
         * Reads the endpoint overrides. `android.os.SystemProperties` is reached by reflection
         * rather than directly: the class is hidden, and while this app is platform-signed and
         * built against `platform_apis` — so it could call it outright — going through reflection
         * keeps these sources compilable outside that build and turns an absent class into the
         * documented defaults instead of a link error.
         */
        fun fromSystemProperties(): MotorLinkConfig {
            val defaults = MotorLinkConfig()
            val host = prop("persist.motorguard.someip.host", defaults.staticHost)
            val config = defaults.copy(
                staticHost = host,
                staticUdpPort = propInt("persist.motorguard.someip.udp_port", defaults.staticUdpPort),
                staticTcpPort = propInt("persist.motorguard.someip.tcp_port", defaults.staticTcpPort),
                sdMulticast = prop("persist.motorguard.someip.sd_addr", defaults.sdMulticast),
                sdPort = propInt("persist.motorguard.someip.sd_port", defaults.sdPort),
            )
            if (host.isNotEmpty()) {
                Log.i(MotorLinkNative.TAG, "static peer $host (discovery bypassed)")
            }
            return config
        }

        private fun prop(key: String, fallback: String): String = runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            get.invoke(null, key, fallback) as String
        }.getOrDefault(fallback)

        private fun propInt(key: String, fallback: Int): Int =
            prop(key, "").toIntOrNull() ?: fallback
    }
}
