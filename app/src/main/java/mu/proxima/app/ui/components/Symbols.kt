package mu.proxima.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import mu.proxima.app.R
import mu.proxima.app.ui.theme.sizes

/**
 * Material Symbols, Rounded family (weight 400, fill 0), vendored from google/material-design-icons (Apache 2.0)
 * as res/drawable/ic_*.xml. One family for the whole app (DESIGN_RULES.md).
 */
enum class Sym(@DrawableRes val res: Int) {
    Back(R.drawable.ic_arrow_back),
    Sos(R.drawable.ic_sos),
    Medical(R.drawable.ic_medical_services),
    Hazard(R.drawable.ic_warning),
    LostPerson(R.drawable.ic_person_search),
    Person(R.drawable.ic_person),
    Done(R.drawable.ic_check_circle),
    Error(R.drawable.ic_error),
    Mic(R.drawable.ic_mic),
    Keyboard(R.drawable.ic_keyboard),
    Mesh(R.drawable.ic_hub),
    Police(R.drawable.ic_local_police),
    Hospital(R.drawable.ic_local_hospital),
    Fire(R.drawable.ic_local_fire_department),
    Direction(R.drawable.ic_navigation),
    Close(R.drawable.ic_close),
    Reports(R.drawable.ic_groups),
    Verified(R.drawable.ic_verified),
    ReadAloud(R.drawable.ic_volume_up),
    Location(R.drawable.ic_location_on),
    Settings(R.drawable.ic_settings),
    Way(R.drawable.ic_near_me),
    Signal(R.drawable.ic_cell_tower),
    Time(R.drawable.ic_schedule),
}

/** [description] = null only when a parent element already carries the label (decorative). */
@Composable
fun SymbolIcon(
    sym: Sym,
    description: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = MaterialTheme.sizes.icon,
) {
    Icon(painterResource(sym.res), contentDescription = description, tint = tint, modifier = modifier.size(size))
}
