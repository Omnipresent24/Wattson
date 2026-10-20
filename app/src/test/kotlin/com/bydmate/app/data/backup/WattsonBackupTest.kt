package com.bydmate.app.data.backup

import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** WATTSON fork: daily backups by default, a second copy on the SD card, Wattson prefs carried. */
class WattsonBackupTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeSettingsDao : SettingsDao {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override suspend fun getMany(keys: List<String>): List<SettingEntity> =
            keys.mapNotNull { k -> map[k]?.let { SettingEntity(k, it) } }
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    @Test fun `daily backup is on until the user chooses`() = runTest {
        val settings = SettingsRepository(FakeSettingsDao(), mockk<LocalePreferences>(relaxed = true))
        assertEquals(AutoBackupPeriod.DAILY, settings.getAutoBackupPeriod())
        assertEquals(AutoBackupPeriod.DAILY, settings.observeAutoBackupPeriod().first())
        settings.setAutoBackupPeriod(AutoBackupPeriod.OFF)
        assertEquals(AutoBackupPeriod.OFF, settings.getAutoBackupPeriod())
    }

    @Test fun `wattson settings travel with the backup`() {
        assertTrue("wattson_safety" in BackupManager.PREFS_FILES)
        assertTrue("wattson_camera" in BackupManager.PREFS_FILES)
    }

    @Test fun `card keeps the newest automatic copies and every manual one`() {
        val source = tmp.newFolder("Download")
        val card = tmp.newFolder("Sicherungen")
        val manual = File(source, "bydmate_backup_20261001_100000.zip").apply { writeText("m") }
        assertNotNull(BackupMirror.copyTo(card, manual))
        repeat(BackupMirror.KEEP_AUTO + 3) { i ->
            val f = File(source, "${AutoBackupRunner.AUTO_PREFIX}202610${(i + 10)}_100000.zip").apply {
                writeText("a$i")
                setLastModified(1_000_000_000_000L + i * 1000L)
            }
            assertNotNull(BackupMirror.copyTo(card, f))
        }
        val names = card.list()!!.toSet()
        assertTrue(manual.name in names)
        assertEquals(BackupMirror.KEEP_AUTO, names.count { it.startsWith(AutoBackupRunner.AUTO_PREFIX) })
        assertTrue("${AutoBackupRunner.AUTO_PREFIX}202610${BackupMirror.KEEP_AUTO + 12}_100000.zip" in names)
    }
}
