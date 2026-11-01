package io.ge.wawo.features.items.contacts

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.ge.wawo.i18n.LocaleResourceMode
import io.ge.wawo.i18n.LocalizedContextFactory
import io.ge.wawo.i18n.WeKitLocaleController
import io.ge.wawo.utils.HostInfo

fun localizedContactsString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedContactsString(id, *formatArgs)

fun Context.localizedContactsString(@StringRes id: Int, vararg formatArgs: Any): String =
    contactsLocalizedContext().getString(id, *formatArgs)

fun localizedContactsQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = HostInfo.application.localizedContactsQuantity(id, quantity, *formatArgs)

fun Context.localizedContactsQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = contactsLocalizedContext().resources.getQuantityString(id, quantity, *formatArgs)

private fun Context.contactsLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
