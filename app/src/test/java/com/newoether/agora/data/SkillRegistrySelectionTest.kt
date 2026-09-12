package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillRegistrySelectionTest {

    private val tree = setOf(
        "skills/brainstorming/SKILL.md",
        "skills/writing-plans/SKILL.md",
        "skills/debugging/SKILL.md",
        "skills/pdf/SKILL.md",
        "skills/pdf/scripts/run.py",
        "other/nested/skills/deep/SKILL.md",
    )

    @Test
    fun allowlistSelectsOnlyListedPathsThatContainSkillMd() {
        val selected = SkillRegistry.selectSkillDirs(
            treePaths = tree,
            allowlist = listOf("skills/brainstorming", "skills/writing-plans", "skills/missing"),
            manifestPaths = null,
            root = "skills",
        )
        assertEquals(listOf("skills/brainstorming", "skills/writing-plans"), selected)
    }

    @Test
    fun folderScanKeepsOnlyDirectChildrenOfRoot() {
        val selected = SkillRegistry.selectSkillDirs(
            treePaths = tree,
            allowlist = null,
            manifestPaths = null,
            root = "skills",
        )
        assertEquals(
            listOf("skills/brainstorming", "skills/debugging", "skills/pdf", "skills/writing-plans"),
            selected.sorted(),
        )
    }

    @Test
    fun excludeDropsNamedFoldersAfterSelection() {
        val selected = SkillRegistry.selectSkillDirs(
            treePaths = tree,
            allowlist = null,
            manifestPaths = null,
            root = "skills",
            exclude = setOf("debugging"),
        )
        assertEquals(
            listOf("skills/brainstorming", "skills/pdf", "skills/writing-plans"),
            selected.sorted(),
        )
    }

    @Test
    fun curatedMarketplaceAllowlistsResolveAgainstRealRepoShape() {
        val superpowers = curatedSkillMarketplaces.first { it.repo == "superpowers" }
        val selected = SkillRegistry.selectSkillDirs(
            treePaths = tree,
            allowlist = superpowers.skills,
            manifestPaths = null,
            root = superpowers.root,
        )
        assertEquals(listOf("skills/brainstorming", "skills/writing-plans"), selected)
    }
}
