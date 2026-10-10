package net.primal.android.pcoc

import io.kotest.matchers.shouldBe
import java.io.File
import net.primal.android.pcoc.enroll.FileEnrolledPcocStore
import org.junit.Test

class PcocAccountMarkTest {

    @Test
    fun `the orange check is only on the active account while a pcoc is saved`() {
        pcocMarkVisible(isOwnProfile = false, hasSavedPcoc = true) shouldBe false
        pcocMarkVisible(isOwnProfile = true, hasSavedPcoc = false) shouldBe false

        val root = kotlin.io.path.createTempDirectory("pcoc-mark").toFile()
        val store = FileEnrolledPcocStore(root)
        val account = "ab".repeat(32)
        pcocMarkVisible(isOwnProfile = true, hasSavedPcoc = store.contains(account)) shouldBe false

        store.write(account, byteArrayOf(1), "hike.cbor", 1L)
        pcocMarkVisible(isOwnProfile = true, hasSavedPcoc = store.contains(account)) shouldBe true
        pcocMarkVisible(isOwnProfile = false, hasSavedPcoc = store.contains(account)) shouldBe false

        store.delete(account)
        store.contains(account) shouldBe false
        pcocMarkVisible(isOwnProfile = true, hasSavedPcoc = store.contains(account)) shouldBe false
    }

    @Test
    fun `own profile and the pcoc menu place the check next to the account name`() {
        source("src/main/kotlin/net/primal/android/profile/details/ui/ProfileDetailsHeader.kt").let { header ->
            header.contains("PcocOrangeCheck") shouldBe true
            header.contains("showPcocMark") shouldBe true
        }
        source("src/main/kotlin/net/primal/android/profile/details/ProfileDetailsScreen.kt").let { screen ->
            screen.contains("pcocMarkVisible") shouldBe true
            screen.contains("isActiveUser == true") shouldBe true
        }
        source("src/main/kotlin/net/primal/android/pcoc/PcocScreen.kt").contains("PcocAccountNameRow") shouldBe true
        source("src/main/kotlin/net/primal/android/pcoc/PcocAccountMarkViewModel.kt").contains("http") shouldBe false
    }

    private fun source(path: String): String {
        val module = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        return File(module, path).readText()
    }
}
