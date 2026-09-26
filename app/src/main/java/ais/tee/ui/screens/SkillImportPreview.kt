package ais.tee.ui.screens

import ais.tee.data.skills.AgentSkillManifest
import ais.tee.data.skills.AgentSkillManifestParser
import ais.tee.data.skills.AgentSkillValidationIssue
import ais.tee.data.skills.ACTIVE_SKILL_ENTRY_FILE
import ais.tee.data.skills.ActiveSkillDeclaration
import ais.tee.data.skills.SkillArchive
import ais.tee.data.skills.activeSkillBundleDigest
import ais.tee.data.skills.activeSkillDeclaration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAX_SOURCE_PREVIEW_CHARS = 24 * 1024
private val PORTABLE_SKILL_FILENAMES = setOf("SKILL.md", "skill.md")

internal fun boundedSkillSourceForDisplay(source: String): String {
    if (source.length <= MAX_SOURCE_PREVIEW_CHARS) return source
    return source.take(MAX_SOURCE_PREVIEW_CHARS) +
        "\n\n… source preview truncated …"
}

internal data class SkillImportPreview(
    val displayName: String,
    val source: String,
    val manifest: AgentSkillManifest?,
    val issues: List<AgentSkillValidationIssue>,
    /** Set only for an archive import while active skills are on. */
    val archive: SkillArchive? = null,
    val activeRuntime: ActiveSkillDeclaration? = null,
    /** Problems that keep the skill from running; they never block importing the instructions. */
    val activeRuntimeIssues: List<String> = emptyList(),
    val bundleDigest: String? = null,
) {
    val isValid: Boolean
        get() = manifest != null && issues.isEmpty()

    fun sourceForDisplay(): String = boundedSkillSourceForDisplay(source)
}

internal suspend fun buildSkillImportPreview(
    displayName: String,
    source: String,
    validateFilename: Boolean = true,
    archive: SkillArchive? = null
): SkillImportPreview = withContext(Dispatchers.Default) {
    val parsed = AgentSkillManifestParser.parse(source)
    val issues = parsed.issues.toMutableList()
    if (validateFilename && displayName !in PORTABLE_SKILL_FILENAMES) {
        issues += AgentSkillValidationIssue(
            field = null,
            message = "Portable skills must be named SKILL.md (skill.md is also accepted)."
        )
    }
    val declaration = parsed.manifest?.let(::activeSkillDeclaration)
    val runtimeIssues = buildList {
        declaration?.issues?.forEach { add(it.message) }
        if (declaration?.declaration != null && archive?.scripts?.containsKey(ACTIVE_SKILL_ENTRY_FILE) != true) {
            add("The skill declares a runtime but has no $ACTIVE_SKILL_ENTRY_FILE.")
        }
        if (declaration?.declaration?.networkOrigins?.isNotEmpty() == true) {
            add("Network access is not available in this version; this skill cannot run.")
        }
    }
    SkillImportPreview(
        displayName = displayName,
        source = source,
        manifest = parsed.manifest,
        issues = issues,
        archive = archive,
        activeRuntime = declaration?.declaration,
        activeRuntimeIssues = runtimeIssues,
        bundleDigest = archive?.let {
            activeSkillBundleDigest(it.scripts + (ACTIVE_SKILL_MANIFEST_FILE to source.encodeToByteArray()))
        },
    )
}

internal const val ACTIVE_SKILL_MANIFEST_FILE = "SKILL.md"
