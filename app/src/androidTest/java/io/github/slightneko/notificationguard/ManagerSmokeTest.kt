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
    private fun shell(command: String): String = instrument.uiAutomation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
    @Before fun prepare() {
        assertTrue("Synthetic-data tests are emulator-only",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
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
        instrument.waitForIdleSync()
        val screenshot = instrument.uiAutomation.takeScreenshot()
        assertNotNull(screenshot)
        val directory = File(context.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
        File(directory,"$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
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
    @Test fun ordinaryShellCallerCannotReadOrMutateBridge() {
        val read = shell("content query --uri content://${GuardProvider.AUTHORITY}/state")
        assertTrue(read.contains("SecurityException") || read.contains("Module-only"))
        val write = shell("content call --uri content://${GuardProvider.AUTHORITY} --method request --arg enable")
        assertTrue(write.contains("SecurityException") || write.contains("Untrusted"))
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
