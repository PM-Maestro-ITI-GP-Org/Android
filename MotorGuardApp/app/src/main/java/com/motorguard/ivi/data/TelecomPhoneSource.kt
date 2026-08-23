package com.motorguard.ivi.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real phone backend. The head unit is the HFP **hands-free** unit; the handset is the
 * audio gateway. Outgoing calls go out through the Bluetooth stack's PhoneAccount
 * (`HfpClientConnectionService`), and control of the live call comes back to us through
 * [InCallBridge] because we are bound as the `InCallService`.
 *
 * Requires, at runtime: BLUETOOTH_CONNECT, CALL_PHONE, READ_CONTACTS, READ_CALL_LOG,
 * READ_PHONE_STATE — and the DIALER role, or the platform never binds our InCallService:
 *
 *   adb shell cmd role add-role-holder android.app.role.DIALER com.motorguard.ivi
 *
 * Every provider/Telecom call is wrapped: on a Pi image without a Bluetooth stack these
 * throw or return empty, and the surface must degrade to "No phone connected" rather
 * than crash the launcher.
 */
class TelecomPhoneSource(private val app: Context) : PhoneRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val telecom: TelecomManager? =
        app.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val audioManager: AudioManager? =
        app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** Held for the lifetime of a call so media apps duck; null when idle. */
    private var duckingFocus: AudioFocusRequest? = null

    /**
     * The hands-free link. On this board it is the only thing that ever sees a call — see
     * [HfpCallSource] for why Telecom cannot be used here — and it owns the SCO link that
     * carries the driver's voice to the phone.
     */
    private val hfp = HfpCallSource(app)

    /** Rings only when the phone is not ringing for us over SCO. See [CallRingtone]. */
    private val ringtone = CallRingtone(app)

    private val _link = MutableStateFlow(PhoneLink.DISCONNECTED)
    private val _deviceName = MutableStateFlow<String?>(null)
    private val _call = MutableStateFlow<ActiveCall?>(null)
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    private val _recents = MutableStateFlow<List<CallLogEntry>>(emptyList())

    /**
     * Mute, on the hands-free path.
     *
     * [InCallBridge] carries it when Telecom owns the call, but with no InCallService bound
     * there is nothing behind it — the button toggled a flag no one read. The car is the
     * hands-free unit, so the microphone being muted is *our* microphone, and this is the
     * state of it.
     */
    private val _micMuted = MutableStateFlow(false)

    override val link: StateFlow<PhoneLink> = _link.asStateFlow()
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()
    override val call: StateFlow<ActiveCall?> = _call.asStateFlow()
    override val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()
    override val recents: StateFlow<List<CallLogEntry>> = _recents.asStateFlow()

    /** Answer time is ours to keep: Telecom's connectTimeMillis is wall clock, not monotonic. */
    private var answeredAtElapsedMs = 0L

    /**
     * Every known number → display name, including a person's second and third numbers.
     *
     * [_contacts] deliberately keeps one entry per person, because that is what the list
     * shows. This keeps them all: the caller is whichever number actually rang, and
     * matching only the first one is how someone in the phonebook still comes up as a
     * bare number on the in-call screen.
     */
    private val numberIndex = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val hfpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_HFP_CLIENT_CONNECTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            applyLink(state, device)
            if (state == BluetoothProfile.STATE_CONNECTED) refresh()
        }
    }

    init {
        // targetSdk 34 rejects a context-registered receiver with no export flag, and the
        // flag has to be EXPORTED: this broadcast comes from com.android.bluetooth, and
        // NOT_EXPORTED limits delivery to broadcasts this app sends itself, so the receiver
        // never fired and the link state only ever updated on the refresh() poll. It is a
        // protected broadcast, so only the system can send it either way.
        runCatching {
            ContextCompat.registerReceiver(
                app,
                hfpReceiver,
                IntentFilter(ACTION_HFP_CLIENT_CONNECTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "HFP state receiver not registered", it) }

        // Two possible sources for one call, in priority order.
        //
        // Telecom is preferred where it works, but on this board no InCallService is ever
        // bound (the DIALER role needs telephony hardware the Pi does not have), so
        // InCallBridge stays empty forever and the hands-free link is the only source that
        // ever reports anything. Keeping both means this still does the right thing on an
        // image that does have telephony, without a build flag deciding it.
        combine(
            InCallBridge.call,
            InCallBridge.revision,
            InCallBridge.muted,
            hfp.call,
            _micMuted,
        ) { call, _, telecomMuted, hfpCall, micMuted ->
            // Nothing read here is trusted. A projection that throws would cancel this flow
            // and, on Main.immediate, surface as an uncaught exception on the main thread —
            // one bad call object would end both this call and every call after it.
            runCatching {
                project(call, telecomMuted) ?: hfpCall?.let { named(it).copy(muted = micMuted) }
            }.getOrElse {
                Log.e(TAG, "could not read the live call", it)
                null
            }
        }.onEach { live ->
            runCatching { updateDucking(live != null) }
                .onFailure { Log.w(TAG, "ducking not applied", it) }
            // Never leave the driver's microphone muted after the call it belonged to.
            if (live == null) clearMicMute()
            _call.value = live
        }.catch {
            Log.e(TAG, "call flow stopped", it)
        }.launchIn(scope)

        // Ringing is driven from here rather than from the in-call screen, for the reason
        // the fault tones are driven from the Activity: a composable only collects while it
        // is composed, and a call has to be audible whichever tab the driver is looking at
        // — including none, with the screen asleep.
        combine(_call, hfp.audioRouted) { live, inBandAudio ->
            ringtone.update(live, inBandAudio)
        }.catch {
            Log.w(TAG, "ringtone not updated", it)
        }.launchIn(scope)

        refresh()
    }

    // --- commands ----------------------------------------------------------

    override fun dial(number: String, displayName: String?) {
        val tm = telecom ?: return
        val uri = Uri.fromParts("tel", number.filter { it.isDigit() || it == '+' || it == '#' || it == '*' }, null)
        val extras = Bundle().apply {
            hfpAccount()?.let { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
        }
        runCatching { tm.placeCall(uri, extras) }
            .onFailure { Log.e(TAG, "placeCall failed for $displayName", it) }
    }

    /**
     * Put a name on a hands-free call.
     *
     * HFP carries the number, never the name, so the caller would otherwise show as digits
     * even for someone in the synced phonebook. Same resolution the Telecom path uses.
     */
    private fun named(call: ActiveCall): ActiveCall {
        if (call.name != null || call.number.isBlank()) return call
        val known = numberIndex[numberKey(call.number)]
            ?: _contacts.value.firstOrNull { sameNumber(it.number, call.number) }?.name
        if (known == null) resolveNameLater(call.number)
        return if (known != null) call.copy(name = known) else call
    }

    override fun answer() {
        val telecomCall = InCallBridge.call.value
        if (telecomCall != null) {
            runCatching { telecomCall.answer(VideoProfile.STATE_AUDIO_ONLY) }
                .onFailure { Log.e(TAG, "answer failed", it) }
            return
        }
        hfp.answer()
    }

    override fun hangUp() {
        val telecomCall = InCallBridge.call.value
        if (telecomCall != null) {
            runCatching { telecomCall.disconnect() }
                .onFailure { Log.e(TAG, "disconnect failed", it) }
            return
        }
        hfp.hangUp()
    }

    /**
     * Telecom owns mute when it owns the call. On the hands-free path the far end never hears
     * a microphone we have switched off locally, so muting ours is the whole of it — and it is
     * the only thing that works on a board where no InCallService is ever bound.
     */
    override fun setMuted(muted: Boolean) {
        if (InCallBridge.service != null) {
            InCallBridge.setMuted(muted)
            return
        }
        val am = audioManager ?: return
        runCatching { am.isMicrophoneMute = muted }
            .onFailure { Log.w(TAG, "microphone mute refused", it) }
        _micMuted.value = runCatching { am.isMicrophoneMute }.getOrDefault(muted)
    }

    private fun clearMicMute() {
        if (!_micMuted.value) return
        runCatching { audioManager?.isMicrophoneMute = false }
            .onFailure { Log.w(TAG, "microphone left muted", it) }
        _micMuted.value = false
    }

    override fun setOnHold(hold: Boolean) {
        val call = InCallBridge.call.value
        if (call != null) {
            runCatching { if (hold) call.hold() else call.unhold() }
                .onFailure { Log.e(TAG, "hold toggle failed", it) }
            return
        }
        hfp.setOnHold(hold)
    }

    override fun sendDtmf(digit: Char) {
        val call = InCallBridge.call.value
        if (call != null) {
            runCatching {
                call.playDtmfTone(digit)
                call.stopDtmfTone()
            }.onFailure { Log.e(TAG, "dtmf failed", it) }
            return
        }
        hfp.sendDtmf(digit)
    }

    override fun refresh() {
        scope.launch {
            applyLink(profileState(), connectedDevice())
            val loaded = withContext(Dispatchers.IO) { queryContacts() to queryRecents() }
            _contacts.value = loaded.first
            _recents.value = loaded.second
        }
    }

    // --- platform → domain -------------------------------------------------

    private fun project(call: Call?, muted: Boolean): ActiveCall? {
        if (call == null) {
            answeredAtElapsedMs = 0L
            return null
        }
        @Suppress("DEPRECATION") val raw = call.state
        val state = when (raw) {
            Call.STATE_CONNECTING, Call.STATE_DIALING -> CallState.DIALING
            Call.STATE_RINGING -> CallState.RINGING
            Call.STATE_ACTIVE -> CallState.ACTIVE
            Call.STATE_HOLDING -> CallState.HOLDING
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> CallState.ENDING
            else -> return null
        }
        if (state == CallState.ACTIVE && answeredAtElapsedMs == 0L) {
            answeredAtElapsedMs = SystemClock.elapsedRealtime()
        }

        val details = call.details
        val number = details?.handle?.schemeSpecificPart.orEmpty()
        // In order of trust: what the phone itself said, then every number we have synced,
        // then the visible list. HFP rarely sends a display name, so in practice the index
        // is what puts a name on the in-call screen.
        val name = details?.callerDisplayName?.takeIf { it.isNotBlank() }
            ?: numberIndex[numberKey(number)]
            ?: _contacts.value.firstOrNull { sameNumber(it.number, number) }?.name
        // Nothing matched: ask the provider directly. Off the main thread, so the result
        // arrives later and re-emits — see resolveNameLater.
        if (name == null) resolveNameLater(number)

        return ActiveCall(
            number = number,
            name = name,
            state = state,
            direction = if (raw == Call.STATE_RINGING) CallDirection.INCOMING else CallDirection.OUTGOING,
            startedAtElapsedMs = answeredAtElapsedMs,
            muted = muted,
        )
    }

    /**
     * Ducks (not pauses) whatever media is playing for the life of a call — from the
     * first ring through hang-up — by holding transient "may duck" focus. Other apps
     * decide for themselves whether to lower volume or pause outright; most media apps
     * duck on this gain type rather than stop.
     */
    private fun updateDucking(callActive: Boolean) {
        val am = audioManager ?: return
        if (callActive) {
            if (duckingFocus != null) return
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            val result = runCatching { am.requestAudioFocus(request) }
                .onFailure { Log.w(TAG, "audio focus request failed", it) }
                .getOrNull()
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) duckingFocus = request
        } else {
            duckingFocus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            duckingFocus = null
        }
    }

    private fun applyLink(state: Int, device: BluetoothDevice?) {
        _link.value = when (state) {
            BluetoothProfile.STATE_CONNECTED -> PhoneLink.CONNECTED
            BluetoothProfile.STATE_CONNECTING -> PhoneLink.CONNECTING
            else -> PhoneLink.DISCONNECTED
        }
        _deviceName.value = if (_link.value == PhoneLink.CONNECTED) {
            runCatching { device?.name }.getOrNull() ?: "Phone"
        } else {
            null
        }
    }

    /** HEADSET_CLIENT is not in the public SDK constant set, so the profile id is literal. */
    private fun profileState(): Int =
        runCatching { adapter?.getProfileConnectionState(PROFILE_HEADSET_CLIENT) }
            .getOrNull() ?: BluetoothProfile.STATE_DISCONNECTED

    private fun connectedDevice(): BluetoothDevice? =
        runCatching { adapter?.bondedDevices?.firstOrNull() }.getOrNull()

    /** Prefer the Bluetooth HFP account; a SIM account would place the call on the head unit. */
    private fun hfpAccount() = runCatching {
        telecom?.callCapablePhoneAccounts?.firstOrNull {
            it.componentName.className.contains("HfpClient", ignoreCase = true)
        }
    }.getOrNull()

    // --- providers ---------------------------------------------------------

    private fun queryContacts(): List<Contact> = runCatching {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
        )
        val out = LinkedHashMap<Long, Contact>()
        app.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val number = c.getString(2) ?: continue
                // Every number goes in the lookup index, including the second and third
                // one a person has — the caller is whichever of them rang.
                numberIndex[numberKey(number)] = name
                // ...but the visible list keeps one row per person, both because that is
                // the useful thing to scroll and because Contact.id keys the list: three
                // rows sharing an id would collide.
                if (out.containsKey(id)) continue
                out[id] = Contact(id, name, number, favorite = c.getInt(3) == 1)
            }
        }
        out.values.toList()
    }.getOrElse {
        Log.w(TAG, "contacts unavailable (permission or no PBAP sync)", it)
        emptyList()
    }

    private fun queryRecents(): List<CallLogEntry> = runCatching {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
        )
        val out = mutableListOf<CallLogEntry>()
        // The row cap goes in the URI, not the sort order. CallLogProvider validates the
        // sort order and rejects the LIMIT token outright --
        //   IllegalArgumentException: Invalid token LIMIT
        //     at CallLogProvider.queryInternal
        // -- so "DATE DESC LIMIT 40" threw on every refresh. runCatching below swallowed
        // it, and Recents showed "No calls yet." permanently, even with a synced log.
        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "40")
            .build()
        app.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(2).orEmpty()
                out += CallLogEntry(
                    id = c.getLong(0),
                    // PBAP syncs the log with CACHED_NAME empty -- every row arrives as
                    // name=NULL even for people who are in the phonebook -- so Recents
                    // listed bare numbers. Resolve against the same index the in-call
                    // screen uses. queryContacts() runs before this and fills it.
                    name = c.getString(1)?.takeIf { it.isNotBlank() }
                        ?: numberIndex[numberKey(number)],
                    number = number,
                    direction = when (c.getInt(3)) {
                        CallLog.Calls.INCOMING_TYPE -> CallDirection.INCOMING
                        CallLog.Calls.MISSED_TYPE -> CallDirection.MISSED
                        else -> CallDirection.OUTGOING
                    },
                    timestampMillis = c.getLong(4),
                )
            }
        }
        out
    }.getOrElse {
        Log.w(TAG, "call log unavailable (permission or no PBAP sync)", it)
        emptyList()
    }

    /**
     * Resolve a number the synced phonebook did not cover, then re-emit the call with it.
     *
     * [PhoneLookup] is the provider's own reverse index: it normalises numbers properly
     * (country codes, punctuation, short codes) rather than comparing trailing digits the
     * way [sameNumber] has to, and it searches every number a contact owns. It is a
     * database query though, so it cannot run inside [project] — that is called on the
     * main thread from a flow, and the in-call screen must appear the instant the phone
     * rings, not after a query. The name lands a moment later and the card updates.
     */
    private fun resolveNameLater(number: String) {
        if (number.isBlank() || numberIndex.containsKey(numberKey(number))) return
        scope.launch {
            val resolved = withContext(Dispatchers.IO) { phoneLookup(number) } ?: return@launch
            numberIndex[numberKey(number)] = resolved
            // Only touch the call still on screen; by now it may have ended or moved on.
            _call.value = _call.value?.takeIf { it.number == number }?.copy(name = resolved)
                ?: _call.value
        }
    }

    private fun phoneLookup(number: String): String? = runCatching {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        app.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrElse {
        Log.w(TAG, "PhoneLookup unavailable", it)
        null
    }

    /** Trailing digits only, so +20 100 123 4567 and 01001234567 are the same person. */
    private fun numberKey(raw: String): String = raw.filter(Char::isDigit).takeLast(9)

    private fun sameNumber(a: String, b: String): Boolean = numberKey(a) == numberKey(b)

    private companion object {
        const val TAG = "MotorGuardPhone"
        const val PROFILE_HEADSET_CLIENT = 16
        const val ACTION_HFP_CLIENT_CONNECTION_STATE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
    }
}
