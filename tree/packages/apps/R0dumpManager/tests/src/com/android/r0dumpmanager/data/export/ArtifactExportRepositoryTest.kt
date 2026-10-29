package com.android.r0dumpmanager.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class ArtifactExportRepositoryTest {
    @Test
    fun decodeResponseReadsStableHelperLayout() {
        val bytes = response(status = 0, detail = "ok")

        val result = ArtifactExportRepository.decodeResponse(bytes)

        assertEquals("run-1", result.runId)
        assertEquals("/sdcard/Downloads/r0dump-exports/com.example/run-1.zip", result.archivePath)
        assertEquals(9, result.remoteArtifactCount)
        assertEquals(2, result.codeItemsBackfilled)
    }

    @Test
    fun decodeResponsePreservesHelperFailureDetail() {
        val error = assertThrows(ArtifactExportException::class.java) {
            ArtifactExportRepository.decodeResponse(response(status = 10, detail = "no_run_with_artifacts"))
        }

        assertEquals(10, error.status)
        assertEquals("no_run_with_artifacts", error.detail)
    }

    private fun response(status: Int, detail: String): ByteArray {
        val buffer = ByteBuffer.allocate(682).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x52304552)
        buffer.putShort(1.toShort())
        buffer.putShort(682.toShort())
        buffer.putInt(status)
        listOf(9, 12, 7, 3, 2, 8, 2).forEach(buffer::putInt)
        buffer.putFixed("run-1", 65)
        buffer.putFixed("/sdcard/Downloads/r0dump-exports/com.example/run-1.zip", 256)
        buffer.putFixed("a".repeat(64), 65)
        buffer.putFixed(detail, 256)
        return buffer.array()
    }

    private fun ByteBuffer.putFixed(value: String, size: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        put(bytes)
        position(position() + size - bytes.size)
    }
}
