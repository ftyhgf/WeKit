package io.ge.wawo.loader.startup

import io.ge.wawo.loader.abc.IHookBridge
import io.ge.wawo.loader.abc.ILoaderService

object StartupInfo {

    lateinit var modulePath: String
    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null
}
