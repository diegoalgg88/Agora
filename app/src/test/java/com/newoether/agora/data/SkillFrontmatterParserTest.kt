package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillFrontmatterParserTest {

    @Test
    fun validFrontmatterParsesIdDescriptionAndBody() {
        val result = SkillFrontmatterParser.parse(
            "---\nname: pdf\ndescription: Reads PDF files.\n---\n\nDo the thing.\n",
        )
        val ok = result as SkillFrontmatterParser.Result.Ok
        assertEquals("pdf", ok.id)
        assertEquals("Reads PDF files.", ok.description)
        assertEquals("Do the thing.", ok.body)
    }

    @Test
    fun missingFrontmatterFailsClosed() {
        val result = SkillFrontmatterParser.parse("# Just markdown\nno frontmatter here")
        assertTrue(result is SkillFrontmatterParser.Result.Err)
    }

    @Test
    fun unclosedFrontmatterFailsClosed() {
        val result = SkillFrontmatterParser.parse("---\nname: pdf\ndescription: x\n")
        assertTrue(result is SkillFrontmatterParser.Result.Err)
    }

    @Test
    fun missingNameFailsClosed() {
        val result = SkillFrontmatterParser.parse("---\ndescription: No name here.\n---\nbody")
        assertTrue(result is SkillFrontmatterParser.Result.Err)
    }

    @Test
    fun missingDescriptionFailsClosed() {
        val result = SkillFrontmatterParser.parse("---\nname: pdf\n---\nbody")
        assertTrue(result is SkillFrontmatterParser.Result.Err)
    }

    @Test
    fun invalidIdsAreRejected() {
        listOf("-pdf", "pdf-", "p--df", "PDF", "my skill", "skill_name").forEach { badId ->
            val result = SkillFrontmatterParser.parse("---\nname: $badId\ndescription: d\n---\nbody")
            assertTrue("expected Err for id '$badId'", result is SkillFrontmatterParser.Result.Err)
        }
    }

    @Test
    fun oversizedDescriptionIsRejected() {
        val longDescription = "x".repeat(1025)
        val result = SkillFrontmatterParser.parse("---\nname: pdf\ndescription: $longDescription\n---\nbody")
        assertTrue(result is SkillFrontmatterParser.Result.Err)
    }

    @Test
    fun displayNameTitleCasesHyphenatedId() {
        assertEquals("Create Skill", SkillFrontmatterParser.displayName("create-skill"))
    }
}
