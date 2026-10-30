package app.stronger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.stronger.R

/**
 * Google Sans Flex, pre-instanced (see README) at full roundness for brand styles and half for
 * plain text, so type feels as soft as the shapes around it.
 */
private val Brand = FontFamily(
    Font(R.font.gsf_r100_w400, FontWeight.W400),
    Font(R.font.gsf_r100_w500, FontWeight.W500),
    Font(R.font.gsf_r100_w600, FontWeight.W600),
    Font(R.font.gsf_r100_w700, FontWeight.W700),
    Font(R.font.gsf_r100_w800, FontWeight.W800),
)

private val Plain = FontFamily(
    Font(R.font.gsf_r50_w400, FontWeight.W400),
    Font(R.font.gsf_r50_w500, FontWeight.W500),
    Font(R.font.gsf_r50_w600, FontWeight.W600),
    Font(R.font.gsf_r50_w700, FontWeight.W700),
    Font(R.font.gsf_r50_w800, FontWeight.W800),
)

private fun TextStyle.brand() = copy(fontFamily = Brand)
private fun TextStyle.plain() = copy(fontFamily = Plain)

private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.brand(), displayMedium = t.displayMedium.brand(), displaySmall = t.displaySmall.brand(),
        headlineLarge = t.headlineLarge.brand(), headlineMedium = t.headlineMedium.brand(), headlineSmall = t.headlineSmall.brand(),
        titleLarge = t.titleLarge.brand(), titleMedium = t.titleMedium.plain(), titleSmall = t.titleSmall.plain(),
        bodyLarge = t.bodyLarge.plain(), bodyMedium = t.bodyMedium.plain(), bodySmall = t.bodySmall.plain(),
        labelLarge = t.labelLarge.plain(), labelMedium = t.labelMedium.plain(), labelSmall = t.labelSmall.plain(),
        displayLargeEmphasized = t.displayLargeEmphasized.brand(), displayMediumEmphasized = t.displayMediumEmphasized.brand(),
        displaySmallEmphasized = t.displaySmallEmphasized.brand(), headlineLargeEmphasized = t.headlineLargeEmphasized.brand(),
        headlineMediumEmphasized = t.headlineMediumEmphasized.brand(), headlineSmallEmphasized = t.headlineSmallEmphasized.brand(),
        titleLargeEmphasized = t.titleLargeEmphasized.brand(), titleMediumEmphasized = t.titleMediumEmphasized.plain(),
        titleSmallEmphasized = t.titleSmallEmphasized.plain(), bodyLargeEmphasized = t.bodyLargeEmphasized.plain(),
        bodyMediumEmphasized = t.bodyMediumEmphasized.plain(), bodySmallEmphasized = t.bodySmallEmphasized.plain(),
        labelLargeEmphasized = t.labelLargeEmphasized.plain(), labelMediumEmphasized = t.labelMediumEmphasized.plain(),
        labelSmallEmphasized = t.labelSmallEmphasized.plain(),
    )
}

@Composable
fun StrongerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = AppTypography,
        content = content,
    )
}
