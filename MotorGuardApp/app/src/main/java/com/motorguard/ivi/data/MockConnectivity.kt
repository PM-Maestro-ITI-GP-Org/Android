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

    // Saved networks persist: "known" is a claim about the past, so forgetting it on every
    // restart would make Forget the only button that appears to work.
    private val _known = mutableStateListOf<String>().apply {
        val saved = LocalStore.getString(LocalStore.Keys.WIFI_KNOWN)
        if (saved.isNullOrBlank()) add("MotorGuard-5G") else addAll(saved.split('\n').filter { it.isNotBlank() })
    }
    override val known: List<String> get() = _known

    private fun persistKnown() =
        LocalStore.putString(LocalStore.Keys.WIFI_KNOWN, _known.joinToString("\n"))

    override val networks = listOf(
        WifiNetwork("MotorGuard-5G", secured = true, signal = 3),
        WifiNetwork("Garage_WiFi", secured = true, signal = 2),
        WifiNetwork("ITI-Guest", secured = false, signal = 2),
        WifiNetwork("Neighbor_2.4", secured = true, signal = 1),
    )

    override fun setEnabled(enabled: Boolean) { _enabled = enabled }

    override fun connect(ssid: String, password: String?) {
        connectedSsid = ssid
        if (ssid !in _known) {
            _known.add(ssid)
            persistKnown()
        }
    }

    override fun disconnect() { connectedSsid = null }

    override fun forget(ssid: String) {
        _known.remove(ssid)
        persistKnown()
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
/**
 * Driver profiles kept by the app itself, saved to [LocalStore].
 *
 * Used on any build that is not platform-signed — the emulator, a phone, a debug APK — where the
 * real Android multi-user APIs are out of reach. These are the app's own profiles rather than
 * system users, but they are genuinely the driver's: renaming one, or picking its colour, is
 * still there after a reboot, which is the part that actually matters to whoever is using it.
 *
 * A guest is deliberately not persisted as "someone who was here" beyond its profile row; it is
 * removed like any other. One guest at a time.
 */
class LocalUsersRepo : UsersRepo {

    private val _users = mutableStateListOf<UserProfile>()
    override val users: List<UserProfile> get() = _users
    override val active: UserProfile? get() = _users.firstOrNull { it.isActive }

    private var nextId = 100

    init {
        val saved = UsersStore.load()
        if (saved.isNullOrEmpty()) {
            _users.addAll(
                listOf(
                    UserProfile(id = 0, name = "Driver", isActive = true, color = 0),
                    UserProfile(id = 1, name = "Alex", isActive = false, color = 1),
                ),
            )
        } else {
            _users.addAll(saved)
        }
        // Never hand out an id that is already taken — ids are the key everything else uses.
        nextId = ((_users.maxOfOrNull { it.id } ?: 0) + 1).coerceAtLeast(100)
        // A saved list whose active profile was somehow lost would leave the UI with no
        // selection and no way to get one back.
        if (_users.none { it.isActive }) _users.firstOrNull()?.let { switchTo(it.id) } else persist()
    }

    override fun switchTo(id: Int) {
        for (i in _users.indices) {
            _users[i] = _users[i].copy(isActive = _users[i].id == id)
        }
        persist()
    }

    override fun addUser(name: String) {
        val clean = name.trim().ifBlank { "Driver" }
        _users.add(UserProfile(id = nextId++, name = clean, isActive = false, color = _users.size))
        persist()
    }

    override fun removeUser(id: Int) {
        val wasActive = _users.firstOrNull { it.id == id }?.isActive == true
        _users.removeAll { it.id == id }
        // Removing the active profile falls back to the first remaining one.
        if (wasActive && _users.isNotEmpty()) switchTo(_users.first().id) else persist()
    }

    override fun addGuest() {
        if (_users.any { it.isGuest }) return // one guest at a time
        _users.add(
            UserProfile(
                id = nextId++,
                name = "Guest",
                isActive = false,
                isGuest = true,
                color = _users.size,
            ),
        )
        persist()
    }

    override fun rename(id: Int, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        val i = _users.indexOfFirst { it.id == id }
        if (i < 0) return
        // initial is derived from the name, so it has to be recomputed rather than carried over.
        _users[i] = _users[i].copy(
            name = clean,
            initial = clean.firstOrNull()?.uppercase() ?: "?",
        )
        persist()
    }

    override fun setColor(id: Int, color: Int) {
        val i = _users.indexOfFirst { it.id == id }
        if (i < 0) return
        _users[i] = _users[i].copy(color = color)
        persist()
    }

    private fun persist() = UsersStore.save(_users)
}

/**
 * Profile list ⇄ JSON in [LocalStore]. `org.json` ships with Android, so this needs no
 * serialization dependency for what is a handful of short records.
 */
private object UsersStore {

    fun load(): List<UserProfile>? = runCatching {
        val raw = LocalStore.getString(LocalStore.Keys.USERS) ?: return null
        val array = org.json.JSONArray(raw)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val name = o.optString("name", "Driver")
            UserProfile(
                id = o.getInt("id"),
                name = name,
                isActive = o.optBoolean("active", false),
                isGuest = o.optBoolean("guest", false),
                initial = name.trim().firstOrNull()?.uppercase() ?: "?",
                color = o.optInt("color", 0),
            )
        }
    }.getOrNull()

    fun save(users: List<UserProfile>) {
        runCatching {
            val array = org.json.JSONArray()
            users.forEach { u ->
                array.put(
                    org.json.JSONObject()
                        .put("id", u.id)
                        .put("name", u.name)
                        .put("active", u.isActive)
                        .put("guest", u.isGuest)
                        .put("color", u.color),
                )
            }
            LocalStore.putString(LocalStore.Keys.USERS, array.toString())
        }
    }
}
