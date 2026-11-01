package io.ge.wawo.features.items.home_screen_menu

import androidx.annotation.StringRes
import io.ge.wawo.i18n.LocaleResourceMode
import io.ge.wawo.i18n.LocalizedContextFactory
import io.ge.wawo.i18n.WeKitLocaleController
import io.ge.wawo.utils.HostInfo

fun localizedHomeMenuString(@StringRes id: Int, vararg formatArgs: Any): String =
    LocalizedContextFactory.create(
        HostInfo.application,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(id, *formatArgs)
