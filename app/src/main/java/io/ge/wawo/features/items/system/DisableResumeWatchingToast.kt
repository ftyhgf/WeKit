package io.ge.wawo.features.items.system

import io.ge.wawo.R
import io.ge.wawo.dexkit.abc.IResolveDex
import io.ge.wawo.dexkit.dsl.dexMethod
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.features.core.SwitchFeature

object DisableResumeWatchingToast : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用「刚刚在看」提醒"
    override val nameRes = R.string.feature_disable_resume_watching_toast_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_disable_resume_watching_toast_description

    private val methodShowRecoveryToast by dexMethod {
        matcher {
            paramCount = 0
            usingEqStrings(
                "MicroMsg.RecoveryHelper",
                "topActivity == null or isFinishing or isDestroyed",
                "recoveryObj == null ",
                "toast_button",
                "view_exp",
            )
        }
    }

    override fun onEnable() {
        methodShowRecoveryToast.hookBefore {
            result = null
        }
    }
}
