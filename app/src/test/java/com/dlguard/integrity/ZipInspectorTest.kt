package com.dlguard.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipInspectorTest {

    private fun buildStoredZip(content: String): File {
        val file = File.createTempFile("guard_zip_", ".zip")
        val bytes = content.toByteArray()
        val crc = CRC32().apply { update(bytes) }
        ZipOutputStream(FileOutputStream(file)).use { out ->
            val entry = ZipEntry("payload.bin").apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
            }
            out.putNextEntry(entry)
            out.write(bytes)
            out.closeEntry()
        }
        return file
    }

    @Test
    fun `valid zip passes eocd and crc verification`() {
        val zip = buildStoredZip("download-guard")
        try {
            val scan = ZipInspector.scanEocd(zip)
            assertTrue(scan is EocdScan.Found)
            assertEquals(1, (scan as EocdScan.Found).info.entryCount)
            val result = ZipInspector.verifyEntries(zip)
            assertEquals(null, result.errorVerdict)
            assertEquals(1, result.entries)
        } finally {
            zip.delete()
        }
    }

    @Test
    fun `truncated zip is reported as missing eocd`() {
        val zip = buildStoredZip("truncate-me".repeat(100))
        val truncated = File.createTempFile("guard_trunc_", ".zip")
        try {
            val bytes = zip.readBytes()
            truncated.writeBytes(bytes.copyOf(bytes.size - 40))
            assertTrue(ZipInspector.scanEocd(truncated) is EocdScan.NotFound)
            val result = ZipInspector.verifyEntries(truncated)
            assertNotNull(result.errorVerdict)
        } finally {
            zip.delete()
            truncated.delete()
        }
    }

    @Test
    fun `flipped payload byte is detected as crc error`() {
        val zip = buildStoredZip("crc-check-payload")
        try {
            RandomAccessFile(zip, "rw").use { raf ->
                raf.seek(26)
                val nameLength = readLeShort(raf)
                raf.seek(28)
                val extraLength = readLeShort(raf)
                val dataStart = 30L + nameLength + extraLength
                raf.seek(dataStart + 2)
                val original = raf.read()
                raf.seek(dataStart + 2)
                raf.write(original xor 0xFF)
            }
            val result = ZipInspector.verifyEntries(zip)
            assertEquals(Verdict.CRC_ERROR, result.errorVerdict)
        } finally {
            zip.delete()
        }
    }

    private fun readLeShort(raf: RandomAccessFile): Int {
        val low = raf.read()
        val high = raf.read()
        return (low and 0xFF) or ((high and 0xFF) shl 8)
    }
}
