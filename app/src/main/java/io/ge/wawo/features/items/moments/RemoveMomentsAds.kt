package io.ge.wawo.features.items.moments

import com.tencent.mm.plugin.sns.storage.ADInfo
import dev.ujhhgtg.reflekt.reflekt
import io.ge.wawo.R
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.features.core.SwitchFeature
import io.ge.wawo.utils.WeLogger

object RemoveMomentsAds : SwitchFeature() {

    override val technicalId = "拦截朋友圈广告"
    override val nameRes = R.string.feature_remove_moments_ads_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_remove_moments_ads_description

    private const val TAG = "RemoveMomentsAds"

    override fun onEnable() {
        ADInfo::class.reflekt()
            .firstConstructor {
                parameters(String::class)
            }
            .hookBefore {
                WeLogger.i(TAG, "blocked ad")
                result = null
            }
    }
}
