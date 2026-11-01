package io.ge.wawo.features.items.system

import android.content.Context
import androidx.annotation.StringRes
import io.ge.wawo.i18n.LocaleResourceMode
import io.ge.wawo.i18n.LocalizedContextFactory
import io.ge.wawo.i18n.WeKitLocaleController
import io.ge.wawo.utils.HostInfo

fun localizedSystemString(@StringRes id: Int, vararg args: Any): String =
    HostInfo.application.systemLocalizedContext().getString(id, *args)

fun Context.localizedSystemString(@StringRes id: Int, vararg args: Any): String =
    systemLocalizedContext().getString(id, *args)

private fun Context.systemLocalizedContext(): Context = LocalizedContextFactory.create(
    this,
    WeKitLocaleController.resolvedLocale,
    LocaleResourceMode.InjectedHost,
)
