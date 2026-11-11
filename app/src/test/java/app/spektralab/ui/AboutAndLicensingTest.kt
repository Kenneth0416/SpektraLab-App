package app.spektralab.ui

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.spektralab.MainActivity
import app.spektralab.R
import app.spektralab.SpektraApp
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi", sdk = [34])
class AboutAndLicensingTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun appNameIn(locale: Locale): String {
        val app = ApplicationProvider.getApplicationContext<SpektraApp>()
        val config = Configuration(app.resources.configuration)
        config.setLocale(locale)
        return app.createConfigurationContext(config).getString(R.string.app_name)
    }

    @Test fun displayNameFollowsDeviceLocale() {
        assertEquals("Silvercam", appNameIn(Locale.ENGLISH))
        assertEquals("银盐工坊", appNameIn(Locale.forLanguageTag("zh-Hans")))
        assertEquals("銀鹽工坊", appNameIn(Locale.forLanguageTag("zh-Hant")))
    }

    @Test fun bundledLicenseAssetsAreCompleteAndAccurate() {
        val app = ApplicationProvider.getApplicationContext<SpektraApp>()
        assertEquals("Silvercam", app.getString(R.string.app_name))

        val gpl = BundledLicenses.loadAsset(app, "licenses/GPL-3.0.txt")
        assertTrue(gpl.contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(gpl.contains("Version 3, 29 June 2007"))

        val cc = BundledLicenses.loadAsset(app, "spektra/SPEKTRAFILM_LICENSE.txt")
        assertTrue(cc.contains("by Andrea Volpato"))
        assertTrue(cc.contains("https://github.com/andreavolpato/spektrafilm"))
        assertTrue(cc.contains("Creative Commons"))
        assertTrue(cc.contains("Attribution-ShareAlike 4.0 International"))

        val changelog = BundledLicenses.loadAsset(app, "spektra/CHANGELOG.txt")
        assertTrue(changelog.contains("2026-11-10"))
        assertTrue(changelog.contains("2026-11-11"))
        assertTrue(changelog.contains("https://github.com/Kenneth0416/SpektraLab-App"))

        val notice = BundledLicenses.loadAsset(app, "licenses/NOTICE.txt")
        assertTrue(notice.contains("Silvercam"))
        assertTrue(notice.contains("银盐工坊"))
        assertTrue(notice.contains("not trademark-cleared"))
        assertTrue(notice.contains("Andrea Volpato"))
        assertTrue(notice.contains("https://github.com/andreavolpato/spektrafilm"))
        assertTrue(notice.contains("https://github.com/Kenneth0416/SpektraLab-App"))
        assertTrue(notice.replace('\n', ' ').contains("Corresponding Source"))

        val longCang = BundledLicenses.loadAsset(app, "licenses/OFL-LongCang.txt")
        assertTrue(longCang.contains("SIL OPEN FONT LICENSE Version 1.1"))
        val martian = BundledLicenses.loadAsset(app, "licenses/OFL-MartianMono.txt")
        assertTrue(martian.contains("SIL OPEN FONT LICENSE Version 1.1"))
        val apache = BundledLicenses.loadAsset(app, "licenses/APACHE-2.0.txt")
        assertTrue(apache.contains("Apache License"))
        assertTrue(apache.contains("Version 2.0, January 2004"))
    }

    @Test fun libraryShowsBrandAndOpensAboutWithFullLicenses() {
        rule.onNodeWithText("Silvercam · CONTACT SHEET").assertIsDisplayed()
        rule.onNodeWithContentDescription("关于与开源许可").assertIsDisplayed().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("About Silvercam & open-source licenses").assertIsDisplayed()
        rule.onNodeWithText("https://github.com/andreavolpato/spektrafilm", substring = true).assertIsDisplayed()
        rule.onNodeWithText("https://github.com/Kenneth0416/SpektraLab-App", substring = true).assertIsDisplayed()

        // The independent-project and trademark statements must be visible, not only bundled as files.
        rule.onNodeWithText("not trademark-cleared", substring = true).assertIsDisplayed()
        rule.onNodeWithText("独立项目声明", substring = true).assertIsDisplayed()

        // Open GPLv3 full local text
        rule.onNodeWithTag("about_list")
            .performScrollToNode(hasContentDescription("GNU GPL v3.0 完整许可原文"))
        rule.onNodeWithContentDescription("GNU GPL v3.0 完整许可原文").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertIsDisplayed()

        // Return to overview and open CC BY-SA 4.0 data license
        rule.onNodeWithText("‹ 返回").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("about_list")
            .performScrollToNode(hasContentDescription("CC BY-SA 4.0 与 spektrafilm 数据前序"))
        rule.onNodeWithContentDescription("CC BY-SA 4.0 与 spektrafilm 数据前序").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("License for spektrafilm profiles, LUTs, and direct derivatives", substring = true).assertIsDisplayed()

        // Return and check CHANGELOG, NOTICE, OFL fonts, and Apache-2.0
        val extraDocs = listOf(
            "档案与 LUT 修改记录（CHANGELOG）" to "SPK1 binary files",
            "修改声明、源码与第三方依赖摘要" to "INDEPENDENT FORK / NON-ENDORSEMENT NOTICE",
            "SIL OFL 1.1 — Long Cang 字体" to "Copyright 2018 The Long Cang Project Authors",
            "SIL OFL 1.1 — Martian Mono 字体" to "Copyright 2021 The Martian Mono Project Authors",
            "Apache License 2.0 完整原文" to "Version 2.0, January 2004",
        )
        for ((desc, expectedSnippet) in extraDocs) {
            rule.onNodeWithText("‹ 返回").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("about_list").performScrollToNode(hasContentDescription(desc))
            rule.onNodeWithContentDescription(desc).performClick()
            rule.waitForIdle()
            rule.onNodeWithText(expectedSnippet, substring = true).assertIsDisplayed()
        }
    }
}
