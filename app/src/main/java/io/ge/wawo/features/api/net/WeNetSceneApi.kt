package io.ge.wawo.features.api.net

import dev.ujhhgtg.reflekt.reflekt
import io.ge.wawo.R
import io.ge.wawo.dexkit.abc.IResolveDex
import io.ge.wawo.dexkit.dsl.dexMethod
import io.ge.wawo.features.api.core.WeDatabaseApi
import io.ge.wawo.features.core.ApiFeature
import io.ge.wawo.features.core.FeatureCategoryIds

object WeNetSceneApi : ApiFeature(), IResolveDex {

    override val technicalId = "NetScene 服务"
    override val nameRes = R.string.feature_we_net_scene_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_net_scene_api_description

    fun sendNetScene(netScene: Any) {
        val queue = WeDatabaseApi.classMmKernel.clazz.reflekt()
            .firstMethod {
                returnType = methodAddNetSceneToQueue.method.declaringClass
            }.invokeStatic()!!
        methodAddNetSceneToQueue.method.invoke(queue, netScene, 0)
    }

    val methodAddNetSceneToQueue by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.NetSceneQueue", "forbid in waiting: type=", "forbid in running: type=")
        }
    }
}
