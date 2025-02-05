/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.appcompat.app

import androidx.appcompat.testutils.LocalesActivityTestRule
import androidx.appcompat.testutils.LocalesUtils.CUSTOM_LOCALE_LIST
import androidx.appcompat.testutils.LocalesUtils.assertConfigurationLocalesEquals
import androidx.appcompat.testutils.LocalesUtils.setLocalesAndWaitForRecreate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.testutils.LifecycleOwnerUtils.waitUntilState
import junit.framework.TestCase.assertNotSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = 32)
class LocalesForegroundDialogTestCase {
    @get:Rule
    val rule = LocalesActivityTestRule(LocalesUpdateActivity::class.java)
    private var baseLocales = LocaleListCompat.getEmptyLocaleList()

    @Before
    fun setUp() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        baseLocales = LocalesUpdateActivity.getConfigLocales(rule.activity.resources.configuration)
    }

    @Test
    fun testLocalesChangeWithForegroundDialog() {
        val firstActivity = rule.activity
        assertConfigurationLocalesEquals(baseLocales, firstActivity)

        // Open a dialog on top of the activity.
        rule.runOnUiThread {
            val frag = TestDialogFragment.newInstance()
            frag.show(firstActivity.supportFragmentManager, "dialog")
        }

        // Now change the locales for the foreground activity.
        setLocalesAndWaitForRecreate(
            firstActivity,
            CUSTOM_LOCALE_LIST
        )

        // Ensure that it was recreated.
        assertNotSame(rule.activity, firstActivity)
        waitUntilState(rule.activity, Lifecycle.State.RESUMED)
    }
}
