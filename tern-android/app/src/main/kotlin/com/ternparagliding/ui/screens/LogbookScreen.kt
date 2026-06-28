package com.ternparagliding.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ternparagliding.flight.export.IgcExporter
import com.ternparagliding.spedmo.SpedmoCredentials
import com.ternparagliding.spedmo.SpedmoUploadQueue
import com.ternparagliding.spedmo.SpedmoUploader
import com.ternparagliding.flight.recording.FlightRecording
import com.ternparagliding.flight.recording.FlightStore
import com.ternparagliding.flight.recording.FlightSummary
import com.ternparagliding.flight.recording.SealReason
import com.ternparagliding.redux.MapStore
import com.ternparagliding.units.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The flight **logbook** (Epic 05 5.3): every recorded flight, newest first, with at-a-glance
 * stats derived from the recording ([FlightSummary]). Tap a flight for detail; export its track as
 * IGC (share intent) or delete it (incident-"protected" flights ask first). Reads the same
 * `filesDir/recordings` the recorder writes — files are the source of truth, so a read-only store
 * instance here is safe.
 *
 * On-map replay reuses the existing IGC replay path and lands in a later pass; this is the list +
 * detail + export surface.
 */
@Composable
fun LogbookScreen(
    modifier: Modifier = Modifier,
    store: MapStore,
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsState()
    val distanceUnit = state.settingsState.distanceUnit
    val altitudeUnit = state.settingsState.altitudeUnit

    val flightStore = remember { FlightStore(File(context.applicationContext.filesDir, "recordings")) }
    var refresh by remember { mutableIntStateOf(0) }
    val summaries by produceState(initialValue = emptyList<FlightSummary>(), refresh) {
        value = withContext(Dispatchers.IO) { runCatching { flightStore.listSummaries() }.getOrDefault(emptyList()) }
    }

    var detailId by remember { mutableStateOf<String?>(null) }
    val detail by produceState<FlightRecording?>(null, detailId) {
        value = detailId?.let { id -> withContext(Dispatchers.IO) { flightStore.load(id) } }
    }
    // Back closes the detail first, then (via the screen's own onDismiss in TernMapScreen) the screen.
    BackHandler(enabled = detailId != null) { detailId = null }

    var pendingDelete by remember { mutableStateOf<FlightSummary?>(null) }

    fun doDelete(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { flightStore.delete(id) }
            if (detailId == id) detailId = null
            refresh++
        }
    }

    fun exportIgc(id: String) {
        scope.launch {
            val intent = withContext(Dispatchers.IO) {
                val rec = flightStore.load(id) ?: return@withContext null
                val text = IgcExporter.toIgc(rec) ?: return@withContext null
                val file = File(context.cacheDir, "tern-${id}.igc").apply { writeText(text) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            intent?.let { context.startActivity(Intent.createChooser(it, "Share flight (IGC)")) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("logbook_screen")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
    ) {
        Card(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .align(Alignment.Center)
                .clickable(enabled = false) {},
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (detailId == null) "Logbook" else "Flight",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(
                        onClick = { if (detailId != null) detailId = null else onDismiss() },
                        modifier = Modifier.testTag("btn_logbook_close"),
                    ) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }

                val current = detail
                if (detailId != null && current != null) {
                    FlightDetail(
                        recording = current,
                        distanceUnit = distanceUnit,
                        altitudeUnit = altitudeUnit,
                        onExport = { exportIgc(current.id) },
                        onDelete = {
                            val summary = summaries.find { it.id == current.id }
                            if (current.isProtected) pendingDelete = summary else doDelete(current.id)
                        },
                    )
                } else if (summaries.isEmpty()) {
                    Text(
                        text = "No recorded flights yet. Your flights are saved automatically once you launch.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.testTag("logbook_list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(summaries, key = { it.id }) { s ->
                            FlightRow(
                                summary = s,
                                distanceUnit = distanceUnit,
                                altitudeUnit = altitudeUnit,
                                onClick = { detailId = s.id },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete protected flight?") },
            text = { Text("This flight is marked protected (it holds an SOS or an incident). Delete it anyway?") },
            confirmButton = {
                TextButton(
                    onClick = { pendingDelete = null; doDelete(s.id) },
                    modifier = Modifier.testTag("btn_logbook_delete_confirm"),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun FlightRow(
    summary: FlightSummary,
    distanceUnit: String,
    altitudeUnit: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.testTag("logbook_flight_${summary.id}"),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatDate(summary.startTimeMs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (summary.isProtected) Badge("PROTECTED", MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Duration", formatDuration(summary.durationMs))
                Stat("Distance", Units.distance(summary.trackDistanceM / 1000.0, distanceUnit))
                Stat("Max alt", summary.maxAltitudeM?.let { Units.altitude(it, altitudeUnit) } ?: "—")
            }
            if (summary.peerCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${summary.peerCount} buddy${if (summary.peerCount == 1) "" else "s"} recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FlightDetail(
    recording: FlightRecording,
    distanceUnit: String,
    altitudeUnit: String,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = remember(recording) { FlightSummary.from(recording) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Text(formatDate(s.startTimeMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        DetailRow("Duration", formatDuration(s.durationMs))
        DetailRow("Track distance", Units.distance(s.trackDistanceM / 1000.0, distanceUnit))
        DetailRow("Straight distance", Units.distance(s.straightDistanceM / 1000.0, distanceUnit))
        DetailRow("Max altitude", s.maxAltitudeM?.let { Units.altitude(it, altitudeUnit) } ?: "—")
        DetailRow("Max climb", s.maxClimbMs?.let { Units.vario(it, "m/s") } ?: "—")
        DetailRow("Max sink", s.maxSinkMs?.let { Units.vario(it, "m/s") } ?: "—")
        DetailRow("Fixes", s.fixCount.toString())
        DetailRow("Buddies recorded", s.peerCount.toString())
        DetailRow("How it ended", sealLabel(s.sealReason))
        if (recording.signature != null) DetailRow("Integrity", "checksummed")

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onExport, modifier = Modifier.testTag("btn_logbook_export")) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Export IGC", fontSize = 15.sp)
            }
            TextButton(onClick = onDelete, modifier = Modifier.testTag("btn_logbook_delete")) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Text("  Delete", fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
            }
        }

        SpedmoUploadRow(flightId = recording.id)
    }
}

/**
 * Spedmo upload state for one flight (Epic 03 3.5 / 5.4). Self-hides when the build has no partner
 * key; prompts to link when configured-but-unlinked; otherwise shows the current state and offers a
 * manual upload / retry. Auto-upload (when opted in) means this usually already reads "Uploaded ✓".
 */
@Composable
private fun SpedmoUploadRow(flightId: String) {
    if (!SpedmoCredentials.isConfigured) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploader = remember { SpedmoUploader.get(context) }
    val linked = remember { SpedmoCredentials.isLinked(context) }

    var refresh by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val status by remember(flightId, refresh) { mutableStateOf(uploader.status(flightId)) }

    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

    if (!linked) {
        Text(
            "Link a Spedmo account in Settings to upload this flight.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val (label, canSend) = when (status) {
        SpedmoUploadQueue.State.UPLOADED -> "Uploaded to Spedmo ✓" to false
        SpedmoUploadQueue.State.QUEUED -> "Queued — uploads when online" to false
        SpedmoUploadQueue.State.FAILED -> "Upload failed" to true
        null -> "Not uploaded" to true
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("spedmo_upload_status"))
        if (canSend) {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    uploader.uploadNow(flightId)
                    // Drain runs on its own IO scope; poll the on-disk status briefly to reflect it.
                    scope.launch {
                        repeat(12) {
                            kotlinx.coroutines.delay(500)
                            refresh++
                            if (uploader.status(flightId) == SpedmoUploadQueue.State.UPLOADED) return@launch
                        }
                        busy = false
                    }
                },
                modifier = Modifier.testTag("btn_spedmo_upload"),
            ) { Text(if (status == SpedmoUploadQueue.State.FAILED) "Retry" else "Upload to Spedmo", fontSize = 15.sp) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onError,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun sealLabel(reason: SealReason): String = when (reason) {
    SealReason.LANDED -> "Landed"
    SealReason.MANUAL -> "Ended manually"
    SealReason.SOS -> "SOS"
    SealReason.RAPID_DESCENT -> "Incident"
    SealReason.CRASH_RECOVERED -> "Recovered after app restart"
}
