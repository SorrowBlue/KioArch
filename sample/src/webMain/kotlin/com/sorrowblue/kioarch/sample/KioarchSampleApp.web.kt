package com.sorrowblue.kioarch.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import kioarch_root.sample.generated.resources.Res
import kioarch_root.sample.generated.resources.noto_sans_jp_regular
import org.jetbrains.compose.resources.Font

@Composable
internal actual fun AppTheme(content: @Composable (() -> Unit)) {
    MaterialTheme(
        typography = getAppTypography(), content = content
    )
}


@Composable
private fun getAppTypography(): Typography {
    val notoFontFamily = FontFamily(Font(Res.font.noto_sans_jp_regular))
    return Typography().run {
        copy(
            displayLarge = displayLarge.copy(fontFamily = notoFontFamily),
            displayMedium = displayMedium.copy(fontFamily = notoFontFamily),
            displaySmall = displaySmall.copy(fontFamily = notoFontFamily),
            headlineLarge = headlineLarge.copy(fontFamily = notoFontFamily),
            headlineMedium = headlineMedium.copy(fontFamily = notoFontFamily),
            headlineSmall = headlineSmall.copy(fontFamily = notoFontFamily),
            titleLarge = titleLarge.copy(fontFamily = notoFontFamily),
            titleMedium = titleMedium.copy(fontFamily = notoFontFamily),
            titleSmall = titleSmall.copy(fontFamily = notoFontFamily),
            bodyLarge = bodyLarge.copy(fontFamily = notoFontFamily),
            bodyMedium = bodyMedium.copy(fontFamily = notoFontFamily),
            bodySmall = bodySmall.copy(fontFamily = notoFontFamily),
            labelLarge = labelLarge.copy(fontFamily = notoFontFamily),
            labelMedium = labelMedium.copy(fontFamily = notoFontFamily),
            labelSmall = labelSmall.copy(fontFamily = notoFontFamily)
        )
    }
}
