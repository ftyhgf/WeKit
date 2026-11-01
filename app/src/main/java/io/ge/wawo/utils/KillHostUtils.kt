package io.ge.wawo.utils

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import io.ge.wawo.R
import io.ge.wawo.i18n.LocaleResourceMode
import io.ge.wawo.i18n.LocalizedContextFactory
import io.ge.wawo.i18n.WeKitLocaleController
import io.ge.wawo.utils.android.showToast
import kotlin.system.exitProcess

fun restartHost() {
    WeLogger.i("KillHostUtils", "restarting host")
    val context = LocalizedContextFactory.create(
        HostInfo.application,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
    showToast(context, context.getString(R.string.noncompose_restarting_host))
    val instance = "com.tencent.mm.process.KillProcessHelperActivity".toClass()
        .reflekt().firstField().getStatic()!!
    instance.reflekt().firstMethod().invoke(HostInfo.application, true)
}

fun killHost() {
    WeLogger.i("KillHostUtils", "killing host")
    exitProcess(0)
}
