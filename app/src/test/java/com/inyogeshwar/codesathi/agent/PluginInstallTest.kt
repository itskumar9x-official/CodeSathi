package com.inyogeshwar.codesathi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the Phase 7, Step B4 ports: the GitHub
 * reference parser, the plugin manifest / marketplace parsers, the
 * plugin-command conversion, the skill front-matter parser and the
 * Termux MCP port picker. No Android framework, no network, no
 * files — the install flows themselves (GitHub download, zip read,
 * Termux run) cannot run in a plain JUnit test and are not covered
 * here.
 */
class PluginInstallTest {

    @Test
    fun repoRefParsesGitHubTreeUrl() {
        val ref = RepoRef.parse("https://github.com/anthropics/skills/tree/main/skills/pdf")
        assertEquals("anthropics", ref!!.owner)
        assertEquals("skills", ref.repo)
        assertEquals("skills/pdf", ref.path)
        assertEquals("main", ref.ref)
    }

    @Test
    fun repoRefParsesBlobSkillUrl() {
        val ref = RepoRef.parse("https://github.com/owner/repo/blob/main/skills/foo/SKILL.md")
        assertEquals("skills/foo", ref!!.path)
        assertEquals("main", ref.ref)
    }

    @Test
    fun repoRefParsesSkillsShAndShorthand() {
        val fromSkillsSh = RepoRef.parse("https://skills.sh/anthropics/skills")
        assertEquals("anthropics", fromSkillsSh!!.owner)
        assertEquals("skills", fromSkillsSh.repo)
        assertEquals("", fromSkillsSh.path)
        val shorthand = RepoRef.parse("owner/repo/path@dev")
        assertEquals("owner", shorthand!!.owner)
        assertEquals("repo", shorthand.repo)
        assertEquals("path", shorthand.path)
        assertEquals("dev", shorthand.ref)
    }

    @Test
    fun repoRefRejectsTooShortInput() {
        assertNull(RepoRef.parse("not-a-repo"))
        assertNull(RepoRef.parse(""))
    }

    @Test
    fun pluginIdSlugsNames() {
        assertEquals("my-plugin", pluginIdFor("My Plugin!"))
        assertEquals("plugin", pluginIdFor("!!!"))
    }

    @Test
    fun pluginManifestParses() {
        val manifest = parsePluginManifest(
            """{"name": "My Plugin", "version": "1.2.0", "description": "Does things."}""".toByteArray()
        )
        assertEquals("My Plugin", manifest.name)
        assertEquals("1.2.0", manifest.version)
        assertEquals("Does things.", manifest.description)
        assertNull(manifest.mcpServersJson)
    }

    @Test
    fun pluginManifestWithoutNameThrows() {
        try {
            parsePluginManifest("""{"version": "1.0.0"}""".toByteArray())
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("no name"))
        }
    }

    @Test
    fun marketplaceKeepsLocalPathsOnly() {
        val body = """
            {"plugins": [
              {"name": "foo", "description": "Foo.", "source": "./plugins/foo"},
              {"name": "remote", "description": "Remote.", "source": {"url": "https://example.com/x"}},
              {"name": "bar", "description": "Bar.", "source": "plugins/bar"}
            ]}
        """.trimIndent().toByteArray()
        val items = parseMarketplaceJson(body, "market")
        assertEquals(2, items.size)
        assertEquals("foo", items[0].name)
        assertEquals("market/plugins/foo", items[0].path)
        assertEquals("bar", items[1].name)
        assertEquals("market/plugins/bar", items[1].path)
    }

    @Test
    fun pluginCommandConvertsArgumentsPlaceholder() {
        val draft = convertPluginCommand(
            "review",
            "---\ndescription: Review things.\n---\n\nReview \$ARGUMENTS now, first \$1 then done."
        )
        assertEquals("review", draft.name)
        assertEquals("Review things.", draft.description)
        assertTrue(draft.body.contains("{input}"))
        assertFalse(draft.body.contains("\$ARGUMENTS"))
        assertFalse(draft.body.contains("\$1"))
    }

    @Test
    fun skillFrontMatterParses() {
        val (name, description, instructions) = parseSkillFrontMatter(
            "---\nname: PDF Reader\ndescription: Reads PDFs.\n---\n\nRead the PDF carefully.",
            "pdf-reader"
        )
        assertEquals("PDF Reader", name)
        assertEquals("Reads PDFs.", description)
        assertEquals("Read the PDF carefully.", instructions)
    }

    @Test
    fun skillFrontMatterFallsBackWithoutFrontMatter() {
        val (name, description, instructions) = parseSkillFrontMatter(
            "Just do the thing.\nSecond line.",
            "do-thing"
        )
        assertEquals("do-thing", name)
        assertEquals("Just do the thing.", description)
        assertTrue(instructions.startsWith("Just do the thing."))
    }

    @Test
    fun termuxMcpPortIsStableAndInRange() {
        val command = "npx -y my-mcp-server"
        assertEquals(TermuxMcp.port(command), TermuxMcp.port(command))
        val port = TermuxMcp.port(command)
        assertTrue(port in 18100..18899)
    }
}
