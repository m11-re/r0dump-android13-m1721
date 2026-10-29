package com.android.r0dumpmanager.data.property

import com.android.r0dumpmanager.domain.FusionConfigSchema
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionPropertyRepositoryTest {
    @Test
    fun publishesDisablesAndRecoversWithGenerationChecks() {
        runBlocking {
            val store = FakePropertyStore()
            val repository = FusionPropertyRepository(store, store)
            assertEquals(FusionDeviceSnapshot.Unconfigured, repository.read())

            val draft = FusionConfigSchema.defaults().copy(
                enabled = true,
                runId = "run-1",
                targetPackage = "com.example.target",
                targetProcess = "com.example.target",
                capturePointMask = 1uL shl 39,
                forceInvokeEnabled = false,
            )
            val published = repository.publish(draft, 0L)
            assertTrue(published is FusionDeviceSnapshot.Enabled)
            assertEquals(2L, (published as FusionDeviceSnapshot.Enabled).generation)
            assertEquals(draft, published.draft)

            val disabled = repository.disable(2L)
            assertTrue(disabled is FusionDeviceSnapshot.Disabled)
            assertEquals(4L, (disabled as FusionDeviceSnapshot.Disabled).generation)
            assertEquals(false, disabled.draft.enabled)

            store.values["${FusionConfigSchema.PROPERTY_PREFIX}generation"] = "5"
            assertEquals(FusionDeviceSnapshot.Interrupted(5L), repository.read())
            val recovered = repository.recoverInterrupted()
            assertTrue(recovered is FusionDeviceSnapshot.Disabled)
            assertEquals(6L, (recovered as FusionDeviceSnapshot.Disabled).generation)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsStaleExpectedGeneration() {
        runBlocking {
            val store = FakePropertyStore().apply {
                values["${FusionConfigSchema.PROPERTY_PREFIX}generation"] = "8"
            }
            FusionPropertyRepository(store, store).publish(FusionConfigSchema.defaults(), 6L)
        }
    }

    @Test
    fun stableMalformedAndSemanticallyInvalidSnapshotsAreInvalid() {
        runBlocking {
            val malformed = FakePropertyStore().apply {
                publish(FusionConfigSchema.toJson(validDraft()), 0L)
                values["${FusionConfigSchema.PROPERTY_PREFIX}schema"] = "4294967297"
            }
            val malformedSnapshot = FusionPropertyRepository(malformed, malformed).read()
            assertTrue(malformedSnapshot is FusionDeviceSnapshot.Invalid)
            assertTrue((malformedSnapshot as FusionDeviceSnapshot.Invalid).reason.contains("schema"))

            val semantic = FakePropertyStore().apply {
                publish(FusionConfigSchema.toJson(validDraft()), 0L)
                values["${FusionConfigSchema.PROPERTY_PREFIX}max_artifacts"] = "513"
            }
            val semanticSnapshot = FusionPropertyRepository(semantic, semantic).read()
            assertEquals(
                FusionDeviceSnapshot.Invalid("validation:max_artifacts"),
                semanticSnapshot,
            )
        }
    }

    @Test
    fun unstableGenerationSandwichReturnsConcurrentChangeAfterThreeAttempts() {
        runBlocking {
            val store = FakePropertyStore().apply {
                publish(FusionConfigSchema.toJson(validDraft()), 0L)
                generationReadResults.addAll(listOf("2", "4", "4", "6", "6", "8"))
            }

            assertEquals(
                FusionDeviceSnapshot.ConcurrentChange,
                FusionPropertyRepository(store, store).read(),
            )
        }
    }

    @Test
    fun writerDenialAndReadbackMismatchAreNotReportedAsSuccess() {
        val denial = object : ManagerPropertyWriter {
            override fun publish(rawConfig: String, expectedGeneration: Long): Long =
                throw SecurityException("property_write_denied")
            override fun disable(expectedGeneration: Long): Long = error("unused")
            override fun recoverDisabled(): Long = error("unused")
        }
        assertThrows(SecurityException::class.java) {
            runBlocking {
                FusionPropertyRepository(FakePropertyStore(), denial).publish(validDraft(), 0L)
            }
        }

        val noOpWriter = object : ManagerPropertyWriter {
            override fun publish(rawConfig: String, expectedGeneration: Long) = expectedGeneration + 2L
            override fun disable(expectedGeneration: Long): Long = error("unused")
            override fun recoverDisabled(): Long = error("unused")
        }
        val mismatch = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                FusionPropertyRepository(FakePropertyStore(), noOpWriter).publish(validDraft(), 0L)
            }
        }
        assertTrue(mismatch.message.orEmpty().contains("verification"))
    }

    @Test
    fun disableAndRecoveryReadbackMismatchAreNotReportedAsSuccess() {
        val store = FakePropertyStore().apply { publish(FusionConfigSchema.toJson(validDraft()), 0L) }
        val noOpWriter = object : ManagerPropertyWriter {
            override fun publish(rawConfig: String, expectedGeneration: Long) = error("unused")
            override fun disable(expectedGeneration: Long) = expectedGeneration + 2L
            override fun recoverDisabled(): Long = 6L
        }
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { FusionPropertyRepository(store, noOpWriter).disable(2L) }
            }.message.orEmpty().contains("verification"),
        )

        store.values["${FusionConfigSchema.PROPERTY_PREFIX}generation"] = "5"
        store.values["${FusionConfigSchema.PROPERTY_PREFIX}enabled"] = "1"
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { FusionPropertyRepository(store, noOpWriter).recoverInterrupted() }
            }.message.orEmpty().contains("verification"),
        )
    }

    @Test
    fun classifiesStableDisabledEnabledInterruptedAndInvalidGenerationSnapshots() {
        runBlocking {
            val store = FakePropertyStore()
            val repository = FusionPropertyRepository(store, store)
            store.publish(FusionConfigSchema.toJson(validDraft().copy(enabled = false)), 0L)
            assertTrue(repository.read() is FusionDeviceSnapshot.Disabled)

            store.publish(FusionConfigSchema.toJson(validDraft()), 2L)
            assertTrue(repository.read() is FusionDeviceSnapshot.Enabled)

            store.values["${FusionConfigSchema.PROPERTY_PREFIX}generation"] = "5"
            assertEquals(FusionDeviceSnapshot.Interrupted(5L), repository.read())

            store.values["${FusionConfigSchema.PROPERTY_PREFIX}generation"] = "not-a-number"
            assertEquals(FusionDeviceSnapshot.Invalid("generation"), repository.read())
        }
    }
}

private fun validDraft() = FusionConfigSchema.defaults().copy(
    enabled = true,
    runId = "run-1",
    targetPackage = "com.example.target",
    targetProcess = "com.example.target",
    capturePointMask = 1uL shl 39,
    forceInvokeEnabled = false,
)

private class FakePropertyStore : PropertyAccess, ManagerPropertyWriter {
    val values = mutableMapOf<String, String>()
    val generationReadResults = java.util.ArrayDeque<String>()

    override fun get(key: String, default: String): String {
        if (key == "${FusionConfigSchema.PROPERTY_PREFIX}generation" && generationReadResults.isNotEmpty()) {
            return generationReadResults.removeFirst()
        }
        return values[key] ?: default
    }

    override fun publish(rawConfig: String, expectedGeneration: Long): Long {
        checkExpected(expectedGeneration)
        val json = JSONObject(rawConfig)
        val publishing = expectedGeneration + 1L
        values["${FusionConfigSchema.PROPERTY_PREFIX}generation"] = publishing.toString()
        FusionConfigSchema.fields.forEach { field ->
            val value = json.get(field)
            values[FusionConfigSchema.PROPERTY_PREFIX + field] = when (value) {
                is Boolean -> if (value) "1" else "0"
                else -> value.toString()
            }
        }
        return (publishing + 1L).also {
            values["${FusionConfigSchema.PROPERTY_PREFIX}generation"] = it.toString()
        }
    }

    override fun disable(expectedGeneration: Long): Long {
        checkExpected(expectedGeneration)
        values["${FusionConfigSchema.PROPERTY_PREFIX}enabled"] = "0"
        return (expectedGeneration + 2L).also {
            values["${FusionConfigSchema.PROPERTY_PREFIX}generation"] = it.toString()
        }
    }

    override fun recoverDisabled(): Long {
        val generation = get("${FusionConfigSchema.PROPERTY_PREFIX}generation", "0").toLong()
        require(generation % 2L == 1L)
        values["${FusionConfigSchema.PROPERTY_PREFIX}enabled"] = "0"
        return (generation + 1L).also {
            values["${FusionConfigSchema.PROPERTY_PREFIX}generation"] = it.toString()
        }
    }

    private fun checkExpected(expectedGeneration: Long) {
        val current = get("${FusionConfigSchema.PROPERTY_PREFIX}generation", "0").toLong()
        require(current == expectedGeneration && current % 2L == 0L)
    }
}
