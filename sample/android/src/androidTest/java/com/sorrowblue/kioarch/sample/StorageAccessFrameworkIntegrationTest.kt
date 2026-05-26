package com.sorrowblue.kioarch.sample

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pure UI Automator integration test for the KioArch sample application.
 * Bypasses Espresso synchronization completely and implements robust click retry mechanisms
 * to defend against [StaleObjectException] during rapid screen transitions or layout redraws
 * on modern Android versions (like API 34+ / 35 / 37).
 */
@RunWith(AndroidJUnit4::class)
class StorageAccessFrameworkIntegrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext

    private var createdFileUri: Uri? = null
    private val testFilename = "test_ui_automator.zip"

    @Before
    fun setUp() {
        // Prepare a valid, mock ZIP file inside Android's shared Downloads directory.
        createdFileUri = createTestZipInDownloads(context, testFilename)
        assertNotNull("Failed to create test ZIP file in Downloads directory", createdFileUri)
    }

    @After
    fun tearDown() {
        // Clean up the dynamically created zip file.
        createdFileUri?.let {
            deleteTestZipInDownloads(context, it)
        }
    }

    @Test
    fun testArchiveImportViaStorageAccessFramework() {
        // Start the activity via ActivityScenario to avoid Espresso idling resource lookup.
        ActivityScenario.launch(MainActivity::class.java).use {
            // 1. Initial State Check: Click the "Choose Archive File" button using our robust retry helper.
            clickWithRetry(device, By.text("Choose Archive File"))

            // 2. Wait for the native Storage Access Framework (DocumentsUI) picker to populate.
            val safPackagePattern = Pattern.compile("com\\.(google\\.)?android\\.documentsui")
            val isSafLoaded = device.wait(
                Until.hasObject(By.pkg(safPackagePattern)),
                15000
            )
            assertTrue("Storage Access Framework (DocumentsUI) dialog did not load", isSafLoaded)

            // Let the Document Picker settle down
            Thread.sleep(1000)

            // 3. Open the navigation drawer (Show roots / Hamburger menu) in the document picker using retry click.
            val menuPattern = Pattern.compile("(?i)Show roots|Navigate up|メニュー|戻る")
            clickWithRetry(device, By.desc(menuPattern))

            // 4. Find and select the "Downloads" directory from the side roots menu list using retry click.
            val downloadsPattern = Pattern.compile("(?i)Downloads|ダウンロード")
            clickWithRetry(device, By.text(downloadsPattern))

            // 5. Find the prepared "test_ui_automator.zip" within the file list and click using retry click.
            clickWithRetry(device, By.text(testFilename))

            // 6. Verification: Wait for the sample application to process and display the archive entries.
            // Check that the title "test_ui_automator.zip" is displayed.
            val titleNode = device.wait(Until.findObject(By.text(testFilename)), 12000)
            assertNotNull("Imported ZIP file name was not displayed in UI after selection", titleNode)

            // Verify the extracted file entries are rendered via KioArch in the UI.
            val entryNode = device.wait(Until.findObject(By.text("test.txt")), 6000)
            assertNotNull("Decompressed entry 'test.txt' was not found in entry list UI", entryNode)

            val countNode = device.wait(Until.findObject(By.text("1 Entries detected")), 6000)
            assertNotNull("Entries count '1 Entries detected' was not found in UI", countNode)
        }
    }

    /**
     * Attempts to click a view element matching the provided selector. If a [StaleObjectException]
     * is encountered (e.g. if the view is in the process of rebuilding during an animation),
     * it automatically refetches the node and retries up to the given timeout.
     */
    private fun clickWithRetry(device: UiDevice, selector: BySelector, timeoutMs: Long = 10000) {
        var clicked = false
        val endTime = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < endTime && !clicked) {
            try {
                val obj = device.wait(Until.findObject(selector), 1900)
                if (obj != null) {
                    obj.click()
                    clicked = true
                }
            } catch (e: StaleObjectException) {
                // View node updated while attempting to interact, ignore and retry in the next loop.
            }
            if (!clicked) {
                Thread.sleep(400)
            }
        }
        if (!clicked) {
            throw AssertionError("Failed to click element matching selector: $selector within ${timeoutMs}ms")
        }
    }

    private fun createTestZipInDownloads(context: Context, filename: String): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { os ->
                ZipOutputStream(os).use { zos ->
                    // Inject a single entry inside the mock zip file
                    zos.putNextEntry(ZipEntry("test.txt"))
                    zos.write("Hello KioArch UI Automator integration test!".toByteArray())
                    zos.closeEntry()
                }
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
        return uri
    }

    private fun deleteTestZipInDownloads(context: Context, uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            // Suppress cleanup exceptions
        }
    }
}
