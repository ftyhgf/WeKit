package io.ge.wawo.features.items.system

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import io.ge.wawo.R
import io.ge.wawo.dexkit.abc.IResolveDex
import io.ge.wawo.dexkit.dsl.dexMethod
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.features.core.SwitchFeature
import io.ge.wawo.i18n.HostLocalizedStrings
import io.ge.wawo.ui.content.AlertDialogContent
import io.ge.wawo.ui.content.TextButton
import io.ge.wawo.ui.utils.showComposeDialog
import io.ge.wawo.utils.HostInfo
import io.ge.wawo.utils.android.showToast

object PreventXposedDetection : SwitchFeature(), IResolveDex {

    override val technicalId = "禁止微信检测 Xposed"
    override val nameRes = R.string.feature_prevent_xposed_detection_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_prevent_xposed_detection_description

    private val methodCheckStackTraceElements by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.app")
        matcher {
            usingEqStrings(
                "de.robv.android.xposed.XposedBridge",
                "com.zte.heartyservice.SCC.FrameworkBridge"
            )
        }
    }

    override fun onEnable() {
        if (HostInfo.isHostGooglePlay) {
            showToast(HostLocalizedStrings.get(R.string.system_prevent_xposed_google_play_warning))
            applyToggle(false)
            return
        }

        if (methodCheckStackTraceElements.isPlaceholder) return

        methodCheckStackTraceElements.hookBefore {
            result = false
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState && HostInfo.isHostGooglePlay) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(stringResource(R.string.feature_prevent_xposed_detection_name)) },
                    text = {
                        Text(stringResource(R.string.system_prevent_xposed_google_play_warning))
                    },
                    confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } })
            }
            return false
        }

        return true
    }
}
