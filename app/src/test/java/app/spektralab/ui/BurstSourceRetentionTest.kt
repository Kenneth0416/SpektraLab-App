package app.spektralab.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import app.spektralab.SpektraApp
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BurstSourceRetentionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun mergeCompletionKeepsFirstFrameReadableForOutstandingConsumers() {
        val app = ApplicationProvider.getApplicationContext<SpektraApp>()
        val original = File(app.cacheDir, "retained-first.dng").apply { writeText("original raw bytes") }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/x-adobe-dng"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                               selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                original.delete()
                return 1
            }
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply {
            authority = "retention"
            exported = true
        })
        ShadowContentResolver.registerProviderInternal("retention", provider)
        val first = Uri.parse("content://retention/first")
        val merged = Uri.parse("content://retention/merged")
        val state = AppState(app)
        val store = ViewModelStore().apply { put("state", state) }
        try {
            // A queued export owns `first` even if its lightbox entry has since disappeared.
            rule.runOnIdle { state.shooter.onFinished(first, merged, 3f) }
            rule.waitUntil { state.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } }
            assertTrue(original.exists())
            assertEquals("original raw bytes", app.contentResolver.openInputStream(first)!!.bufferedReader().use { it.readText() })
            // Verify this provider really would delete the source if production called delete.
            assertEquals(1, app.contentResolver.delete(first, null, null))
            org.junit.Assert.assertFalse(original.exists())
        } finally {
            rule.runOnIdle { store.clear() }
            original.delete()
        }
    }
}
