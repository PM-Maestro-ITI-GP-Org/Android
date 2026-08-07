package com.motorguard.ivi.ui.diagnostics

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.DoorSliding
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.TireRepair
import androidx.compose.ui.graphics.vector.ImageVector

/** Spot icon per diagnostics hotspot (Material Symbols set in the prototype). */
object DiagnosticsIcons {
    val Battery: ImageVector = Icons.Outlined.BatteryChargingFull
    val Tire: ImageVector = Icons.Outlined.TireRepair
    val Motor: ImageVector = Icons.Outlined.ElectricBolt
    val Brakes: ImageVector = Icons.Outlined.DoorSliding // closest material analog: caliper/disc
    val Doors: ImageVector = Icons.Outlined.DoorFront
    val Car: ImageVector = Icons.Outlined.DirectionsCar
}
