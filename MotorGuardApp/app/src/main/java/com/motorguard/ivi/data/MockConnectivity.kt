package com.motorguard.ivi.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Emulator / demo Wi-Fi. Realistic fake data with all the interactions wired. */
class MockWifiRepo : WifiRepo {
    private var _enabled by mutableStateOf(true)
    override val enabled: Boolean get() = _enabled
    override var connectedSsid by mutableStateOf<String?>("MotorGuard-5G")
        private set

    private val _known = mutableStateListOf("MotorGuard-5G")
    override val known: List<String> get() = _known

    override val networks = listOf(
        WifiNetwork("MotorGuard-5G", secured = true, signal = 3),
        WifiNetwork("Garage_WiFi", secured = true, signal = 2),
        WifiNetwork("ITI-Guest", secured = false, signal = 2),
        WifiNetwork("Neighbor_2.4", secured = true, signal = 1),
    )

    override fun setEnabled(enabled: Boolean) { _enabled = enabled }

    override fun connect(ssid: String, password: String?) {
        connectedSsid = ssid
        if (ssid !in _known) _known.add(ssid)
    }

    override fun disconnect() { connectedSsid = null }

    override fun forget(ssid: String) {
        _known.remove(ssid)
        if (connectedSsid == ssid) connectedSsid = null
    }
}

/** Emulator / demo Bluetooth. */
class MockBtRepo : BtRepo {
    private var _enabled by mutableStateOf(true)
    override val enabled: Boolean get() = _enabled
    override var connectedName by mutableStateOf<String?>("Abdelrahman’s iPhone")
        private set

    private val _paired = mutableStateListOf(
        BtDevice("Abdelrahman’s iPhone", BtKind.PHONE),
        BtDevice("Galaxy Buds Pro", BtKind.AUDIO),
        BtDevice("Pixel Watch", BtKind.WEARABLE),
    )
    override val paired: List<BtDevice> get() = _paired

    override fun setEnabled(enabled: Boolean) { _enabled = enabled }

    override fun toggleConnect(name: String) {
        connectedName = if (connectedName == name) null else name
    }

    override fun unpair(name: String) {
        _paired.removeAll { it.name == name }
        if (connectedName == name) connectedName = null
    }

    override fun rename(oldName: String, newName: String) {
        val i = _paired.indexOfFirst { it.name == oldName }
        if (i >= 0) {
            if (connectedName == oldName) connectedName = newName
            _paired[i] = _paired[i].copy(name = newName)
        }
    }
}

/** Emulator / demo driver profiles. A few fake drivers with switch/add/remove fully wired. */
class MockUsersRepo : UsersRepo {
    private var nextId = 100

    private val _users = mutableStateListOf(
        UserProfile(id = 0, name = "Driver", isActive = true, color = 0),
        UserProfile(id = 1, name = "Alex", isActive = false, color = 1),
        UserProfile(id = 2, name = "Sam", isActive = false, color = 2),
    )
    override val users: List<UserProfile> get() = _users
    override val active: UserProfile? get() = _users.firstOrNull { it.isActive }

    override fun switchTo(id: Int) {
        for (i in _users.indices) {
            _users[i] = _users[i].copy(isActive = _users[i].id == id)
        }
    }

    override fun addUser(name: String) {
        _users.add(UserProfile(id = nextId++, name = name, isActive = false, color = _users.size))
    }

    override fun removeUser(id: Int) {
        val wasActive = _users.firstOrNull { it.id == id }?.isActive == true
        _users.removeAll { it.id == id }
        // Removing the active user falls back to the first remaining profile.
        if (wasActive) _users.firstOrNull()?.let { switchTo(it.id) }
    }

    override fun addGuest() {
        if (_users.any { it.isGuest }) return // one guest at a time
        _users.add(UserProfile(id = nextId++, name = "Guest", isActive = false, isGuest = true, color = _users.size))
    }
}
