package com.ternparagliding.mezulla.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ternparagliding.mezulla.pairing.PairingState
import com.ternparagliding.mezulla.pairing.TernPairLink

/**
 * Pair-time overlay. Shows progress + a transparent disclosure of how
 * Mezulla actually secures the link with the board.
 *
 * Why this disclosure exists: Mezulla's BLE link is intentionally
 * unencrypted — see `docs/architecture/mezulla-wire-contract.md`.
 * Authentication is the QR token at the application layer, not BLE
 * bonding. We tell the pilot up-front because:
 *   1. They might wonder why no system pair dialog appears.
 *   2. They deserve to know that position broadcasts go plaintext on
 *      the 10-m BLE link (same data is already on LoRa, so it's not
 *      new exposure — but it's honest to surface it).
 *   3. They can decide whether the trade-off (zero-tap pair, no
 *      Android dialog) is acceptable for their threat model.
 */
@Composable
fun PairingPrimingScreen(
    state: PairingState,
    link: TernPairLink?,
    modifier: Modifier = Modifier,
) {
    val nodeHex = link?.nodeIdHex

    val (title, body) = when (state) {
        is PairingState.Idle, is PairingState.Received -> "Connecting…" to "Reaching the Mezulla board over Bluetooth."
        is PairingState.Scanning -> "Finding your board…" to "Looking for Mezulla nearby."
        is PairingState.Connecting -> "Connecting to your board…" to "Establishing the Bluetooth link."
        is PairingState.Claiming -> "Claiming the board…" to "Sending your QR token to confirm ownership."
        is PairingState.EstablishingLink -> "Almost ready…" to "Waiting for the board to finish handshaking."
        is PairingState.Success -> "Paired ✓" to "Your phone and Mezulla are married."
        is PairingState.Failed -> "Pairing failed" to state.reason
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xE5000000))
            .padding(32.dp)
            .testTag("pairing_priming_screen"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = body,
                color = Color(0xFFCCCCCC),
                fontSize = 14.sp,
            )

            if (state !is PairingState.Success && state !is PairingState.Failed) {
                CircularProgressIndicator(color = Color(0xFF4CAF50))
            }

            // Transparency block: how we actually secure the link.
            // Always shown during pair (not just first time) so the pilot
            // doesn't need to remember the trade-off from a one-time
            // onboarding screen.
            if (state !is PairingState.Failed) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A2E), RoundedCornerShape(12.dp))
                        .padding(20.dp)
                        .testTag("pairing_priming_security_box"),
                ) {
                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "How Mezulla secures the link",
                            color = Color(0xFF4CAF50),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Authentication: your QR token. The board only accepts " +
                                "commands from the phone that scanned its current QR. " +
                                "Encryption: none on the Bluetooth link — position " +
                                "broadcasts are visible to a BLE sniffer within ~10 m. " +
                                "The same data is already on LoRa, so this is not new " +
                                "exposure.",
                            color = Color(0xFFCCCCCC),
                            fontSize = 11.sp,
                        )
                        Text(
                            "No system pair dialog will appear. This is intentional.",
                            color = Color(0xFFAAAAAA),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            if (nodeHex != null) {
                Text(
                    text = "node !$nodeHex",
                    color = Color(0xFF666666),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
