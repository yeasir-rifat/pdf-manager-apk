package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiBackupExclusionTest {

    @Test fun driveBackupNeverListsTheAiPreferences() {
        assertTrue(BackupSettings.all.none { it.file == AiPrefs.FILE })
        assertTrue(BackupSettings.all.none { it.key == AiPrefs.KEY })
        assertEquals(null, BackupSettings.spec("${AiPrefs.FILE}.${AiPrefs.KEY}"))
    }

    private fun rule(name: String): String {
        val file = listOf("src/main/res/xml/$name", "app/src/main/res/xml/$name").map(::File).first { it.exists() }
        return file.readText()
    }

    private val exclude = "<exclude domain=\"sharedpref\" path=\"${AiPrefs.FILE}.xml\" />"

    @Test fun oldAndroidBackupRulesExcludeIt() {
        assertTrue(rule("backup_rules.xml").contains(exclude))
    }

    @Test fun newAndroidRulesExcludeItFromCloudBackupAndDeviceTransfer() {
        val xml = rule("data_extraction_rules.xml")
        val cloud = xml.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = xml.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertTrue(cloud.contains(exclude))
        assertTrue(transfer.contains(exclude))
    }
}
