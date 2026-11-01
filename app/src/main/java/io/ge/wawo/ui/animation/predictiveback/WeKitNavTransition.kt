package io.ge.wawo.ui.animation.predictiveback

import io.ge.wawo.ui.utils.theme.PageTransitionAnimation
import top.yukonga.miuix.kmp.nav.transition.NavTransition

fun weKitNavTransition(animation: PageTransitionAnimation): NavTransition = when (animation) {
    PageTransitionAnimation.AOSP -> AospNavTransition
    PageTransitionAnimation.MIUIX -> top.yukonga.miuix.kmp.nav.transition.NavTransitions.MiuixDefault
}
