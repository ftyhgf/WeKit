package io.ge.wawo.features.items.chat

import dev.ujhhgtg.reflekt.utils.makeAccessible
import io.ge.wawo.R
import io.ge.wawo.constants.PackageNames
import io.ge.wawo.dexkit.abc.IResolveDex
import io.ge.wawo.dexkit.dsl.data
import io.ge.wawo.dexkit.dsl.dexClass
import io.ge.wawo.dexkit.dsl.dexMethod
import io.ge.wawo.features.api.core.WeMessageApi
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.features.core.SwitchFeature
import io.ge.wawo.utils.HookCallback
import io.ge.wawo.utils.HookParam
import io.ge.wawo.utils.hookDirectly
import io.ge.wawo.utils.reflection.bool
import io.ge.wawo.utils.reflection.int
import io.ge.wawo.utils.reflection.void
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArraySet

object RemoveMessageSelectionLimit : SwitchFeature(), IResolveDex {

    override val technicalId = "解除消息多选数量限制"
    override val nameRes = R.string.feature_remove_message_selection_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_remove_message_selection_limit_description

    private const val SELECTION_LIMIT = 100

    private val methodGetSelectedMessageCount by dexMethod {
        matcher {
            declaredClass(WeMessageApi.classChattingDataAdapter.data.name)
            addUsingField {
                type(CopyOnWriteArraySet::class.java)
            }
            paramCount(0)
            returnType(int)
        }
    }

    private val classChatItemQuickSelect by dexClass {
        searchPackages("${PackageNames.WECHAT}.ui.chatting.component")
        matcher {
            usingEqStrings(
                "MicroMsg.ChatItemQuickSelectComponent",
                "initViews: chattingQuickSelectRootUp="
            )
        }
    }

    private val methodSetQuickSelectViewEnabled1 by dexMethod(
        allowMultiple = true,
        resultIndex = 0
    ) {
        matcher {
            declaredClass(classChatItemQuickSelect.data.name)
            usingNumbers(SELECTION_LIMIT)
            paramTypes(bool)
            returnType(void)
        }
    }

    private val methodSetQuickSelectViewEnabled2 by dexMethod(
        allowMultiple = true,
        resultIndex = 1
    ) {
        matcher {
            declaredClass(classChatItemQuickSelect.data.name)
            usingNumbers(SELECTION_LIMIT)
            paramTypes(bool)
            returnType(void)
        }
    }

    private val selectedMessagesField: Field by lazy {
        WeMessageApi.methodToggleMessageSelection.method.declaringClass.declaredFields.single {
            it.type == CopyOnWriteArraySet::class.java
        }.makeAccessible()
    }

    private data class TemporarilyRemovedSelections(
        val selectedMessages: CopyOnWriteArraySet<Any>,
        val removed: List<Any>
    )

    private val selectedMessageCountOverride = ThreadLocal<Int>()

    override fun onEnable() {
        listOf(
            methodSetQuickSelectViewEnabled1,
            methodSetQuickSelectViewEnabled2
        ).forEach {
            it.hookBefore {
                args[0] = true
            }
        }

        methodGetSelectedMessageCount.hookBefore {
            selectedMessageCountOverride.get()?.let {
                result = it
            }
        }

        val hook = object : HookCallback() {
            override fun beforeHookedMethod(param: HookParam) {
                val adapter = param.thisObject ?: return
                val message = param.args[0] ?: return
                @Suppress("UNCHECKED_CAST")
                val selectedMessages = selectedMessagesField.get(adapter) as CopyOnWriteArraySet<Any>
                if (message in selectedMessages || selectedMessages.size < SELECTION_LIMIT) return

                // Let WeChat run its original add and UI refresh path with 99 existing selections.
                val removed = selectedMessages.take(selectedMessages.size - SELECTION_LIMIT + 1)
                selectedMessages.removeAll(removed.toSet())
                param.extra = TemporarilyRemovedSelections(selectedMessages, removed)
                selectedMessageCountOverride.set(selectedMessages.size + removed.size + 1)
            }

            override fun afterHookedMethod(param: HookParam) {
                val state = param.extra as? TemporarilyRemovedSelections ?: return
                val remainingAndNew = state.selectedMessages.toList()
                state.selectedMessages.clear()
                state.selectedMessages.addAll(state.removed)
                state.selectedMessages.addAll(remainingAndNew)
                selectedMessageCountOverride.remove()
            }
        }

        registerUnhook(WeMessageApi.methodToggleMessageSelection.method.hookDirectly(hook))
    }
}
