package io.ge.wawo.features.items.moments

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.ge.wawo.i18n.LocaleResourceMode
import io.ge.wawo.i18n.LocalizedContextFactory
import io.ge.wawo.i18n.WeKitLocaleController
import io.ge.wawo.utils.HostInfo

fun localizedMomentsString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedMomentsString(id, *formatArgs)

fun Context.localizedMomentsString(@StringRes id: Int, vararg formatArgs: Any): String =
    momentsLocalizedContext().getString(id, *formatArgs)

fun localizedMomentsQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = HostInfo.application.momentsLocalizedContext().resources.getQuantityString(
    id,
    quantity,
    *formatArgs,
)

private fun Context.momentsLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
