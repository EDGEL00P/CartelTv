package com.carteltv.tv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateHome() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        launchAndBrowseHome()
    }

    @Test
    fun generateGuide() = rule.collect(packageName = TARGET_PACKAGE) {
        launchAndBrowseGuide()
    }

    @Test
    fun generateChannelZap() = rule.collect(packageName = TARGET_PACKAGE) {
        launchAndChannelZap()
    }
}
