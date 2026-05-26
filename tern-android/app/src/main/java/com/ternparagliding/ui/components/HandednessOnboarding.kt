package com.ternparagliding.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ternparagliding.redux.Handedness
import com.ternparagliding.redux.MapAction
import com.ternparagliding.redux.MapStore
import com.ternparagliding.utils.HandednessDetectionService
import kotlinx.coroutines.launch

/**
 * Onboarding component for handedness detection and setup
 * Privacy-safe: only runs when explicitly requested during onboarding
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandednessOnboardingScreen(
    store: MapStore = viewModel(),
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var suggestedHandedness by remember { mutableStateOf<Handedness?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedHandedness by remember { mutableStateOf<Handedness?>(null) }

    // Detect handedness on composition (privacy-safe - only runs once)
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            try {
                val detectionService = HandednessDetectionService(context)
                val result = detectionService.detectHandednessForOnboarding()

                suggestedHandedness = result.handedness
                selectedHandedness = result.handedness // Pre-select the suggestion

                // Update Redux state with detected handedness
                store.dispatch(MapAction.SetHandedness(result.handedness))
                store.dispatch(MapAction.UpdateHandednessSource(result.source))

            } catch (e: Exception) {
                // Fallback to right-handed if detection fails
                suggestedHandedness = Handedness.RIGHT_HANDED
                selectedHandedness = Handedness.RIGHT_HANDED

                store.dispatch(MapAction.SetHandedness(Handedness.RIGHT_HANDED))
                store.dispatch(MapAction.UpdateHandednessSource(com.ternparagliding.redux.HandednessSource.SMART_DEFAULT))
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Safety-focused header
        Text(
            text = "Flight Safety Setup",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "For your safety during flight, we'll optimize control placement based on your hand preference.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (isLoading) {
            // Loading state
            CircularProgressIndicator()
            Text(
                text = "Analyzing device settings...",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            // Hand selection interface
            HandednessSelector(
                suggestedHandedness = suggestedHandedness ?: Handedness.RIGHT_HANDED,
                selectedHandedness = selectedHandedness,
                onHandednessSelected = { handedness ->
                    selectedHandedness = handedness
                    // Update Redux state immediately
                    store.dispatch(MapAction.SetHandedness(handedness))
                }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = {
                    // Use current selection or fallback to right-handed
                    val finalHandedness = selectedHandedness ?: Handedness.RIGHT_HANDED
                    store.dispatch(MapAction.SetHandedness(finalHandedness))
                    store.dispatch(MapAction.UpdateHandednessSource(com.ternparagliding.redux.HandednessSource.USER_SELECTED))
                    onComplete()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Skip Setup")
            }

            Button(
                onClick = {
                    // Use selected handedness with user source
                    val finalHandedness = selectedHandedness ?: suggestedHandedness ?: Handedness.RIGHT_HANDED
                    store.dispatch(MapAction.SetHandedness(finalHandedness))
                    store.dispatch(MapAction.UpdateHandednessSource(com.ternparagliding.redux.HandednessSource.USER_SELECTED))
                    onComplete()
                },
                enabled = selectedHandedness != null,
                modifier = Modifier.weight(1f)
            ) {
                Text("Continue")
            }
        }

        // Privacy notice
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Privacy Note: We only use your current device settings to suggest optimal defaults. No usage tracking is performed.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HandednessSelector(
    suggestedHandedness: Handedness,
    selectedHandedness: Handedness?,
    onHandednessSelected: (Handedness) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Which hand do you prefer for flight controls?",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Hand selection options
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            HandednessOption(
                handedness = Handedness.LEFT_HANDED,
                icon = "🫲",
                title = "Left Hand",
                description = "Controls on left side",
                isSuggested = suggestedHandedness == Handedness.LEFT_HANDED,
                isSelected = selectedHandedness == Handedness.LEFT_HANDED,
                onSelected = onHandednessSelected
            )

            HandednessOption(
                handedness = Handedness.RIGHT_HANDED,
                icon = "🫱",
                title = "Right Hand",
                description = "Controls on right side",
                isSuggested = suggestedHandedness == Handedness.RIGHT_HANDED,
                isSelected = selectedHandedness == Handedness.RIGHT_HANDED,
                onSelected = onHandednessSelected
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Ambidextrous option (smaller, as it's less common)
        OutlinedButton(
            onClick = { onHandednessSelected(Handedness.AMBIDEXTROUS) },
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text("🤲 Ambidextrous (Either hand)")
        }
    }
}

@Composable
private fun HandednessOption(
    handedness: Handedness,
    icon: String,
    title: String,
    description: String,
    isSuggested: Boolean,
    isSelected: Boolean,
    onSelected: (Handedness) -> Unit
) {
    Column(
        modifier = Modifier
            .height(140.dp)
            .clickable { onSelected(handedness) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    isSuggested -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surface
                }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = icon,
                    style = MaterialTheme.typography.displayMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isSuggested) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Suggested",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}