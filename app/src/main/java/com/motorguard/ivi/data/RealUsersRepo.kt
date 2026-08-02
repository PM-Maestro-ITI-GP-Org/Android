package com.motorguard.ivi.data

import android.app.ActivityManager
import android.content.Context
import android.os.UserManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Real driver profiles via UserManager. Listing/reading the device users works on a
 * privileged platform build (INTERACT_ACROSS_USERS + MANAGE_USERS). Switching the active
 * user (ActivityManager.switchUser) and creating/removing users (UserManager.createUser/
 * removeUser) are privileged system APIs / hidden methods — left as TODO to finish on real
 * hardware (the emulator's unprivileged build can't exercise them; they no-op there).
 *
 * Every privileged call is wrapped in runCatching so a denied call never crashes the app.
 */
class RealUsersRepo(context: Context) : UsersRepo {

    private val appContext = context.applicationContext
    private val um = context.getSystemService(UserManager::class.java)
    private val am = context.getSystemService(ActivityManager::class.java)

    private val _users = mutableStateListOf<UserProfile>()
    override val users: List<UserProfile> get() = _users
    override var active by mutableStateOf<UserProfile?>(null)
        private set

    init {
        refresh()
    }

    /** Current process user id. UserHandle.myUserId() is hidden — read it reflectively. */
    private fun currentUserId(): Int = runCatching {
        val m = android.os.UserHandle::class.java.getMethod("myUserId")
        m.invoke(null) as Int
    }.getOrDefault(0)

    private fun refresh() {
        val me = currentUserId()
        _users.clear()
        // TODO(on-device): UserManager.getUsers() is a hidden system API (needs MANAGE_USERS).
        // Reflect it to enumerate every driver profile; on an unprivileged build this throws
        // and we fall back to the single current user below.
        val listed = runCatching {
            @Suppress("UNCHECKED_CAST")
            val infos = UserManager::class.java
                .getMethod("getUsers")
                .invoke(um) as List<Any>
            infos.mapIndexedNotNull { i, info ->
                val cls = info.javaClass
                val id = cls.getField("id").getInt(info)
                val name = runCatching { cls.getField("name").get(info) as? String }.getOrNull()
                    ?: "User $id"
                val isGuest = runCatching { cls.getMethod("isGuest").invoke(info) as Boolean }
                    .getOrDefault(false)
                UserProfile(
                    id = id,
                    name = name,
                    isActive = id == me,
                    isGuest = isGuest,
                    // Our colour choice for this system user, if the driver ever picked one.
                    color = LocalStore.getInt(userColorKey(id), i),
                )
            }
        }.getOrNull()

        if (listed.isNullOrEmpty()) {
            // Fallback: only the current user is visible without privilege.
            val name = runCatching { um?.userName }.getOrNull()?.ifBlank { null } ?: "Driver"
            _users.add(
                UserProfile(
                    id = me,
                    name = name,
                    isActive = true,
                    color = LocalStore.getInt(userColorKey(me), 0),
                ),
            )
        } else {
            _users.addAll(listed)
        }
        active = _users.firstOrNull { it.isActive }
    }

    // TODO(on-device): ActivityManager.switchUser(int) is a privileged hidden API.
    override fun switchTo(id: Int) {
        runCatching {
            ActivityManager::class.java
                .getMethod("switchUser", Int::class.javaPrimitiveType)
                .invoke(am, id)
        }
        refresh()
    }

    // TODO(on-device): UserManager.createUser(name, flags) needs MANAGE_USERS.
    override fun addUser(name: String) {
        runCatching {
            UserManager::class.java
                .getMethod("createUser", String::class.java, Int::class.javaPrimitiveType)
                .invoke(um, name, 0)
        }
        refresh()
    }

    // TODO(on-device): UserManager.removeUser(int) needs MANAGE_USERS.
    override fun removeUser(id: Int) {
        runCatching {
            UserManager::class.java
                .getMethod("removeUser", Int::class.javaPrimitiveType)
                .invoke(um, id)
        }
        refresh()
    }

    // TODO(on-device): UserManager.setUserName(int, String) needs MANAGE_USERS. The avatar colour
    // has no platform equivalent at all, so it is kept next to the profile in LocalStore — the
    // system owns who the users are, we own how they look in this UI.
    override fun rename(id: Int, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        runCatching {
            UserManager::class.java
                .getMethod("setUserName", Int::class.javaPrimitiveType, String::class.java)
                .invoke(um, id, clean)
        }
        refresh()
    }

    override fun setColor(id: Int, color: Int) {
        LocalStore.putInt(userColorKey(id), color)
        val i = _users.indexOfFirst { it.id == id }
        if (i >= 0) _users[i] = _users[i].copy(color = color)
    }

    private fun userColorKey(id: Int) = "${LocalStore.Keys.USERS}.color.$id"

    // TODO(on-device): UserManager.createGuest(context) / createUser(GUEST flag) needs MANAGE_USERS.
    override fun addGuest() {
        runCatching {
            UserManager::class.java
                .getMethod("createGuest", Context::class.java)
                .invoke(um, appContext)
        }
        refresh()
    }
}
