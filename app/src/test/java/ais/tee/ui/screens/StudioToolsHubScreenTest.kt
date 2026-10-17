package ais.tee.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class StudioToolsHubScreenTest {
    @Test
    fun skillsRemainTheFirstStudioToolsSection() {
        assertEquals(StudioToolsSection.SKILLS, StudioToolsSection.entries.first())
    }
    @Test
    fun agentsAreAnOptionalSectionOfTheExistingToolsHub() {
        assertEquals(StudioToolsSection.AGENTS, StudioToolsSection.entries[2])
    }
}
