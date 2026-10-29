package com.android.r0dumpmanager.data.draft

import androidx.test.platform.app.InstrumentationRegistry
import com.android.r0dumpmanager.domain.FusionConfigSchema
import com.android.r0dumpmanager.domain.ProfileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftStateStoreTest {
    @Test
    fun draftSurvivesStoreRecreationAndIsGenerationBound() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DraftStateStore(context)
        val draft = FusionConfigSchema.defaults().copy(
            runId = "draft-persistence-test",
            targetPackage = "com.example.target",
            targetProcess = "com.example.target",
        )

        store.clear()
        try {
            store.save(draft, 24L, ProfileId.COOLAPK_COOKIE_WALK)

            assertEquals(
                SavedFusionDraft(draft, 24L, ProfileId.COOLAPK_COOKIE_WALK),
                DraftStateStore(context).restore(24L),
            )
            assertNull(DraftStateStore(context).restore(26L))
        } finally {
            store.clear()
        }
    }
}
