package io.ge.wawo.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import io.ge.wawo.i18n.LocaleResourceMode
import io.ge.wawo.i18n.WeKitLocaleProvider

@Composable
fun ModuleAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    WeKitLocaleProvider(mode = LocaleResourceMode.ModuleApp) {
        val colorScheme = if (darkTheme) darkScheme else lightScheme
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
        ) {
            content()
        }
    }
}
