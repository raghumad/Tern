package com.ternparagliding.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.mezulla.pairing.TeamLink

private val GREEN = Color(0xFF22C55E)
private val AMBER = Color(0xFFF59E0B)
private val MUTED = Color(0xFF94A3B8)

/**
 * The **Team** section in Settings → Connections — create / join / share a Mezulla team (the board's
 * PRIMARY LoRa channel). A team is *intent*, owned by the phone: it can be created, joined and
 * shared with the board paired but **offline** — the board is reconciled to it when it next
 * connects. Boards on the same team hear each other; this is how the pilot turns a pile of boards
 * into "my buddies".
 *
 *  - With a team: name + status (on-board / pending), a QR + copyable [tern://team] link to share,
 *    and Leave.
 *  - A name field to **create** a fresh team, and a paste-link field to **join** someone else's —
 *    both work offline.
 *
 * Pure presentation — all side effects (set_team is the reconcile step's job, persistence, clipboard,
 * share-sheet) are the caller's via the callbacks. [applied] = the board is confirmed on this team
 * (its link == what we last wrote); [linkUp] = the board is connected right now.
 */
@Composable
fun MezullaTeamSection(
    teamName: String?,
    teamShareLink: String?,
    applied: Boolean,
    linkUp: Boolean,
    onCreate: (String) -> Unit,
    onJoin: (link: String) -> Unit,
    onLeave: () -> Unit,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(18.dp), tint = MUTED)
            Text("Team", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
            teamName?.let {
                Text(
                    "· $it",
                    fontSize = 14.sp,
                    color = GREEN,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        // Current team → status + share. No connection gate: the link is just the team credential.
        if (teamName != null && teamShareLink != null) {
            val (statusText, statusColor) = when {
                applied -> "● On your board" to GREEN
                linkUp -> "Applying to your board…" to AMBER
                else -> "Will apply when your board connects" to AMBER
            }
            Text(statusText, fontSize = 12.sp, color = statusColor, modifier = Modifier.padding(top = 6.dp))

            Text(
                "Share this so buddies join your team:",
                fontSize = 12.sp,
                color = MUTED,
                modifier = Modifier.padding(top = 8.dp),
            )
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                QrCode(teamShareLink, modifier = Modifier.size(180.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { onCopy(teamShareLink) }, modifier = Modifier.weight(1f).testTag("btn_team_copy")) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  Copy link", fontSize = 13.sp)
                }
                OutlinedButton(onClick = { onShare(teamShareLink) }, modifier = Modifier.weight(1f).testTag("btn_team_share")) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  Share", fontSize = 13.sp)
                }
            }
            TextButton(onClick = onLeave, modifier = Modifier.testTag("btn_team_leave")) {
                Text("Leave team", color = MUTED, fontSize = 13.sp)
            }
        }

        // Create / join — always available (creating or joining offline; switching overwrites).
        CreateJoinControls(hasTeam = teamName != null, onCreate = onCreate, onJoin = onJoin)
    }
}

@Composable
private fun CreateJoinControls(
    hasTeam: Boolean,
    onCreate: (String) -> Unit,
    onJoin: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var joinLink by remember { mutableStateOf("") }

    Text(
        if (hasTeam) "Or switch to another team:" else "Create a team your buddies can join:",
        fontSize = 12.sp,
        color = MUTED,
        modifier = Modifier.padding(top = 12.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Team name") },
            modifier = Modifier.weight(1f).testTag("field_team_name"),
        )
        OutlinedButton(
            onClick = { onCreate(name.trim()); name = "" },
            enabled = name.isNotBlank(),
            modifier = Modifier.height(56.dp).testTag("btn_team_create"),
        ) { Text("Create") }
    }

    Text(
        "Joining a team? Paste the link a buddy shared:",
        fontSize = 12.sp,
        color = MUTED,
        modifier = Modifier.padding(top = 12.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = joinLink,
            onValueChange = { joinLink = it },
            singleLine = true,
            label = { Text("tern://team?…") },
            isError = joinLink.isNotBlank() && TeamLink.parse(joinLink.trim()) == null,
            modifier = Modifier.weight(1f).testTag("field_team_link"),
        )
        OutlinedButton(
            onClick = { onJoin(joinLink.trim()); joinLink = "" },
            enabled = TeamLink.parse(joinLink.trim()) != null,
            modifier = Modifier.height(56.dp).testTag("btn_team_join"),
        ) { Text("Join") }
    }
}
