package dev.brainrotstopper.feature.rules

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppsTest {

    @Test
    fun `excluded packages are not offered`() {
        val entries = listOf(
            InstalledApp("com.instagram.android", "Instagram"),
            InstalledApp("dev.brainrotstopper", "Brainrot Stopper"),
        )

        val selected = AppCatalogPolicy.select(entries, setOf("dev.brainrotstopper"))

        assertEquals(listOf("com.instagram.android"), selected.map { it.packageName })
    }

    @Test
    fun `duplicate packages with several launcher activities appear once`() {
        val entries = listOf(
            InstalledApp("com.google.android.youtube", "YouTube"),
            InstalledApp("com.google.android.youtube", "YouTube"),
            InstalledApp("com.zhiliaoapp.musically", "TikTok"),
        )

        val selected = AppCatalogPolicy.select(entries, emptySet())

        assertEquals(2, selected.size)
        assertEquals(
            listOf("com.zhiliaoapp.musically", "com.google.android.youtube"),
            selected.map { it.packageName },
        )
    }

    @Test
    fun `labels sort case-insensitively`() {
        val entries = listOf(
            InstalledApp("z.pkg", "zebra"),
            InstalledApp("a.pkg", "Apple"),
            InstalledApp("b.pkg", "banana"),
        )

        val selected = AppCatalogPolicy.select(entries, emptySet())

        assertEquals(listOf("Apple", "banana", "zebra"), selected.map { it.label })
    }

    @Test
    fun `a package with only excluded entries yields nothing`() {
        val entries = listOf(InstalledApp("dev.brainrotstopper", "Brainrot Stopper"))

        assertEquals(emptyList<InstalledApp>(), AppCatalogPolicy.select(entries, setOf("dev.brainrotstopper")))
    }
}
