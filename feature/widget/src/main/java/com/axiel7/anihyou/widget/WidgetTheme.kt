package com.axiel7.anihyou.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
// androidx.glance.color only exposes the ColorProvider(day, night) factory; the interface
// itself lives in androidx.glance.unit, hence the alias.
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import com.materialkolor.ktx.harmonize

/** How long a widget waits for its data before reporting a timeout instead of staying blank. */
const val WIDGET_LOAD_TIMEOUT_MS = 30_000L

@Composable
fun glanceStringResource(@StringRes id: Int, vararg args: Any): String {
    return LocalContext.current.getString(id, *args)
}

/**
 * Applies [action] only when it is not null. Actions built from a launch intent lookup can be
 * unavailable; an absent action leaves the view inert instead of starting an empty intent, which
 * would throw ActivityNotFoundException.
 */
fun GlanceModifier.clickableIfAvailable(action: Action?): GlanceModifier =
    if (action == null) this else this.clickable(action)

fun GlanceModifier.widgetCornerRadius(): GlanceModifier {
    val cornerRadiusModifier =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            GlanceModifier.cornerRadius(android.R.dimen.system_app_widget_background_radius)
        } else {
            GlanceModifier
        }

    return this.then(cornerRadiusModifier)
}

@Composable
fun RoundedDrawableBox(
    @DrawableRes shapeRes: Int,
    color: GlanceColorProvider,
    modifier: GlanceModifier = GlanceModifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.background(
            imageProvider = ImageProvider(shapeRes),
            colorFilter = ColorFilter.tint(color)
        ),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * Card palette shared by the widgets.
 *
 * Container and text colors have to be lightness-mapped per theme: assigning day and night
 * the same value leaves the card indistinguishable from its background in dark mode. The hue
 * is the cover color after harmonizing against the theme primary.
 */
data class WidgetCardColors(
    val container: GlanceColorProvider,
    val title: GlanceColorProvider,
    val text: GlanceColorProvider,
)

private fun Color.withLightness(lightness: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[2] = lightness.coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

@Composable
fun rememberWidgetCardColors(baseMediaColor: Color?): WidgetCardColors? {
    if (baseMediaColor == null) return null
    val primaryColor = GlanceTheme.colors.primary.getColor(LocalContext.current)
    return remember(baseMediaColor, primaryColor) {
        val base = baseMediaColor.harmonize(primaryColor)
        WidgetCardColors(
            container = ColorProvider(
                day = base.withLightness(0.70f),
                night = base.withLightness(0.30f),
            ),
            title = ColorProvider(
                day = base.withLightness(0.16f),
                night = base.withLightness(0.96f),
            ),
            text = ColorProvider(
                day = base.withLightness(0.25f),
                night = base.withLightness(0.79f),
            ),
        )
    }
}
