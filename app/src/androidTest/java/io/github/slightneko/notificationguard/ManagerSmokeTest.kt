package io.github.slightneko.notificationguard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.slightneko.notificationguard.data.GuardProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ManagerSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun shell(command: String): String {
        val descriptors = instrument.uiAutomation.executeShellCommandRwe(command)
        descriptors[1].close()
        val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
        val error = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).bufferedReader().use { it.readText() }
        return output + error
    }
    @Before fun prepare() {
        assertTrue("Synthetic-data tests are emulator-only",Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        context.contentResolver.call(GuardProvider.URI,"rules",null,null)
        val protected = context.createDeviceProtectedStorageContext()
        protected.getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putBoolean("onboarded",true).commit()
        SQLiteDatabase.openDatabase(protected.getDatabasePath("guard.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DELETE FROM channels WHERE pkg='com.example.guard.demo'")
            db.execSQL("DELETE FROM stats WHERE pkg='com.example.guard.demo'")
            db.execSQL("DELETE FROM rules WHERE pkg='com.example.guard.demo'")
            db.execSQL("INSERT INTO channels VALUES(0,'com.example.guard.demo','ads','促销通知','演示应用',3,1)")
            db.execSQL("INSERT INTO channels VALUES(0,'com.example.guard.demo','service','常驻服务','演示应用',2,1)")
            db.execSQL("INSERT INTO stats VALUES(?,0,'com.example.guard.demo','ads',36,30,4)",arrayOf(LocalDate.now().toString()))
            db.execSQL("INSERT INTO stats VALUES(?,0,'com.example.guard.demo','service',10,0,9)",arrayOf(LocalDate.now().toString()))
        }
        shell("wm size reset"); shell("wm density reset"); shell("cmd uimode night no")
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(1200)
        instrument.waitForIdleSync()
        val screenshot = instrument.uiAutomation.takeScreenshot()
        assertNotNull(screenshot)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/NotificationGuardSmoke")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun waitFor(label: String) {
        compose.waitUntil(20000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun phoneAndTabletScreensAndChannelToggles() {
        shell("wm size 720x1280"); shell("wm density 320")
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("演示应用"); screenshot("phone-ranking")
            compose.onNodeWithText("渠道").performClick()
            waitFor("演示应用"); compose.onNodeWithText("演示应用").performClick()
            waitFor("促销通知")
            compose.onAllNodes(isToggleable()).onFirst().performClick()
            compose.waitUntil(15000) {
                context.contentResolver.query(GuardProvider.URI.buildUpon().appendPath("rules").build(),null,null,null,null)?.use { c -> c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("blocked")) == 1 } == true
            }
            screenshot("phone-channels")
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("设置").performClick(); waitFor("SystemUI"); screenshot("phone-settings")
        }
        shell("cmd uimode night yes")
        ActivityScenario.launch(MainActivity::class.java).use { waitFor("演示应用"); screenshot("phone-dark") }
        shell("wm size 1600x2560"); shell("wm density 240")
        ActivityScenario.launch(MainActivity::class.java).use { waitFor("演示应用"); screenshot("tablet-ranking") }
        shell("wm size reset"); shell("wm density reset"); shell("cmd uimode night no")
    }
    @Test fun onboardingDoesNotEnableNotificationsWithoutSystemConnection() {
        context.createDeviceProtectedStorageContext().getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putBoolean("onboarded",false).commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("进入")
            compose.onNodeWithText("一键开启全部通知").assertIsNotEnabled()
            screenshot("phone-onboarding")
            compose.onNodeWithText("进入").performClick()
            waitFor("演示应用")
        }
    }
    @Test fun ordinaryShellCallerCannotReadOrMutateBridge() {
        val read = shell("content query --uri content://${GuardProvider.AUTHORITY}/state")
        assertTrue("Unexpected query result: $read",read.contains("SecurityException") || read.contains("Module-only"))
        val write = shell("content call --uri content://${GuardProvider.AUTHORITY} --method request --arg enable")
        assertTrue("Unexpected command result: $write",write.contains("SecurityException") || write.contains("Untrusted"))
    }
    @Test fun databaseSchemaDoesNotStoreNotificationContent() {
        val protected = context.createDeviceProtectedStorageContext()
        SQLiteDatabase.openDatabase(protected.getDatabasePath("guard.db").path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            for (table in listOf("rules","channels","stats","state","jobs","backup","batches")) {
                db.rawQuery("PRAGMA table_info($table)",null).use { c ->
                    while (c.moveToNext()) assertFalse(c.getString(c.getColumnIndexOrThrow("name")) in setOf("title","text","body","extras","notification","tag"))
                }
            }
        }
    }
}
