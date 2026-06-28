package com.ternparagliding.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.network.HttpClientProvider
import com.ternparagliding.spedmo.SpedmoApi
import com.ternparagliding.spedmo.SpedmoCredentials
import kotlinx.coroutines.launch

/**
 * Settings → Spedmo account (Epic 03 3.1 / 5.4). Link by pasting a member access key (validated
 * against `GET /member.api` so a bad key is caught immediately), then toggle auto-upload of
 * finalized flights. Hidden entirely when the build has no partner key — there's nothing to link to.
 *
 * Auto-upload defaults off (privacy); nothing leaves the device until linked *and* opted in. The key
 * is a per-pilot secret kept by [SpedmoCredentials] outside Redux.
 */
@Composable
fun SpedmoAccountSection() {
    if (!SpedmoCredentials.isConfigured) return // no partner key in this build → nothing to offer

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var linked by remember { mutableStateOf(SpedmoCredentials.isLinked(ctx)) }
    var autoUpload by remember { mutableStateOf(SpedmoCredentials.autoUpload(ctx)) }
    var liveTracking by remember { mutableStateOf(SpedmoCredentials.liveTracking(ctx)) }
    var keyText by remember { mutableStateOf("") }
    var validating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Spedmo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

        if (linked) {
            Text("Account linked ✓", fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Auto-upload flights after landing", fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = autoUpload,
                    onCheckedChange = { on -> autoUpload = on; SpedmoCredentials.setAutoUpload(ctx, on) },
                    modifier = Modifier.testTag("switch_spedmo_autoupload"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Share live position when I have signal", fontSize = 15.sp)
                    Text(
                        "Cellular only — your offline buddy mesh is unaffected.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = liveTracking,
                    onCheckedChange = { on -> liveTracking = on; SpedmoCredentials.setLiveTracking(ctx, on) },
                    modifier = Modifier.testTag("switch_spedmo_livetrack"),
                )
            }
            TextButton(
                onClick = { SpedmoCredentials.clear(ctx); linked = false; autoUpload = false; liveTracking = false; keyText = "" },
                modifier = Modifier.testTag("btn_spedmo_unlink"),
            ) { Text("Unlink") }
        } else {
            Text(
                "Paste your Spedmo member access key to upload flights to your logbook.",
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it; error = null },
                label = { Text("Member access key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = error != null,
                modifier = Modifier.fillMaxWidth().testTag("field_spedmo_key"),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            OutlinedButton(
                onClick = {
                    val key = keyText.trim()
                    if (key.isEmpty()) { error = "Enter your access key"; return@OutlinedButton }
                    scope.launch {
                        validating = true; error = null
                        val api = SpedmoApi(
                            HttpClientProvider.getInstance(ctx),
                            SpedmoCredentials.baseUrl,
                            SpedmoCredentials.partnerApiKey,
                        )
                        when (val r = api.getMember(key)) {
                            is SpedmoApi.Result.Ok -> { SpedmoCredentials.setAccessKey(ctx, key); linked = true }
                            is SpedmoApi.Result.AuthError -> error = "Key not accepted (HTTP ${r.code})"
                            is SpedmoApi.Result.Transient -> error = "Couldn't reach Spedmo — check your connection"
                        }
                        validating = false
                    }
                },
                enabled = !validating,
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp).testTag("btn_spedmo_link"),
            ) {
                if (validating) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                else Text("Link Spedmo account", fontSize = 16.sp)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    }
}
