package com.ternparagliding.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.mezulla.pairing.TeamLink
import com.ternparagliding.network.HttpClientProvider
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.spedmo.SpedmoApi
import com.ternparagliding.spedmo.SpedmoCredentials
import kotlinx.coroutines.launch

/**
 * Settings → "Team from your Spedmo club" (Epic 03 3.9). Loads the linked member's clubs and lets
 * the pilot adopt one as their Mezulla team: the club's channel name + PSK become the board's LoRa
 * channel (via the existing [MapAction.SetTeam] → set_channel apply path), so the buddy mesh shows
 * exactly that club. Hidden until the build is configured *and* an account is linked.
 */
@Composable
fun SpedmoTeamSection(store: MapStore) {
    if (!SpedmoCredentials.isConfigured) return
    val ctx = LocalContext.current
    if (!SpedmoCredentials.isLinked(ctx)) return

    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var clubs by remember { mutableStateOf<List<SpedmoApi.SpedmoClub>?>(null) }
    val activeTeam = store.state.value.settingsState.let { if (it.teamSource == "spedmo-club") it.teamName else null }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Team from your Spedmo club", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "Your buddy mesh becomes whoever's in the club.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        OutlinedButton(
            onClick = {
                scope.launch {
                    loading = true; error = null
                    val key = SpedmoCredentials.accessKey(ctx)
                    if (key == null) { error = "Not linked"; loading = false; return@launch }
                    val api = SpedmoApi(HttpClientProvider.getInstance(ctx), SpedmoCredentials.baseUrl, SpedmoCredentials.partnerApiKey)
                    when (val r = api.listClubs(key)) {
                        is SpedmoApi.ClubsResult.Ok -> clubs = r.clubs
                        is SpedmoApi.ClubsResult.AuthError -> error = "Spedmo rejected the request (HTTP ${r.code})"
                        is SpedmoApi.ClubsResult.Transient -> error = "Couldn't load clubs — ${r.detail}"
                    }
                    loading = false
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(44.dp).testTag("btn_spedmo_load_clubs"),
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.height(18.dp))
            else Text(if (clubs == null) "Load my clubs" else "Reload clubs", fontSize = 15.sp)
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }

        clubs?.let { list ->
            if (list.isEmpty()) {
                Text("No clubs on your Spedmo account.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            list.forEach { club ->
                val teamName = club.channelName ?: club.name
                val isActive = activeTeam != null && activeTeam == teamName
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(club.name ?: teamName ?: "Club ${club.id}", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (isActive) {
                        Text("Active team ✓", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    } else {
                        val team = remember(club) {
                            val name = teamName
                            val psk = club.psk
                            if (name != null && psk != null) TeamLink.fromHex(name, psk) else null
                        }
                        TextButton(
                            enabled = team != null,
                            onClick = { team?.let { store.dispatch(MapAction.SetTeam(it.name, TeamLink.encode(it), "spedmo-club")) } },
                            modifier = Modifier.testTag("btn_use_club_${club.id}"),
                        ) { Text("Use as team", fontSize = 14.sp) }
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    }
}
