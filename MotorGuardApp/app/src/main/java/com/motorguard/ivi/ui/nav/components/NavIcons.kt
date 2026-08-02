package com.motorguard.ivi.ui.nav.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RampLeft
import androidx.compose.material.icons.filled.RampRight
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.ui.graphics.vector.ImageVector
import com.motorguard.ivi.data.nav.Maneuver
import com.motorguard.ivi.data.nav.PlaceCategory

/**
 * The one maneuver → glyph table in the app. The design system draws these from Material
 * Symbols Rounded (`turn_right`, `merge`, …); the Compose equivalents below are the same
 * shapes from `material-icons-extended`.
 */
fun maneuverIcon(maneuver: Maneuver): ImageVector = when (maneuver) {
    Maneuver.DEPART -> Icons.Filled.NearMe
    Maneuver.CONTINUE -> Icons.Filled.Straight
    Maneuver.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft
    Maneuver.LEFT -> Icons.Filled.TurnLeft
    Maneuver.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    Maneuver.UTURN_LEFT -> Icons.Filled.UTurnLeft
    Maneuver.UTURN_RIGHT -> Icons.Filled.UTurnRight
    Maneuver.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    Maneuver.RIGHT -> Icons.Filled.TurnRight
    Maneuver.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
    Maneuver.RAMP_LEFT -> Icons.Filled.RampLeft
    Maneuver.RAMP_RIGHT -> Icons.Filled.RampRight
    Maneuver.RAMP_STRAIGHT -> Icons.Filled.Straight
    Maneuver.EXIT_LEFT -> Icons.Filled.ForkLeft
    Maneuver.EXIT_RIGHT -> Icons.Filled.ForkRight
    Maneuver.MERGE -> Icons.Filled.Merge
    Maneuver.ROUNDABOUT_ENTER -> Icons.Filled.RoundaboutRight
    Maneuver.ROUNDABOUT_EXIT -> Icons.Filled.RoundaboutLeft
    Maneuver.FERRY -> Icons.Filled.DirectionsBoat
    Maneuver.ARRIVE -> Icons.Filled.Flag
}

/** Search-result row glyph. Charger first — this is an EV. */
fun placeIcon(category: PlaceCategory): ImageVector = when (category) {
    PlaceCategory.CHARGER -> Icons.Filled.EvStation
    PlaceCategory.FUEL -> Icons.Filled.LocalGasStation
    PlaceCategory.FOOD -> Icons.Filled.LocalCafe
    PlaceCategory.PARKING -> Icons.Filled.LocalParking
    PlaceCategory.SHOP -> Icons.Filled.Storefront
    PlaceCategory.TRANSPORT -> Icons.Filled.DirectionsTransit
    PlaceCategory.CITY -> Icons.Filled.LocationCity
    PlaceCategory.ADDRESS -> Icons.Filled.Place
    PlaceCategory.OTHER -> Icons.Filled.Place
}
