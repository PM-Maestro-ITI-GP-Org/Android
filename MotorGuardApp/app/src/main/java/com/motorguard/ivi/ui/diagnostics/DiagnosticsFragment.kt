package com.motorguard.ivi.ui.diagnostics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.motorguard.ivi.data.vehicle.fake.FakeVehicleDataSource
import com.motorguard.ivi.ui.theme.MotorGuardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owner C. Interactive car: tap a component -> zoom + live state card. Bound to a
 * real VHAL in Phase 2 — for Phase 1 the fake source is held in the ServiceLocator
 * so rapid navigation never respawns telemetry coroutines.
 */
class DiagnosticsFragment : Fragment() {

    private val vm: DiagnosticsViewModel by viewModels {
        DiagnosticsViewModel.factory(
            DiagnosticsLocator.vehicleDataSource(requireContext()),
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MotorGuardTheme { DiagnosticsScreen(vm) }
        }
    }
}

/**
 * Minimal hand-rolled DI holder for the Phase-1 diagnostics stack. Kept here so the
 * fragment stays a thin shell — the real `CarDataRepository` (Phase 2) slots into
 * the same `VehicleDataSource` slot.
 */
object DiagnosticsLocator {
    @Volatile private var source: FakeVehicleDataSource? = null
    private var scope: CoroutineScope? = null

    fun vehicleDataSource(@Suppress("UNUSED_PARAMETER") context: android.content.Context): FakeVehicleDataSource =
        source ?: synchronized(this) {
            source ?: run {
                val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                scope = sc
                FakeVehicleDataSource(scope = sc).also { source = it }
            }
        }
}
