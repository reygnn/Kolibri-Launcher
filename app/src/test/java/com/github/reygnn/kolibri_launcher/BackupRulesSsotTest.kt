package com.github.reygnn.kolibri_launcher

import com.github.reygnn.kolibri_launcher.core.AppConstants
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Single-source-of-truth drift guard for the Auto-Backup rules (AUDIT-4 #2).
 *
 * The backup-rule XMLs are parsed statically by the Android backup framework,
 * so their `path` attributes cannot reference [AppConstants] directly. This
 * test enforces the link the other way: it fails if the XML drifts from the
 * constant.
 *
 * Guarantees, per backup section:
 *  - the whole `datastore/` directory is included, so the settings DataStore
 *    (and any future store) is backed up by default — the opposite of the
 *    over-narrow allowlist that caused #2;
 *  - the ACRA consent file is excluded, and its name matches
 *    [AppConstants.CONSENT_DATASTORE_NAME], so consent never travels to a new
 *    device (privacy-by-default, Rule 8);
 *  - the consent file is never (re-)included.
 *
 * Pure JVM test: reads the XML from the module source tree. Gradle runs unit
 * tests with the working directory set to the module root (`app/`).
 */
class BackupRulesSsotTest {

    private val datastoreDir = "datastore/"
    private val consentFilePath =
        "datastore/${AppConstants.CONSENT_DATASTORE_NAME}.preferences_pb"

    private data class Rule(val tag: String, val domain: String, val path: String)

    /** file -> the section elements whose file-domain rules must match. */
    private val ruleFiles = mapOf(
        "src/main/res/xml/data_extraction_rules.xml" to listOf("cloud-backup", "device-transfer"),
        "src/main/res/xml/backup_rules.xml" to listOf("full-backup-content"),
    )

    private fun sections(relPath: String, sectionTags: List<String>): Map<String, List<Rule>> {
        val file = File(relPath)
        assertTrue(
            "Backup-rules XML not found at ${file.absolutePath} — the test working " +
                "directory must be the :app module root.",
            file.exists()
        )
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return sectionTags.associateWith { tag ->
            val nodes = doc.getElementsByTagName(tag)
            assertTrue("$relPath is missing the <$tag> section.", nodes.length == 1)
            fileRulesOf(nodes.item(0) as Element)
        }
    }

    /** Direct <include>/<exclude> children with domain="file". */
    private fun fileRulesOf(parent: Element): List<Rule> {
        val out = mutableListOf<Rule>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && (node.tagName == "include" || node.tagName == "exclude")) {
                out += Rule(node.tagName, node.getAttribute("domain"), node.getAttribute("path"))
            }
        }
        return out.filter { it.domain == "file" }
    }

    @Test
    fun `every backup section includes the whole datastore directory`() {
        ruleFiles.forEach { (path, sectionTags) ->
            sections(path, sectionTags).forEach { (section, rules) ->
                assertTrue(
                    "$path <$section> must <include domain=\"file\" path=\"$datastoreDir\"> " +
                        "so the settings DataStore (and future stores) are backed up.",
                    rules.any { it.tag == "include" && it.path == datastoreDir }
                )
            }
        }
    }

    @Test
    fun `every backup section excludes the consent store by its constant name`() {
        ruleFiles.forEach { (path, sectionTags) ->
            sections(path, sectionTags).forEach { (section, rules) ->
                assertTrue(
                    "$path <$section> must <exclude domain=\"file\" path=\"$consentFilePath\"> " +
                        "so ACRA consent never travels (must match " +
                        "AppConstants.CONSENT_DATASTORE_NAME).",
                    rules.any { it.tag == "exclude" && it.path == consentFilePath }
                )
            }
        }
    }

    @Test
    fun `no backup section re-includes the consent store`() {
        ruleFiles.forEach { (path, sectionTags) ->
            sections(path, sectionTags).forEach { (section, rules) ->
                assertTrue(
                    "$path <$section> must not <include> the consent file $consentFilePath.",
                    rules.none { it.tag == "include" && it.path == consentFilePath }
                )
            }
        }
    }
}
