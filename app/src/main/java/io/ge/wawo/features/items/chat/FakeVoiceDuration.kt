package io.ge.wawo.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.ge.wawo.R
import io.ge.wawo.dexkit.abc.IResolveDex
import io.ge.wawo.dexkit.dsl.dexMethod
import io.ge.wawo.features.core.ClickableFeature
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.data.KvStore
import io.ge.wawo.ui.content.AlertDialogContent
import io.ge.wawo.ui.content.Button
import io.ge.wawo.ui.content.TextButton
import io.ge.wawo.ui.utils.showComposeDialog
import io.ge.wawo.utils.android.showToast

object FakeVoiceDuration : ClickableFeature(), IResolveDex {

    override val technicalId = "伪装语音时长"
    override val nameRes = R.string.feature_fake_voice_duration_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_fake_voice_duration_description

    private val methodVoiceRecorderGetLength by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.SceneVoice.Recorder", "Stop file success: ")
            }
            returnType = "long"
        }
    }
    private const val KEY_DURATION = "fake_voice_duration"

    override fun onEnable() {
        methodVoiceRecorderGetLength.hookBefore {
            result = KvStore.getLongOrDef(KEY_DURATION, 0L)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var durationInput by remember { mutableStateOf(KvStore.getLongOrDef(KEY_DURATION, 0).toString()) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_fake_voice_duration_name)) },
                text = {
                    TextField(
                        value = durationInput,
                        onValueChange = { durationInput = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.chat_fake_voice_duration_millis)) })
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(onClick = {
                        val durationMs = durationInput.toLongOrNull()
                        if (durationMs == null) {
                            showToast(localizedChatString(R.string.chat_fake_voice_duration_invalid))
                            return@Button
                        }

                        KvStore.putLong(KEY_DURATION, durationMs)
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }
}
