package com.android.r0dumpmanager.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionConfigContractTest {
    @Test fun schemaSerializesEveryFrameworkField() {
        val json = JSONObject(FusionConfigSchema.toJson(FusionConfigSchema.defaults()))
        assertEquals(FusionConfigSchema.FIELD_COUNT, json.length())
        assertEquals(FusionConfigSchema.fields.toSet(), json.keys().asSequence().toSet())
        assertEquals("0x000002800000002d", json.getString("capture_point_mask"))
        assertEquals("", json.getString("walk_namespace_prefix"))
    }
    @Test fun defaultsMatchTheManagerOperationalBaseline() {
        val defaults = FusionConfigSchema.defaults()
        assertEquals(1_073_741_824L, defaults.maxTotalBytes)
        assertEquals(268_435_456L, defaults.maxDexBytes)
        assertEquals(536_870_912L, defaults.bufferPoolBytes)
        assertEquals(4_194_304L, defaults.maxCodeItemBytes)
        assertEquals(4096L, defaults.maxRecords)
        assertEquals(512L, defaults.maxArtifacts)
        assertTrue(defaults.manifestEnabled)
        assertTrue(defaults.walkEnabled)
        assertEquals(WalkMode.DEX_NAMES, defaults.walkMode)
        assertEquals(16_384L, defaults.walkMaxClasses)
        assertEquals(65_536L, defaults.walkMaxMethods)
        assertTrue(defaults.forceInvokeEnabled)
        assertTrue(defaults.anrProtectionEnabled)
        assertEquals(
            setOf(0, 2, 3, 5, 39, 41),
            CapturePointRegistry.points.filter { CapturePointRegistry.contains(defaults.capturePointMask, it.bit) }
                .mapTo(mutableSetOf()) { it.bit },
        )
    }
    @Test fun schemaSavedStateRoundTripPreservesEveryDraftField() {
        val original = FusionConfigSchema.defaults().copy(
            enabled = true, runId = "run-1", targetPackage = "com.example.app",
            targetProcess = "com.example.app:worker", capturePointMask = CapturePointRegistry.forceNoExecuteMask,
            walkEnabled = true, walkStart = WalkStart.AFTER_FIRST_ACTIVITY_ON_CREATE, walkMode = WalkMode.DEX_NAMES,
            walkNamespacePrefix = "com.example.ui.",
            forceInvokeEnabled = true, forceClass = "com.example.Target", forceMethod = "run",
            forceMethodDescriptor = "()V", anrProtectionEnabled = true,
        )
        assertEquals(original, FusionConfigSchema.fromJson(FusionConfigSchema.toJson(original)))
    }

    @Test fun namespacePrefixRequiresTrailingSegmentBoundary() {
        val base = FusionConfigSchema.defaults().copy(
            enabled = true, runId = "run-1", targetPackage = "com.example.app",
            targetProcess = "com.example.app", capturePointMask = 1uL, forceInvokeEnabled = false,
        )
        assertTrue(FusionConfigValidator.validate(base.copy(walkNamespacePrefix = "com.example.ui.")).isValid)
        assertEquals(
            FusionValidationErrorCode.INVALID_NAMESPACE_PREFIX,
            FusionConfigValidator.validate(base.copy(walkNamespacePrefix = "com.example.ui")).errors["walk_namespace_prefix"]?.code,
        )
    }
    @Test fun captureRegistryIsTheFortyThreeBitArtContract() {
        assertEquals(43, CapturePointRegistry.points.size)
        assertEquals((0..42).toList(), CapturePointRegistry.points.map { it.bit })
        assertEquals("DexFileLoader::OpenCommon", CapturePointRegistry.points[3].sourceLocation)
        assertEquals("ForceInvoke.no_execute", CapturePointRegistry.points[41].name)
        assertEquals("Nterp.method.entry", CapturePointRegistry.points.last().name)
        assertFalse(FusionConfigValidator.validate(FusionConfigSchema.defaults().copy(enabled = true, runId = "x", targetPackage = "com.example.app", targetProcess = "com.example.app", capturePointMask = 1uL shl 42, forceInvokeEnabled = false)).errors.containsKey("capture_point_mask"))
        assertTrue(FusionConfigValidator.validate(FusionConfigSchema.defaults().copy(enabled = true, runId = "x", targetPackage = "com.example.app", targetProcess = "com.example.app", capturePointMask = 1uL shl 43)).errors.containsKey("capture_point_mask"))
    }
    @Test fun forceInvokeRequiresNoExecuteAndStrictDescriptor() {
        val base = FusionConfigSchema.defaults().copy(enabled = true, runId = "run-1", targetPackage = "com.example.app", targetProcess = "com.example.app", capturePointMask = 0uL, forceInvokeEnabled = true, forceClass = "com.example.Target", forceMethod = "run", forceMethodDescriptor = "(Ljava/lang/String;I)V")
        assertTrue(FusionConfigValidator.validate(base).errors.containsKey("capture_point_mask"))
        assertFalse(FusionConfigValidator.validate(base.copy(capturePointMask = CapturePointRegistry.forceNoExecuteMask)).errors.containsKey("force_method_descriptor"))
        assertTrue(FusionConfigValidator.validate(base.copy(capturePointMask = CapturePointRegistry.forceNoExecuteMask, forceMethodDescriptor = "(Ljava.lang.String;)V")).errors.containsKey("force_method_descriptor"))
    }
    @Test fun forceInvokeUsesManifestNamespacesWhenExactSelectorIsEmpty() {
        val manifestScope = FusionConfigSchema.defaults().copy(
            enabled = true,
            runId = "run-1",
            targetPackage = "com.example.app",
            targetProcess = "com.example.app",
        )
        assertTrue(FusionConfigValidator.validate(manifestScope).isValid)
        assertEquals(
            FusionValidationErrorCode.FORCE_SCOPE_REQUIRED,
            FusionConfigValidator.validate(manifestScope.copy(manifestEnabled = false)).errors["force_class"]?.code,
        )
        assertEquals(
            FusionValidationErrorCode.INVALID_METHOD_NAME,
            FusionConfigValidator.validate(manifestScope.copy(forceClass = "com.example.Target")).errors["force_method"]?.code,
        )
    }
    @Test fun targetProcessMustBePackageOrSuffix() {
        val draft = FusionConfigSchema.defaults().copy(enabled = true, runId = "r", targetPackage = "com.example.app", targetProcess = "com.example.app:worker", capturePointMask = 1uL, forceInvokeEnabled = false)
        assertTrue(FusionConfigValidator.validate(draft).isValid)
        assertTrue(FusionConfigValidator.validate(draft.copy(targetProcess = "com.example.application")).errors.containsKey("target_process"))
    }

    @Test fun profilesPreserveTargetAndApplyTheirDocumentedCapabilityDefaults() {
        val source = FusionConfigSchema.defaults().copy(
            targetPackage = "com.example.app",
            targetProcess = "com.example.app:worker",
            capturePointMask = CapturePointRegistry.forceNoExecuteMask,
            forceInvokeEnabled = true,
            forceClass = "com.example.Target",
            forceMethod = "run",
            forceMethodDescriptor = "()V",
            anrProtectionEnabled = true,
        )
        val defaults = ProfileRegistry.apply(ProfileId.DEFAULTS, source)
        assertEquals(source.targetPackage, defaults.targetPackage)
        assertEquals(source.targetProcess, defaults.targetProcess)
        assertTrue(defaults.forceInvokeEnabled)
        assertTrue(defaults.anrProtectionEnabled)
        assertTrue(defaults.walkEnabled)

        val coolApk = ProfileRegistry.apply(ProfileId.COOLAPK_COOKIE_WALK, source)
        assertEquals(1uL shl 39, coolApk.capturePointMask)
        assertTrue(coolApk.walkEnabled)
        assertFalse(coolApk.forceInvokeEnabled)
        assertEquals("", coolApk.forceClass)
        assertEquals("", coolApk.forceMethod)
        assertEquals("", coolApk.forceMethodDescriptor)
        assertFalse(coolApk.anrProtectionEnabled)
    }

    @Test fun budgetRelationshipsAreValidatedAtTheirBoundaries() {
        val valid = FusionConfigSchema.defaults().copy(
            enabled = true,
            runId = "run",
            targetPackage = "com.example.app",
            targetProcess = "com.example.app",
            capturePointMask = 1uL,
            forceInvokeEnabled = false,
            maxRecords = 8,
            maxArtifacts = 8,
            maxTotalBytes = 1024,
            maxDexBytes = 1024,
            bufferPoolBytes = 1024,
            maxCodeItemBytes = 1024,
        )
        assertTrue(FusionConfigValidator.validate(valid).isValid)
        assertTrue(FusionConfigValidator.validate(valid.copy(maxArtifacts = 9)).errors.containsKey("max_artifacts"))
        val dexOverTotal = FusionConfigValidator.validate(valid.copy(maxDexBytes = 1025)).errors
        assertTrue(dexOverTotal.containsKey("max_total_bytes"))
        assertTrue(dexOverTotal.containsKey("max_dex_bytes"))

        val poolBelowDex = FusionConfigValidator.validate(valid.copy(bufferPoolBytes = 1023)).errors
        assertTrue(poolBelowDex.containsKey("max_dex_bytes"))
        assertTrue(poolBelowDex.containsKey("buffer_pool_bytes"))

        val codeItemOverPool = FusionConfigValidator.validate(valid.copy(maxCodeItemBytes = 1025)).errors
        assertTrue(codeItemOverPool.containsKey("buffer_pool_bytes"))
        assertTrue(codeItemOverPool.containsKey("max_code_item_bytes"))
    }

    @Test fun budgetAbsoluteLimitsAreSharedWithTheUiContract() {
        assertEquals(4096L, FusionConfigValidator.MAX_RECORDS)
        assertEquals(512L, FusionConfigValidator.MAX_ARTIFACTS)
        assertEquals(1024L * 1024 * 1024, FusionConfigValidator.MAX_TOTAL_BYTES)
        assertEquals(256L * 1024 * 1024, FusionConfigValidator.MAX_DEX_BYTES)
        assertEquals(512L * 1024 * 1024, FusionConfigValidator.MAX_BUFFER_POOL_BYTES)
        assertEquals(4L * 1024 * 1024, FusionConfigValidator.MAX_CODE_ITEM_BYTES)
        assertEquals(4L, FusionConfigValidator.MAX_FROZEN_GENERATIONS)
        assertEquals(60_000L, FusionConfigValidator.MAX_FLUSH_IDLE_MS)
    }

    @Test fun restoringBudgetsDoesNotChangeOtherDraftSections() {
        val source = FusionConfigSchema.defaults().copy(
            runId = "keep-run",
            targetPackage = "com.example.app",
            targetProcess = "com.example.app:worker",
            capturePointMask = 1uL shl 39,
            maxRecords = 1,
            maxArtifacts = 1,
            maxTotalBytes = 1,
            manifestEnabled = true,
            walkEnabled = true,
            anrProtectionEnabled = true,
        )

        val restored = FusionConfigSchema.restoreDefaultBudgets(source)

        assertEquals(FusionConfigSchema.defaults().maxRecords, restored.maxRecords)
        assertEquals(FusionConfigSchema.defaults().maxArtifacts, restored.maxArtifacts)
        assertEquals(FusionConfigSchema.defaults().maxTotalBytes, restored.maxTotalBytes)
        assertEquals(source.runId, restored.runId)
        assertEquals(source.targetPackage, restored.targetPackage)
        assertEquals(source.targetProcess, restored.targetProcess)
        assertEquals(source.capturePointMask, restored.capturePointMask)
        assertEquals(source.manifestEnabled, restored.manifestEnabled)
        assertEquals(source.walkEnabled, restored.walkEnabled)
        assertEquals(source.anrProtectionEnabled, restored.anrProtectionEnabled)
    }

    @Test fun defaultsProfilePreservesManagerOwnedRunId() {
        val source = FusionConfigSchema.defaults().copy(
            runId = "manager-generated-run",
            targetPackage = "com.example.app",
            targetProcess = "com.example.app",
        )

        val defaults = ProfileRegistry.apply(ProfileId.DEFAULTS, source)

        assertEquals(source.runId, defaults.runId)
        assertEquals(source.targetPackage, defaults.targetPackage)
        assertEquals(source.targetProcess, defaults.targetProcess)
    }
}
