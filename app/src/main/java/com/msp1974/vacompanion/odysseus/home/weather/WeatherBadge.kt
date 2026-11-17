package com.msp1974.vacompanion.odysseus.home.weather

// Odysseus fork: the weather badge in the clock page's top left: Home Assistant's icon for the
// condition, the temperature beside it, and today's high and low small under the temperature.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.R
import kotlin.math.roundToInt

private val ICON_SIZE = 48.dp

@Composable
fun WeatherBadge(weather: WeatherNow, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(drawableFor(WeatherForecasts.icon(weather.condition, weather.isNight))),
            contentDescription = weather.condition,
            tint = colors.onBackground,
            modifier = Modifier.size(ICON_SIZE),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            weather.temperature?.let {
                Text(
                    text = degrees(it),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Light,
                    color = colors.onBackground,
                    maxLines = 1,
                )
            }
            val highLow = listOfNotNull(
                weather.high?.let { "H ${degrees(it)}" },
                weather.low?.let { "L ${degrees(it)}" },
            ).joinToString("  ")
            if (highLow.isNotEmpty()) {
                Text(
                    text = highLow,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Whole degrees; the unit letter is left out, as on most clocks. */
private fun degrees(value: Double) = "${value.roundToInt()}°"

internal fun drawableFor(icon: WeatherIcon): Int = when (icon) {
    WeatherIcon.SUNNY -> R.drawable.odysseus_mdi_weather_sunny
    WeatherIcon.NIGHT -> R.drawable.odysseus_mdi_weather_night
    WeatherIcon.PARTLY_CLOUDY -> R.drawable.odysseus_mdi_weather_partly_cloudy
    WeatherIcon.NIGHT_PARTLY_CLOUDY -> R.drawable.odysseus_mdi_weather_night_partly_cloudy
    WeatherIcon.CLOUDY -> R.drawable.odysseus_mdi_weather_cloudy
    WeatherIcon.RAINY -> R.drawable.odysseus_mdi_weather_rainy
    WeatherIcon.POURING -> R.drawable.odysseus_mdi_weather_pouring
    WeatherIcon.LIGHTNING -> R.drawable.odysseus_mdi_weather_lightning
    WeatherIcon.LIGHTNING_RAINY -> R.drawable.odysseus_mdi_weather_lightning_rainy
    WeatherIcon.SNOWY -> R.drawable.odysseus_mdi_weather_snowy
    WeatherIcon.SNOWY_RAINY -> R.drawable.odysseus_mdi_weather_snowy_rainy
    WeatherIcon.HAIL -> R.drawable.odysseus_mdi_weather_hail
    WeatherIcon.FOG -> R.drawable.odysseus_mdi_weather_fog
    WeatherIcon.WINDY -> R.drawable.odysseus_mdi_weather_windy
    WeatherIcon.WINDY_VARIANT -> R.drawable.odysseus_mdi_weather_windy_variant
    WeatherIcon.EXCEPTIONAL -> R.drawable.odysseus_mdi_alert_circle_outline
}
