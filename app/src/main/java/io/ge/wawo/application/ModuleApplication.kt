package io.ge.wawo.application

import android.app.Application
import android.content.Context
import dev.ujhhgtg.lsparanoid.generated.LspBootstrap
import io.ge.wawo.i18n.WeKitLocaleController
import io.ge.wawo.utils.HostInfo

class ModuleApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        LspBootstrap.loadInstalled()
    }

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
        WeKitLocaleController.initializeModuleProcess(this)
    }
}
