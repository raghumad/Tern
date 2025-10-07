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
            //large spacer to push content towards center
            Spacer(modifier = Modifier.height(250.dp))

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

            Spacer(modifier = Modifier.height(300.dp))

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
    // Subtle welcome text, not competing with branding
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = "Tap \"Allow\" when prompted for location access",
            style = TextStyle(
                fontFamily = gruppoFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.8f),
                shadow = Shadow(
                    color = Color.Black,
                    blurRadius = 2f
                )
            ),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AcquiringGpsView(gruppoFont: FontFamily, isFinalAcquisition: Boolean = false) {
    // Subtle bottom-aligned GPS status - much less prominent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .background(
                    Color.Black.copy(alpha = 0.3f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = if (isFinalAcquisition) "Finalizing location..." else "Acquiring GPS...",
                style = TextStyle(
                    fontFamily = gruppoFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White
                )
            )
        }
    }
}

@Composable
private fun GpsLostView(gruppoFont: FontFamily, onRetryGps: () -> Unit) {
    // Subtle bottom-aligned GPS status
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(
                    Color.Black.copy(alpha = 0.3f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "GPS Signal Lost",
                style = TextStyle(
                    fontFamily = gruppoFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.Red.copy(alpha = 0.9f)
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onRetryGps,
                modifier = Modifier.height(28.dp)
            ) {
                Text(
                    text = "Retry",
                    fontSize = 10.sp,
                    fontFamily = gruppoFont
                )
            }
        }
    }
}

@Composable
private fun PermissionRequiredView(gruppoFont: FontFamily) {
    // Subtle bottom-aligned GPS status
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(
                    Color.Black.copy(alpha = 0.3f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Location Access Required",
                style = TextStyle(
                    fontFamily = gruppoFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.Red.copy(alpha = 0.9f)
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Tap \"Allow\" when prompted",
                style = TextStyle(
                    fontFamily = gruppoFont,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.8f)
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}
