package com.ternparagliding.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ternparagliding.weather.Flyability
import com.ternparagliding.weather.FlyabilityOutlook
import com.ternparagliding.weather.FlyabilityReason
import com.ternparagliding.weather.FlyingQuality
import com.ternparagliding.weather.ThermalQuality
import com.ternparagliding.weather.Verdict

private fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.GO -> Color(0xFF22C55E)
    Verdict.CAUTION -> Color(0xFFF59E0B)
    Verdict.NO_GO -> Color(0xFFEF4444)
}

private fun verdictLabel(v: Verdict): String = when (v) {
    Verdict.GO -> "GO"
    Verdict.CAUTION -> "CAUTION"
    Verdict.NO_GO -> "NO-GO"
}

private fun soonLabel(atMs: Long?): String {
    if (atMs == null) return ""
    val hrs = (atMs - System.currentTimeMillis()) / 3_600_000.0
    return if (hrs < 1.0) "soon" else "in ~${hrs.toInt()}h"
}

/**
 * Glanceable "is it flyable here, now and soon" card — the universal pilot read.
 * Shows the verdict, the reasons (transparent, never a black box), the next
 * worsening, and the thermal quality. Eye-validate via the @Previews below or on
 * a real device (UI, so not claim-tested).
 */
@Composable
fun FlyabilityCard(
    outlook: FlyabilityOutlook,
    quality: FlyingQuality? = null,
    site: com.ternparagliding.weather.SiteContext? = null,
    altitudeUnit: String = "m",
    modifier: Modifier = Modifier,
) {
    val verdict = outlook.now.verdict
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(verdictColor(verdict), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        verdictLabel(verdict),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                val subtitle = when (verdict) {
                    Verdict.GO -> "flyable here, now"
                    Verdict.CAUTION -> "marginal here, now"
                    Verdict.NO_GO -> "not flyable here, now"
                }
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(10.dp))

            if (outlook.now.reasons.isEmpty()) {
                Text("• conditions look good", style = MaterialTheme.typography.bodyMedium)
            } else {
                outlook.now.reasons.forEach { reason ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(verdictColor(reason.verdict), RoundedCornerShape(4.dp))) {}
                        Spacer(Modifier.width(8.dp))
                        Text("${reason.factor}: ${reason.detail}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            outlook.deterioratesTo?.let { soon ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "→ turns ${verdictLabel(soon.verdict)} ${soonLabel(outlook.deterioratesAtMs)}",
                    color = verdictColor(soon.verdict),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            quality?.let { q ->
                Spacer(Modifier.height(8.dp))
                val lid = if (q.cappedByInversion) " (inversion lid)" else ""
                // Site-aware: when we know the launch elevation, read cloudbase as the
                // working band above launch + MSL — the height the pilot actually has.
                // Both rendered in the pilot's altitude unit.
                val band = com.ternparagliding.units.Units.altitude(q.cloudBaseM, altitudeUnit)
                val base = site?.elevationM?.let {
                    val msl = com.ternparagliding.units.Units.altitude(it + q.cloudBaseM, altitudeUnit)
                    "cloudbase ~$band above launch (~$msl MSL)"
                } ?: "cloudbase ~$band"
                Text(
                    "thermals: ${q.thermal}$lid · $base",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ── Previews — eye-validate in Android Studio (no emulator) ──────────────────

@Preview(showBackground = true)
@Composable
private fun PreviewGo() {
    FlyabilityCard(
        outlook = FlyabilityOutlook(Flyability(Verdict.GO, emptyList()), null, null),
        quality = FlyingQuality(ThermalQuality.STRONG, 8.0, 2400.0, false, emptyList()),
    )
}

@Preview(showBackground = true)
@Composable
private fun PreviewCaution() {
    FlyabilityCard(
        outlook = FlyabilityOutlook(
            Flyability(
                Verdict.CAUTION,
                listOf(
                    FlyabilityReason(Verdict.CAUTION, "wind", "18 kt — strong"),
                    FlyabilityReason(Verdict.CAUTION, "convective", "CAPE 700 — overdevelopment risk"),
                ),
            ),
            Flyability(Verdict.NO_GO, listOf(FlyabilityReason(Verdict.NO_GO, "thunderstorm", "lightning potential 80%"))),
            System.currentTimeMillis() + 3 * 3_600_000,
        ),
        quality = FlyingQuality(ThermalQuality.STRONG, 9.0, 2800.0, false, emptyList()),
    )
}

@Preview(showBackground = true)
@Composable
private fun PreviewNoGo() {
    FlyabilityCard(
        outlook = FlyabilityOutlook(
            Flyability(
                Verdict.NO_GO,
                listOf(
                    FlyabilityReason(Verdict.NO_GO, "gusts", "gusting 30 kt"),
                    FlyabilityReason(Verdict.NO_GO, "gradient", "+20 kt by 80 m — strong shear"),
                ),
            ),
            null, null,
        ),
        quality = FlyingQuality(ThermalQuality.NONE, null, 600.0, true, emptyList()),
    )
}
