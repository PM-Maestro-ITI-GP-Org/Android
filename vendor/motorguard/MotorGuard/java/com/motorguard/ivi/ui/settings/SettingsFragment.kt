// SettingsFragment — owner E
// Connectivity, theme, and system.
package com.motorguard.ivi.ui.settings

import androidx.fragment.app.Fragment

/**
 * FEATURES
 *  - Wi-Fi   : toggle, scan/network list, connection state   (WifiManager)
 *  - Bluetooth: toggle, paired-device list, roles audio/phone (BluetoothAdapter)
 *  - Theme   : Day / Night picker + Auto day-night toggle     (UiModeManager + light sensor)
 *  - Accent  : color / ambient-LED sync
 *  - System  : MotorGuard OS version, OTA, About vehicle (VIN/licenses)
 * READS   : wifi/bt state, current UiMode, OTA status
 * WRITES  : wifi connect, bt pair/connect, theme mode, accent
 */
class SettingsFragment : Fragment()
