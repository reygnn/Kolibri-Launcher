package com.github.reygnn.nyx_launcher.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [normalizeFolderTitle] — the folder-title input normalization
 * applied in MainActivity.applyFolderTitleEdit (A2). A whitespace-only edit must
 * collapse to "" (the localized-default-hint state), and surrounding whitespace is
 * stripped, but a real title is never rejected — empty is a legitimate title.
 */
class FolderTitleTest {

    @Test fun a_whitespace_only_title_collapses_to_empty() {
        assertThat(normalizeFolderTitle("   ")).isEmpty()
        assertThat(normalizeFolderTitle("\t \n")).isEmpty()
    }

    @Test fun surrounding_whitespace_is_stripped() {
        assertThat(normalizeFolderTitle("  Games  ")).isEqualTo("Games")
    }

    @Test fun an_already_clean_title_is_unchanged() {
        assertThat(normalizeFolderTitle("Games")).isEqualTo("Games")
        assertThat(normalizeFolderTitle("")).isEmpty()
    }

    @Test fun interior_whitespace_is_preserved() {
        // Only the ends are trimmed — a multi-word name keeps its internal spacing.
        assertThat(normalizeFolderTitle("  My Games Folder ")).isEqualTo("My Games Folder")
    }
}
