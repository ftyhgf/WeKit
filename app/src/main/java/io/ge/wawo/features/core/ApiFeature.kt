package io.ge.wawo.features.core

abstract class ApiFeature : BaseFeature() {

    final override fun startup() {
        enable()
    }
}
