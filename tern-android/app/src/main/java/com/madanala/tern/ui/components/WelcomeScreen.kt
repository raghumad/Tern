package com.madanala.tern.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madanala.tern.R

@Composable
fun WelcomeScreen(modifier: Modifier = Modifier) {
    val gruppoFont = FontFamily(Font(R.font.gruppo_regular))

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Cyan),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
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
                        fontWeight = FontWeight.W900, // equivalent to heavy
                        color = Color.White,
                        shadow = Shadow(
                            color = Color.Black,
                            blurRadius = 5f
                        ),
                        letterSpacing = 1.5.sp
                    )
                )
            }
        }
    }
}
