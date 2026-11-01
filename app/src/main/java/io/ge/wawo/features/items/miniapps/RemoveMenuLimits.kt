package io.ge.wawo.features.items.miniapps

import io.ge.wawo.R
import io.ge.wawo.dexkit.abc.IResolveDex
import io.ge.wawo.dexkit.dsl.dexMethod
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.features.core.SwitchFeature
import io.ge.wawo.utils.TargetProcess
import io.ge.wawo.utils.enumValueOfClass
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.base.AccessFlagsMatcher
import java.lang.reflect.Modifier

object RemoveMenuLimits : SwitchFeature(), IResolveDex {

    override val technicalId = "去除菜单限制"
    override val nameRes = R.string.feature_remove_menu_limits_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_remove_menu_limits_description

    private lateinit var showAndClickableEnumValue: Any

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.APPBRAND)

    override fun onEnable() {
        listOf(
            methodGetMenuItemVisibility1,
            methodGetMenuItemVisibility2
        ).forEach {
            it.hookBefore {
                if (!::showAndClickableEnumValue.isInitialized) {
                    val returnType = methodGetMenuItemVisibility1.method.returnType
                    showAndClickableEnumValue = enumValueOfClass(returnType, "SHOW_CLICKABLE")
                }
                result = showAndClickableEnumValue
            }
        }
    }

    private val methodGetMenuItemVisibility1 by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.menu")

        matcher {
            declaredClass {
                superClass {
                    modifiers(AccessFlagsMatcher(Modifier.ABSTRACT))
                }

                addMethod {
                    usingNumbers(39)
                }
            }

            returnType("com.tencent.mm.plugin.appbrand.menu", StringMatchType.Contains)
        }
    }

    private val methodGetMenuItemVisibility2 by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.menu")
        matcher {
            declaredClass {
                superClass {
                    modifiers(AccessFlagsMatcher(Modifier.ABSTRACT))
                }

                addMethod {
                    usingNumbers(30)
                }
            }

            returnType("com.tencent.mm.plugin.appbrand.menu", StringMatchType.Contains)
        }
    }
}
