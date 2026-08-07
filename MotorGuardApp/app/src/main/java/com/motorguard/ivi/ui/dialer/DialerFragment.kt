package com.motorguard.ivi.ui.dialer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.motorguard.ivi.data.PhoneRepository
import com.motorguard.ivi.ui.theme.MotorGuardTheme

/**
 * Owner F. Phone tab — dialpad, favourites/recents/contacts, and the in-call surface.
 * Bluetooth HFP: the head unit is the hands-free device, the paired handset places the
 * call. See docs/08-dialer.md.
 *
 * The ViewModel is scoped to the activity, not the fragment, because the rail swaps
 * fragments with `replace()` and a half-dialled number must survive a detour to Media.
 */
class DialerFragment : Fragment() {

    private val vm: DialerViewModel by activityViewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.repo.refresh() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme { DialerScreen(vm) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!PhoneRepository.USE_MOCK) requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        vm.repo.refresh()
    }

    /**
     * Only asked for on the real backend. On the mock there is nothing to permit, and a
     * permission dialog on a launcher that boots straight into HOME is a bad first frame.
     */
    private fun requestMissingPermissions() {
        val needed = buildList {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }
}
