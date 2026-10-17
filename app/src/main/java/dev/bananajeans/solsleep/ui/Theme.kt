@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable fun SolSleepTheme(night: Boolean = false,content: @Composable ()->Unit) {
    val dark=night || isSystemInDarkTheme()
    val context=LocalContext.current
    val scheme=if(android.os.Build.VERSION.SDK_INT>=31 && !night) {
        if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if(dark) darkColorScheme(
        primary=Color(0xFFE9C77C),onPrimary=Color(0xFF3D2E06),
        secondary=Color(0xFFAACBE3),background=Color(0xFF11171E),
        surface=Color(0xFF11171E),surfaceContainer=Color(0xFF1C2530),
        surfaceContainerHigh=Color(0xFF26313E),onSurface=Color(0xFFE5E9F0),
    ) else lightColorScheme(
        primary=Color(0xFF735817),onPrimary=Color.White,
        secondary=Color(0xFF456782),background=Color(0xFFF8F7F2),
        surface=Color(0xFFF8F7F2),surfaceContainer=Color(0xFFEEEDE7),
    )
    MaterialExpressiveTheme(colorScheme=scheme,content=content)
}
