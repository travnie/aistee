package ais.tee.data.skills

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSkillLibraryStoreTest {
    private val root = Files.createTempDirectory("llmbench-local-skills").toFile()
    private val store = LocalSkillLibraryStore(root)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun storesSourceAsCanonicalSkillDocumentAndLoadsDisabledSummary() = runBlocking {
        val source = skillSource(RELEASE_SKILL, FIRST_VERSION)

        val added = store.add(source)
        val loaded = store.load()
        val storedDirectory = root.listFiles().orEmpty().single()
        val opened = store.read(RELEASE_SKILL)

        assertEquals(RELEASE_SKILL, added.name)
        assertEquals(FIRST_VERSION, added.description)
        assertFalse(added.enabled)
        assertNotEquals(RELEASE_SKILL, storedDirectory.name)
        assertEquals(source, storedDirectory.resolve(SKILL_FILE_NAME).readText())
        assertEquals(listOf(RELEASE_SKILL), loaded.map(LocalSkillSummary::name))
        assertFalse(loaded.single().enabled)
        assertEquals(source, opened?.source)
        assertEquals(localSkillSourceDigest(source), opened?.sourceDigest)
    }

    @Test
    fun activationPersistsAndOnlyEnabledManifestsReachRuntime() = runBlocking {
        store.add(skillSource(ALPHA_SKILL, ALPHA_DESCRIPTION, "Follow alpha instructions."))
        store.add(skillSource(BETA_SKILL, BETA_DESCRIPTION, "Follow beta instructions."))

        val enabled = store.setEnabled(BETA_SKILL, true)
        val reloadedStore = LocalSkillLibraryStore(root)
        val summaries = reloadedStore.load()
        val runtime = reloadedStore.loadEnabledManifests()

        assertEquals(BETA_SKILL, enabled?.name)
        assertTrue(enabled?.enabled == true)
        assertFalse(summaries.first { it.name == ALPHA_SKILL }.enabled)
        assertTrue(summaries.first { it.name == BETA_SKILL }.enabled)
        assertEquals(listOf(BETA_SKILL), runtime.map(AgentSkillManifest::name))
        assertEquals("# Instructions\nFollow beta instructions.", runtime.single().instructions)
    }

    @Test
    fun disablingRemovesSkillFromRuntimeSet() = runBlocking {
        store.add(skillSource(TOGGLE_SKILL, "Toggle."))
        store.setEnabled(TOGGLE_SKILL, true)

        val disabled = store.setEnabled(TOGGLE_SKILL, false)

        assertFalse(disabled?.enabled ?: true)
        assertTrue(store.loadEnabledManifests().isEmpty())
    }

    @Test
    fun replacingSameSkillRequiresExplicitFlagAndPreservesActivation() = runBlocking {
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val replacement = skillSource(RELEASE_SKILL, SECOND_VERSION)
        store.add(original)
        store.setEnabled(RELEASE_SKILL, true)

        val conflict = runCatching { store.add(replacement) }.exceptionOrNull()

        assertTrue(conflict is LocalSkillAlreadyExistsException)
        assertEquals(original, store.read(RELEASE_SKILL)?.source)

        val replaced = store.add(replacement, replaceExisting = true)

        assertTrue(replaced.enabled)
        assertEquals(1, store.load().size)
        assertEquals(replacement, store.read(RELEASE_SKILL)?.source)
        assertEquals(listOf(RELEASE_SKILL), store.loadEnabledManifests().map(AgentSkillManifest::name))
    }

    @Test
    fun boundReplacementUpdatesExistingSkillAndPreservesActivation() = runBlocking {
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val replacement = skillSource(RELEASE_SKILL, SECOND_VERSION)
        store.add(original)
        store.setEnabled(RELEASE_SKILL, true)
        val opened = requireNotNull(store.read(RELEASE_SKILL))

        val replaced = store.replace(RELEASE_SKILL, opened.sourceDigest, replacement)

        assertTrue(replaced.skill.enabled)
        assertEquals(SECOND_VERSION, replaced.skill.description)
        assertEquals(localSkillSourceDigest(replacement), replaced.sourceDigest)
        assertEquals(replacement, store.read(RELEASE_SKILL)?.source)
        assertEquals(listOf(RELEASE_SKILL), store.loadEnabledManifests().map(AgentSkillManifest::name))
    }

    @Test
    fun boundReplacementRejectsManifestRenameWithoutCreatingAnotherSkill() = runBlocking {
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val renamed = skillSource(RENAMED_SKILL, SECOND_VERSION)
        store.add(original)
        val opened = requireNotNull(store.read(RELEASE_SKILL))

        val error = runCatching {
            store.replace(RELEASE_SKILL, opened.sourceDigest, renamed)
        }.exceptionOrNull()

        assertTrue(error is LocalSkillRenameRequiredException)
        assertEquals(original, store.read(RELEASE_SKILL)?.source)
        assertNull(store.read(RENAMED_SKILL))
        assertEquals(listOf(RELEASE_SKILL), store.load().map(LocalSkillSummary::name))
    }

    @Test
    fun boundReplacementRequiresExistingSkill() = runBlocking {
        val replacement = skillSource(RELEASE_SKILL, SECOND_VERSION)

        val error = runCatching {
            store.replace(RELEASE_SKILL, localSkillSourceDigest(replacement), replacement)
        }.exceptionOrNull()

        assertTrue(error is LocalSkillNotFoundException)
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun boundReplacementRejectsOverBudgetEditBeforeTouchingEnabledSource() = runBlocking {
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        store.add(original)
        store.setEnabled(RELEASE_SKILL, true)
        val opened = requireNotNull(store.read(RELEASE_SKILL))
        val oversized = skillSource(
            RELEASE_SKILL,
            SECOND_VERSION,
            "x".repeat(MAX_RUNTIME_SKILL_INSTRUCTION_CHARS)
        )

        val error = runCatching {
            store.replace(RELEASE_SKILL, opened.sourceDigest, oversized)
        }.exceptionOrNull()

        assertTrue(error is LocalSkillActivationException)
        assertEquals(original, store.read(RELEASE_SKILL)?.source)
        assertTrue(store.load().single().enabled)
    }

    @Test
    fun boundReplacementRejectsStaleEditorAfterAnotherReplacement() = runBlocking {
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val newer = skillSource(RELEASE_SKILL, SECOND_VERSION)
        val staleDraft = skillSource(RELEASE_SKILL, "Stale editor version.")
        store.add(original)
        val opened = requireNotNull(store.read(RELEASE_SKILL))
        store.add(newer, replaceExisting = true)

        val error = runCatching {
            store.replace(RELEASE_SKILL, opened.sourceDigest, staleDraft)
        }.exceptionOrNull()

        assertTrue(error is LocalSkillSourceConflictException)
        assertEquals(newer, store.read(RELEASE_SKILL)?.source)
    }

    @Test
    fun renameMovesCanonicalSourceAndPreservesActivation() = runBlocking {
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val renamedSource = skillSource(RENAMED_SKILL, SECOND_VERSION)
        store.add(original)
        store.setEnabled(RELEASE_SKILL, true)
        val opened = requireNotNull(store.read(RELEASE_SKILL))

        val renamed = store.rename(RELEASE_SKILL, opened.sourceDigest, renamedSource)

        assertEquals(RENAMED_SKILL, renamed.skill.name)
        assertEquals(SECOND_VERSION, renamed.skill.description)
        assertTrue(renamed.skill.enabled)
        assertEquals(localSkillSourceDigest(renamedSource), renamed.sourceDigest)
        assertNull(store.read(RELEASE_SKILL))
        assertEquals(renamedSource, store.read(RENAMED_SKILL)?.source)
        assertEquals(listOf(RENAMED_SKILL), store.loadEnabledManifests().map(AgentSkillManifest::name))
    }

    @Test
    fun renameRejectsExistingTargetWithoutTouchingEitherSkill() = runBlocking {
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val target = skillSource(RENAMED_SKILL, "Existing target.")
        val renameDraft = skillSource(RENAMED_SKILL, SECOND_VERSION)
        store.add(original)
        store.add(target)
        val opened = requireNotNull(store.read(RELEASE_SKILL))

        val error = runCatching {
            store.rename(RELEASE_SKILL, opened.sourceDigest, renameDraft)
        }.exceptionOrNull()

        assertTrue(error is LocalSkillAlreadyExistsException)
        assertEquals(original, store.read(RELEASE_SKILL)?.source)
        assertEquals(target, store.read(RENAMED_SKILL)?.source)
    }

    @Test
    fun renameRejectsStaleSourceDigestBeforeCreatingTarget() = runBlocking {
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val newer = skillSource(RELEASE_SKILL, SECOND_VERSION)
        val renameDraft = skillSource(RENAMED_SKILL, "Stale rename.")
        store.add(original)
        val opened = requireNotNull(store.read(RELEASE_SKILL))
        store.add(newer, replaceExisting = true)

        val error = runCatching {
            store.rename(RELEASE_SKILL, opened.sourceDigest, renameDraft)
        }.exceptionOrNull()

        assertTrue(error is LocalSkillSourceConflictException)
        assertEquals(newer, store.read(RELEASE_SKILL)?.source)
        assertNull(store.read(RENAMED_SKILL))
    }

    @Test
    fun loadFinishesCommittedRenameCleanup() = runBlocking {
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val renamedSource = skillSource(RENAMED_SKILL, SECOND_VERSION)
        store.add(original)
        store.setEnabled(RELEASE_SKILL, true)
        val sourceDirectory = root.listFiles().orEmpty().single()
        val targetDirectory = storageDirectoryFor(RENAMED_SKILL)
        targetDirectory.mkdirs()
        targetDirectory.resolve(RENAME_FROM_FILE_NAME).writeText(
            renameMarker(committed = true, sourceDirectory.name)
        )
        targetDirectory.resolve(SKILL_FILE_NAME).writeText(renamedSource)
        targetDirectory.resolve(ENABLED_FILE_NAME).writeBytes(ByteArray(0))

        val loaded = store.load()

        assertEquals(listOf(RENAMED_SKILL), loaded.map(LocalSkillSummary::name))
        assertTrue(loaded.single().enabled)
        assertFalse(sourceDirectory.exists())
        assertFalse(targetDirectory.resolve(RENAME_FROM_FILE_NAME).exists())
        assertEquals(listOf(RENAMED_SKILL), store.loadEnabledManifests().map(AgentSkillManifest::name))
    }

    @Test
    fun pendingRenameTargetStaysInvisibleWhenCleanupFails() = runBlocking {
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val stagedSource = skillSource(RENAMED_SKILL, SECOND_VERSION)
        val targetDirectory = storageDirectoryFor(RENAMED_SKILL)
        val stubbornStore = LocalSkillLibraryStore(root) { directory ->
            if (directory.name == targetDirectory.name) false else directory.deleteRecursively()
        }
        stubbornStore.add(original)
        val sourceDirectory = root.listFiles().orEmpty().single()
        targetDirectory.mkdirs()
        targetDirectory.resolve(RENAME_FROM_FILE_NAME).writeText(
            renameMarker(committed = false, sourceDirectory.name)
        )
        targetDirectory.resolve(SKILL_FILE_NAME).writeText(stagedSource)

        val loaded = stubbornStore.load()

        assertEquals(listOf(RELEASE_SKILL), loaded.map(LocalSkillSummary::name))
        assertTrue(targetDirectory.exists())
        assertNull(stubbornStore.read(RENAMED_SKILL))
        assertEquals(original, stubbornStore.read(RELEASE_SKILL)?.source)
    }

    @Test
    fun renameChainDoesNotResurrectOldSkillWhenCleanupFails() = runBlocking {
        var blockedDirectoryName: String? = null
        val stubbornStore = LocalSkillLibraryStore(root) { directory ->
            if (directory.name == blockedDirectoryName) false else directory.deleteRecursively()
        }
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val secondSource = skillSource(RENAMED_SKILL, SECOND_VERSION)
        val thirdSource = skillSource(RENAMED_AGAIN_SKILL, "Third version.")
        stubbornStore.add(original)
        blockedDirectoryName = root.listFiles().orEmpty().single().name
        val firstOpened = requireNotNull(stubbornStore.read(RELEASE_SKILL))

        stubbornStore.rename(RELEASE_SKILL, firstOpened.sourceDigest, secondSource)

        assertNull(stubbornStore.read(RELEASE_SKILL))
        assertEquals(secondSource, stubbornStore.read(RENAMED_SKILL)?.source)
        val secondOpened = requireNotNull(stubbornStore.read(RENAMED_SKILL))
        stubbornStore.rename(RENAMED_SKILL, secondOpened.sourceDigest, thirdSource)

        assertNull(stubbornStore.read(RELEASE_SKILL))
        assertNull(stubbornStore.read(RENAMED_SKILL))
        assertEquals(thirdSource, stubbornStore.read(RENAMED_AGAIN_SKILL)?.source)
        val removalError = runCatching { stubbornStore.remove(RENAMED_AGAIN_SKILL) }.exceptionOrNull()
        assertTrue(removalError is IOException)
        assertEquals(listOf(RENAMED_AGAIN_SKILL), stubbornStore.load().map(LocalSkillSummary::name))
        assertNull(stubbornStore.read(RELEASE_SKILL))

        blockedDirectoryName = null
        stubbornStore.remove(RENAMED_AGAIN_SKILL)
        assertTrue(stubbornStore.load().isEmpty())
    }

    @Test
    fun renameBackRecoveryKeepsNewTargetCanonical() = runBlocking {
        var blockedDirectoryName: String? = null
        val stubbornStore = LocalSkillLibraryStore(root) { directory ->
            if (directory.name == blockedDirectoryName) false else directory.deleteRecursively()
        }
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val renamed = skillSource(RENAMED_SKILL, SECOND_VERSION)
        val renamedBack = skillSource(RELEASE_SKILL, "Back again.")
        stubbornStore.add(original)
        blockedDirectoryName = storageDirectoryFor(RELEASE_SKILL).name
        val firstOpened = requireNotNull(stubbornStore.read(RELEASE_SKILL))
        stubbornStore.rename(RELEASE_SKILL, firstOpened.sourceDigest, renamed)
        val secondOpened = requireNotNull(stubbornStore.read(RENAMED_SKILL))

        val blocked = runCatching {
            stubbornStore.rename(RENAMED_SKILL, secondOpened.sourceDigest, renamedBack)
        }.exceptionOrNull()

        assertTrue(blocked is IOException)
        assertEquals(renamed, stubbornStore.read(RENAMED_SKILL)?.source)
        assertNull(stubbornStore.read(RELEASE_SKILL))

        // Allow reclaiming the inherited A directory, but block cleanup of B after A commits.
        blockedDirectoryName = storageDirectoryFor(RENAMED_SKILL).name
        val retryOpened = requireNotNull(stubbornStore.read(RENAMED_SKILL))
        val result = stubbornStore.rename(RENAMED_SKILL, retryOpened.sourceDigest, renamedBack)
        val newTarget = storageDirectoryFor(RELEASE_SKILL)
        val oldTarget = storageDirectoryFor(RENAMED_SKILL)

        assertEquals(RELEASE_SKILL, result.skill.name)
        assertTrue(newTarget.resolve(RENAME_FROM_FILE_NAME).isFile)
        assertFalse(oldTarget.resolve(RENAME_FROM_FILE_NAME).exists())
        assertTrue(oldTarget.exists())
        assertEquals(renamedBack, stubbornStore.read(RELEASE_SKILL)?.source)
        assertNull(stubbornStore.read(RENAMED_SKILL))
        assertEquals(listOf(RELEASE_SKILL), stubbornStore.load().map(LocalSkillSummary::name))

        blockedDirectoryName = null
        assertEquals(listOf(RELEASE_SKILL), stubbornStore.load().map(LocalSkillSummary::name))
        assertFalse(oldTarget.exists())
        assertEquals(renamedBack, stubbornStore.read(RELEASE_SKILL)?.source)
    }

    @Test
    fun oldNameReuseWaitsUntilStaleTombstoneCanBeRetired() = runBlocking {
        var blockMarkerDelete = true
        val stubbornStore = LocalSkillLibraryStore(
            rootDirectory = root,
            deleteFile = { file ->
                if (file.name == RENAME_FROM_FILE_NAME && blockMarkerDelete) false else file.delete()
            }
        )
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val renamed = skillSource(RENAMED_SKILL, SECOND_VERSION)
        val reused = skillSource(RELEASE_SKILL, "Reused name.")
        stubbornStore.add(original)
        val opened = requireNotNull(stubbornStore.read(RELEASE_SKILL))
        stubbornStore.rename(RELEASE_SKILL, opened.sourceDigest, renamed)

        assertFalse(storageDirectoryFor(RELEASE_SKILL).exists())
        assertTrue(storageDirectoryFor(RENAMED_SKILL).resolve(RENAME_FROM_FILE_NAME).isFile)

        val blocked = runCatching { stubbornStore.add(reused) }.exceptionOrNull()

        assertTrue(blocked is IOException)
        assertNull(stubbornStore.read(RELEASE_SKILL))
        assertEquals(renamed, stubbornStore.read(RENAMED_SKILL)?.source)

        blockMarkerDelete = false
        stubbornStore.add(reused)

        assertEquals(reused, stubbornStore.read(RELEASE_SKILL)?.source)
        assertEquals(renamed, stubbornStore.read(RENAMED_SKILL)?.source)
        assertEquals(
            listOf(RELEASE_SKILL, RENAMED_SKILL),
            stubbornStore.load().map(LocalSkillSummary::name)
        )
    }

    @Test
    fun unreadableCommittedTargetNeverTombstonesLastValidSource() = runBlocking {
        val targetDirectory = storageDirectoryFor(RENAMED_SKILL)
        val stubbornStore = LocalSkillLibraryStore(root) { directory ->
            if (directory.name == targetDirectory.name) false else directory.deleteRecursively()
        }
        val original = skillSource(RELEASE_SKILL, FIRST_VERSION)
        stubbornStore.add(original)
        val sourceDirectory = storageDirectoryFor(RELEASE_SKILL)
        targetDirectory.mkdirs()
        targetDirectory.resolve(RENAME_FROM_FILE_NAME).writeText(
            renameMarker(committed = true, sourceDirectory.name)
        )

        val loaded = stubbornStore.load()

        assertEquals(listOf(RELEASE_SKILL), loaded.map(LocalSkillSummary::name))
        assertEquals(original, stubbornStore.read(RELEASE_SKILL)?.source)
        assertTrue(targetDirectory.exists())
        assertNull(stubbornStore.read(RENAMED_SKILL))
    }

    @Test
    fun invalidTargetDirectoryIsReclaimedOnRetry() = runBlocking {
        val original = skillSource(RETRY_SKILL, "First attempt.")
        val retry = skillSource(RETRY_SKILL, "Retry succeeds.")
        store.add(original)
        val storedDirectory = root.listFiles().orEmpty().single()
        assertTrue(storedDirectory.resolve(SKILL_FILE_NAME).delete())

        val saved = store.add(retry)

        assertEquals(RETRY_SKILL, saved.name)
        assertEquals(retry, store.read(RETRY_SKILL)?.source)
        assertFalse(saved.enabled)
        assertEquals(1, root.listFiles().orEmpty().size)
    }

    @Test
    fun activationRejectsOneSkillOverRuntimeBudget() = runBlocking {
        store.add(
            skillSource(
                OVERSIZED_SKILL,
                OVERSIZED_DESCRIPTION,
                "x".repeat(MAX_RUNTIME_SKILL_INSTRUCTION_CHARS)
            )
        )

        val error = runCatching { store.setEnabled(OVERSIZED_SKILL, true) }.exceptionOrNull()

        assertTrue(error is LocalSkillActivationException)
        assertFalse(store.load().single().enabled)
        assertTrue(store.loadEnabledManifests().isEmpty())
    }

    @Test
    fun activationRejectsSkillThatWouldExceedCombinedRuntimeBudget() = runBlocking {
        val chunk = "x".repeat(22 * 1024)
        store.add(skillSource(ALPHA_SKILL, ALPHA_DESCRIPTION, chunk))
        store.add(skillSource(BETA_SKILL, BETA_DESCRIPTION, chunk))
        store.add(skillSource(GAMMA_SKILL, "Gamma.", chunk))
        store.setEnabled(ALPHA_SKILL, true)
        store.setEnabled(BETA_SKILL, true)

        val error = runCatching { store.setEnabled(GAMMA_SKILL, true) }.exceptionOrNull()
        val runtime = store.loadEnabledManifests()

        assertTrue(error is LocalSkillActivationException)
        assertEquals(listOf(ALPHA_SKILL, BETA_SKILL), runtime.map(AgentSkillManifest::name))
        assertFalse(store.load().first { it.name == GAMMA_SKILL }.enabled)
    }

    @Test
    fun runtimeLoadPrunesStaleOverBudgetEnabledMarker() = runBlocking {
        store.add(
            skillSource(
                OVERSIZED_SKILL,
                OVERSIZED_DESCRIPTION,
                "x".repeat(MAX_RUNTIME_SKILL_INSTRUCTION_CHARS)
            )
        )
        val storedDirectory = root.listFiles().orEmpty().single()
        storedDirectory.resolve(ENABLED_FILE_NAME).writeBytes(ByteArray(0))

        val runtime = store.loadEnabledManifests()

        assertTrue(runtime.isEmpty())
        assertFalse(storedDirectory.resolve(ENABLED_FILE_NAME).exists())
        assertFalse(store.load().single().enabled)
    }

    @Test
    fun removesOnlyRequestedSkill() = runBlocking {
        store.add(skillSource(ALPHA_SKILL, ALPHA_DESCRIPTION))
        store.add(skillSource(BETA_SKILL, BETA_DESCRIPTION))
        store.setEnabled(ALPHA_SKILL, true)

        store.remove(ALPHA_SKILL)

        assertNull(store.read(ALPHA_SKILL))
        assertNotNull(store.read(BETA_SKILL))
        assertEquals(listOf(BETA_SKILL), store.load().map(LocalSkillSummary::name))
        assertTrue(store.loadEnabledManifests().isEmpty())
    }

    @Test
    fun rejectsInvalidPortableSkillBeforeWriting() {
        val invalid = """
            ---
            name: Bad--Skill
            description: Invalid portable name.
            ---
            Instructions.
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.add(invalid) }
        }
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun prunesStoredDocumentWhenManifestIdentityChanges() = runBlocking {
        store.add(skillSource(STABLE_SKILL, "Original."))
        store.setEnabled(STABLE_SKILL, true)
        val storedFile = root.listFiles().orEmpty().single().resolve(SKILL_FILE_NAME)
        storedFile.writeText(skillSource("different-name", "Tampered."))

        assertTrue(store.loadEnabledManifests().isEmpty())
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun storesMaximumLengthSupplementaryUnicodeNameWithSafeDirectoryKey() = runBlocking {
        val supplementaryLetter = "\uD801\uDC28"
        val name = supplementaryLetter.repeat(64)
        val source = skillSource(name, "Unicode boundary.")

        val added = store.add(source)

        assertEquals(name, added.name)
        assertEquals(name, store.load().single().name)
        assertNotNull(store.read(name))
        assertTrue(root.listFiles().orEmpty().single().name.encodeToByteArray().size < 255)
    }

    @Test
    fun corruptEntryDoesNotConsumeFullLibraryQuota() = runBlocking {
        repeat(LocalSkillLibraryStore.MAX_LOCAL_SKILLS) { index ->
            store.add(skillSource("skill-$index", "Entry $index."))
        }
        root.listFiles().orEmpty().first().resolve(SKILL_FILE_NAME).writeText("not a skill")

        store.add(skillSource(REPLACEMENT_SLOT, "Uses reclaimed capacity."))

        val loaded = store.load()
        assertEquals(LocalSkillLibraryStore.MAX_LOCAL_SKILLS, loaded.size)
        assertTrue(loaded.any { it.name == REPLACEMENT_SLOT })
    }

    @Test
    fun fullLibraryLoadsAsBoundedSummariesInsteadOfRetainingEverySource() = runBlocking {
        val instructions = "x".repeat(32 * 1024)
        repeat(LocalSkillLibraryStore.MAX_LOCAL_SKILLS) { index ->
            store.add(skillSource("bulk-$index", "Bulk entry $index.", instructions))
        }

        val loaded = store.load()

        assertEquals(LocalSkillLibraryStore.MAX_LOCAL_SKILLS, loaded.size)
        assertTrue(loaded.all { it.description.startsWith("Bulk entry") })
    }

    @Test
    fun archiveScriptsTravelWithTheSkillAndCountTowardItsBundle() = runBlocking {
        val source = skillSource(RELEASE_SKILL, FIRST_VERSION)
        val scripts = mapOf(
            "scripts/index.html" to "<script></script>".toByteArray(),
            "scripts/lib/app.js" to "1".toByteArray(),
        )
        store.add(source, scripts = scripts)

        val files = requireNotNull(store.readBundleFiles(RELEASE_SKILL))
        assertEquals(setOf(SKILL_FILE_NAME, "scripts/index.html", "scripts/lib/app.js"), files.keys)
        assertEquals(source, files.getValue(SKILL_FILE_NAME).decodeToString())

        // A later plain SKILL.md edit keeps the scripts, but the bundle digest changes with it.
        val before = activeSkillBundleDigest(files)
        val opened = requireNotNull(store.read(RELEASE_SKILL))
        store.replace(RELEASE_SKILL, opened.sourceDigest, skillSource(RELEASE_SKILL, SECOND_VERSION))
        val edited = requireNotNull(store.readBundleFiles(RELEASE_SKILL))
        assertEquals(files.keys, edited.keys)
        assertNotEquals(before, activeSkillBundleDigest(edited))

        // Rename carries the scripts; re-importing with an empty set removes them.
        val reopened = requireNotNull(store.read(RELEASE_SKILL))
        store.rename(RELEASE_SKILL, reopened.sourceDigest, skillSource(RENAMED_SKILL, SECOND_VERSION))
        assertNull(store.readBundleFiles(RELEASE_SKILL))
        assertEquals(files.keys, requireNotNull(store.readBundleFiles(RENAMED_SKILL)).keys)
        store.add(skillSource(RENAMED_SKILL, SECOND_VERSION), replaceExisting = true, scripts = emptyMap())
        assertEquals(setOf(SKILL_FILE_NAME), requireNotNull(store.readBundleFiles(RENAMED_SKILL)).keys)

        store.remove(RENAMED_SKILL)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun storageDirectoryFor(name: String) =
        root.resolve("skill-${sha256Hex(name.encodeToByteArray())}")

    private fun renameMarker(committed: Boolean, vararg sourceDirectoryNames: String): String =
        buildString {
            append(if (committed) "committed" else "pending")
            sourceDirectoryNames.forEach { sourceName ->
                append('\n')
                append(sourceName)
            }
        }

    private fun skillSource(
        name: String,
        description: String,
        instructions: String = "Keep imported content inert until explicitly used."
    ): String = """
        ---
        name: $name
        description: $description
        ---
        # Instructions
        $instructions
    """.trimIndent()

    private companion object {
        const val RELEASE_SKILL = "release-checklist"
        const val RETRY_SKILL = "retry-skill"
        const val RENAMED_SKILL = "renamed-skill"
        const val RENAMED_AGAIN_SKILL = "renamed-again-skill"
        const val ALPHA_SKILL = "alpha-skill"
        const val BETA_SKILL = "beta-skill"
        const val GAMMA_SKILL = "gamma-skill"
        const val TOGGLE_SKILL = "toggle-skill"
        const val OVERSIZED_SKILL = "oversized-skill"
        const val STABLE_SKILL = "stable-name"
        const val REPLACEMENT_SLOT = "replacement-slot"
        const val SKILL_FILE_NAME = "SKILL.md"
        const val ENABLED_FILE_NAME = ".enabled"
        const val RENAME_FROM_FILE_NAME = ".rename-from"
        const val FIRST_VERSION = "First version."
        const val SECOND_VERSION = "Second version."
        const val ALPHA_DESCRIPTION = "Alpha."
        const val BETA_DESCRIPTION = "Beta."
        const val OVERSIZED_DESCRIPTION = "Too large for runtime."
    }
}
