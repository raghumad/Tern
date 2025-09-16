package com.madanala.tern.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate

@Composable
fun Compass(
    rotation: Float,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = Icons.Default.KeyboardArrowUp,
        contentDescription = "Map Compass",
        modifier = modifier.rotate(rotation)
    )
}
