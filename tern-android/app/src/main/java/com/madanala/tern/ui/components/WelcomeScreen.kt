package com.madanala.tern.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madanala.tern.R
import com.madanala.tern.redux.GpsStatus

@Composable
fun WelcomeScreen(
    modifier: Modifier = Modifier,
    gpsStatus: com.madanala.tern.redux.GpsStatus = com.madanala.tern.redux.GpsStatus.INITIAL,
    onRetryGps: () -> Unit = {}
) {
    val gruppoFont = FontFamily(Font(R.font.gruppo_regular))

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Cyan),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            // App branding (consistent with current design)
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(id = R.drawable.kjartan_birgisson),
                    contentDescription = "Kjartan Birgisson",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(250.dp)
                        .padding(start = 90.dp, top = 20.dp)
                )

                Text(
                    text = "Tern Paragliding",
                    style = TextStyle(
                        fontFamily = gruppoFont,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.W900,
                        color = Color.White,
                        shadow = Shadow(
                            color = Color.Black,
                            blurRadius = 5f
                        ),
                        letterSpacing = 1.5.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // GPS status-specific content
            when (gpsStatus) {
                com.madanala.tern.redux.GpsStatus.INITIAL -> {
                    InitialWelcomeView(gruppoFont)
                }
                com.madanala.tern.redux.GpsStatus.ACQUIRING -> {
                    AcquiringGpsView(gruppoFont)
                }
                com.madanala.tern.redux.GpsStatus.LOST -> {
                    GpsLostView(gruppoFont, onRetryGps)
                }
                com.madanala.tern.redux.GpsStatus.DISABLED -> {
                    PermissionRequiredView(gruppoFont)
                }
                com.madanala.tern.redux.GpsStatus.ACTIVE -> {
                    // GPS active but location not ready yet (final acquisition phase)
                    AcquiringGpsView(gruppoFont, isFinalAcquisition = true)
                }
            }
        }
    }
}

@Composable
private fun InitialWelcomeView(gruppoFont: FontFamily) {
    Text(
        text = "Welcome to Tern Paragliding",
        style = TextStyle(
            fontFamily = gruppoFont,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            shadow = Shadow(
                color = Color.Black,
                blurRadius = 3f
            )
        ),
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Tap \"Allow\" when prompted for location access to begin",
        style = TextStyle(
            fontFamily = gruppoFont,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            color = Color.White.copy(alpha = 0.9f),
            shadow = Shadow(
                color = Color.Black,
                blurRadius = 2f
            )
        ),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun AcquiringGpsView(gruppoFont: FontFamily, isFinalAcquisition: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.9f)
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            // Animated GPS icon
            Box(
                modifier = Modifier.size(60.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color.Cyan,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(50.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isFinalAcquisition) "Finalizing GPS location..." else "Acquiring GPS Location",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Please ensure location services are enabled and you have a clear view of the sky.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GpsLostView(gruppoFont: FontFamily, onRetryGps: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "GPS Signal Lost",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.Red
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "GPS location is required for accurate airspace information and safe paragliding operations.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onRetryGps
            ) {
                Text("Retry GPS Acquisition")
            }
        }
    }
}

@Composable
private fun PermissionRequiredView(gruppoFont: FontFamily) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "Location Access Required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.Red
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Tern requires location access to provide accurate airspace information and weather data for your flying location.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}
