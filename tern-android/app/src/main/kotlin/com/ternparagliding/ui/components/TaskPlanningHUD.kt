package com.ternparagliding.ui.components
import com.ternparagliding.model.LocationType

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ternparagliding.model.Task
import com.ternparagliding.redux.MapState
import com.ternparagliding.redux.MapStore
import com.ternparagliding.redux.WeatherActions
import com.ternparagliding.utils.io.WeatherData
import com.ternparagliding.utils.io.WeatherForecast
import com.ternparagliding.weather.Flyability
import com.ternparagliding.weather.FlyabilityLimits
import com.ternparagliding.weather.FlyingQuality
import com.ternparagliding.weather.ThermalQuality
import com.ternparagliding.weather.Verdict
import com.ternparagliding.weather.assessFlyability
import com.ternparagliding.weather.assessQuality
import com.ternparagliding.weather.assessTaskFlightRisk
import com.ternparagliding.weather.cloudBaseMeters
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import kotlin.math.abs
import kotlin.math.roundToInt

// Verdict palette — green / amber / red, readable on the dark panel.
private val GO_GREEN = Color(0xFF00C853)
private val CAUTION_AMBER = Color(0xFFFFB300)
private val NOGO_RED = Color(0xFFE53935)

private fun verdictColor(v: Verdict) = when (v) {
    Verdict.GO -> GO_GREEN
    Verdict.CAUTION -> CAUTION_AMBER
    Verdict.NO_GO -> NOGO_RED
}

/** Glyph for a flight-risk factor label, matched to the old synthesis iconography. */
private fun factorGlyph(label: String) = when (label) {
    "AIRSPACE" -> "🛑"
    "THUNDERSTORM", "CONVECTION" -> "⚡"
    "VISIBILITY" -> "👁"
    "PRECIP" -> "🌧"
    "CLOUDBASE" -> "☁"
    "DAYLIGHT" -> "🌙"
    "TERRAIN" -> "⛰"
    else -> "💨" // wind / gusts / gust factor / shear / direction
}

private fun thermalBars(t: ThermalQuality) = when (t) {
    ThermalQuality.STRONG -> "●●●"
    ThermalQuality.WORKABLE -> "●●○"
    ThermalQuality.WEAK -> "●○○"
    ThermalQuality.NONE -> "○○○"
}

/**
 * **Trajectory weather** — the 4D flyability read *along the task*. For each waypoint
 * we sample the forecast at the pilot's **ETA** (not "now") and run it through the same
 * [assessFlyability] / [assessQuality] engine the site weather sheet uses, so the read
 * folds in 80 m wind, low-level shear, gust factor, CAPE, lightning, precip, visibility
 * (flyability) plus lapse-rate thermals + cloudbase + inversion (quality).
 *
 * Shows a task **synthesis** (overall verdict + the deciding factors: strongest wind,
 * lowest cloudbase, convective onset — each with where/when) over a compact per-point
 * timeline. This is the value the per-point tiles can't give: a time-aware go/no-go.
 *
 * Collapsible, default collapsed; storm risk still also surfaces via the panel header.
 */
@Composable
fun TaskTrajectoryWeather(
    state: MapState,
    task: Task,
    store: MapStore,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val weathers = state.weatherState.waypointWeathers
    val etas = state.weatherState.waypointEtas

    // One source of truth: the same whole-task flight-risk synthesis the headline
    // banner uses, so the drill-down breakdown can never disagree with the verdict.
    val risk = remember(task, weathers, etas, state.airspaceConflicts) {
        assessTaskFlightRisk(
            waypoints = task.waypoints,
            weathers = weathers,
            etas = etas,
            airspaceConflicts = state.airspaceConflicts,
        )
    }
    val points = risk.points
    val anyWeather = risk.anyData
    val worst = risk.verdict
    // Quality breadth (informational, not safety-gating): lowest cloudbase + thermal top.
    val lowestBase = points.filter { it.quality != null }.minByOrNull { it.quality!!.cloudBaseM }
    val lowestTop = points.filter { it.quality?.thermalTopAglM != null }
        .minByOrNull { it.quality!!.thermalTopAglM!! }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp)
                .testTag("TrajectoryWeatherHeader"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Trajectory weather",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Collapsed glance: the overall verdict as a colour dot.
            if (!expanded && anyWeather) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(10.dp)
                        .background(verdictColor(worst), androidx.compose.foundation.shape.CircleShape),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!anyWeather) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Forecast not loaded yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { store.dispatch(WeatherActions.FetchWeatherForTask(task.id)) }) {
                            Text("Fetch", fontWeight = FontWeight.Bold)
                        }
                    }
                    return@Column
                }

                // ── Synthesis: verdict pill + every contributing factor ─────────
                val pillText = when (worst) {
                    Verdict.GO -> "GO"
                    Verdict.CAUTION -> "CAUTION"
                    Verdict.NO_GO -> "NO-GO"
                }
                val pillDetail = risk.headline?.takeIf { worst != Verdict.GO }?.let { f ->
                    f.detail + (f.where?.let { " · $it" } ?: "")
                }
                Surface(
                    color = verdictColor(worst),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(vertical = 6.dp).testTag("TrajectoryVerdict"),
                ) {
                    Text(
                        text = pillText + (pillDetail?.let { " · $it" } ?: ""),
                        color = Color.Black,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }

                // Every safety factor that contributes to the verdict — the breadth the
                // pilot decides on (one line per factor, worst-first).
                risk.factors.forEach { f ->
                    SynthRow(
                        factorGlyph(f.label), f.label, f.detail, f.where,
                        if (f.verdict == Verdict.NO_GO) NOGO_RED
                        else if (f.verdict == Verdict.CAUTION) CAUTION_AMBER else null,
                    )
                }
                if (risk.factors.isEmpty()) {
                    SynthRow("☀", "CONDITIONS", "no limiting factors on the task", null, null)
                }

                // Quality breadth (informational, not safety-gating).
                lowestBase?.quality?.let { q ->
                    val low = q.cloudBaseM < 400.0
                    SynthRow(
                        "☁", "LOWEST CLOUDBASE",
                        "+${q.cloudBaseM.roundToInt()} m AGL" + if (low) " — tight" else "",
                        listOfNotNull(lowestBase.name, lowestBase.etaLabel).joinToString(", "),
                        if (low) CAUTION_AMBER else null,
                    )
                }
                lowestTop?.quality?.thermalTopAglM?.let { top ->
                    val weak = top < 600.0
                    SynthRow(
                        "⤒", "LOWEST THERMAL TOP",
                        "${top.roundToInt()} m AGL" + if (weak) " — weak climbs" else "",
                        listOfNotNull(lowestTop.name, lowestTop.etaLabel).joinToString(", "),
                        if (weak) CAUTION_AMBER else null,
                    )
                }

                if (!risk.dataComplete) {
                    SynthRow("ⓘ", "DATA", "forecast missing for some waypoints", null, null)
                }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // ── Per-point timeline (forecast at each ETA) ───────────────────
                points.forEachIndexed { i, p ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    p.flyability?.let { verdictColor(it.verdict) } ?: MaterialTheme.colorScheme.outline,
                                    androidx.compose.foundation.shape.CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${p.seq}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(16.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (p.weather != null) buildString {
                                    append("${p.weather.wind.speed.roundToInt()} kt @ ${p.weather.wind.direction.roundToInt()}°")
                                    p.quality?.let { append(" · ☁ +${it.cloudBaseM.roundToInt()} m") }
                                    p.quality?.thermalTopAglM?.let { append(" · ⤒ ${it.roundToInt()} m") }
                                    p.quality?.let { append(" · ${thermalBars(it.thermal)}") }
                                } else "—",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        p.etaLabel?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    if (i < points.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    }
                }
            }
        }
    }
}

/** One synthesis line: glyph · LABEL · value (+ optional where/when), value tinted by severity. */
@Composable
private fun SynthRow(glyph: String, label: String, value: String, where: String?, tint: Color?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = tint ?: MaterialTheme.colorScheme.onSurface,
                )
                if (where != null) {
                    Text(
                        "  · $where",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

