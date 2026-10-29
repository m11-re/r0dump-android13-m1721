package com.android.r0dumpmanager.data.export

import android.app.ActivityManager
import android.os.ParcelFileDescriptor
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class ArtifactExportResult(
    val runId: String,
    val archivePath: String,
    val sha256: String,
    val remoteArtifactCount: Int,
    val recordCount: Int,
    val verified: Int,
    val repaired: Int,
    val partial: Int,
    val usableOutputs: Int,
    val codeItemsBackfilled: Int,
)

class ArtifactExportException(
    val status: Int,
    val detail: String,
) : IOException("artifact export failed: status=$status detail=$detail")

class ArtifactExportRepository {
    fun exportLatest(packageName: String): ArtifactExportResult {
        val descriptor = ActivityManager.getService().r0dump_exportLatest(packageName)
            ?: throw ArtifactExportException(STATUS_INTERNAL_ERROR, "request_rejected")
        return descriptor.use(::readResponse)
    }

    private fun readResponse(descriptor: ParcelFileDescriptor): ArtifactExportResult {
        val bytes = ByteArray(RESPONSE_BYTES)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) throw EOFException("short helper response")
                offset += read
            }
        }
        return decodeResponse(bytes)
    }

    companion object {
        private const val RESPONSE_MAGIC = 0x52304552
        private const val RESPONSE_VERSION: Short = 1
        private const val RESPONSE_BYTES = 682
        private const val RUN_ID_BYTES = 65
        private const val ARCHIVE_PATH_BYTES = 256
        private const val SHA256_BYTES = 65
        private const val DETAIL_BYTES = 256
        private const val STATUS_OK = 0
        private const val STATUS_INTERNAL_ERROR = 9

        fun decodeResponse(bytes: ByteArray): ArtifactExportResult {
            require(bytes.size == RESPONSE_BYTES) { "invalid helper response size" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.int == RESPONSE_MAGIC) { "invalid helper response magic" }
            require(buffer.short == RESPONSE_VERSION) { "invalid helper response version" }
            require((buffer.short.toInt() and 0xffff) == RESPONSE_BYTES) {
                "invalid helper response layout"
            }
            val status = buffer.int
            val remoteArtifactCount = buffer.int
            val recordCount = buffer.int
            val verified = buffer.int
            val repaired = buffer.int
            val partial = buffer.int
            val usableOutputs = buffer.int
            val codeItemsBackfilled = buffer.int
            val runId = buffer.fixedString(RUN_ID_BYTES)
            val archivePath = buffer.fixedString(ARCHIVE_PATH_BYTES)
            val sha256 = buffer.fixedString(SHA256_BYTES)
            val detail = buffer.fixedString(DETAIL_BYTES)
            if (status != STATUS_OK) throw ArtifactExportException(status, detail)
            require(runId.isNotBlank() && archivePath.isNotBlank() && sha256.length == 64) {
                "incomplete helper success response"
            }
            return ArtifactExportResult(
                runId = runId,
                archivePath = archivePath,
                sha256 = sha256,
                remoteArtifactCount = remoteArtifactCount,
                recordCount = recordCount,
                verified = verified,
                repaired = repaired,
                partial = partial,
                usableOutputs = usableOutputs,
                codeItemsBackfilled = codeItemsBackfilled,
            )
        }

        private fun ByteBuffer.fixedString(size: Int): String {
            val bytes = ByteArray(size)
            get(bytes)
            val terminator = bytes.indexOf(0)
            require(terminator >= 0) { "unterminated helper response field" }
            require(bytes.drop(terminator + 1).all { it == 0.toByte() }) {
                "non-zero helper response padding"
            }
            return String(bytes, 0, terminator, StandardCharsets.UTF_8)
        }
    }
}
