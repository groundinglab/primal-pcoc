package net.primal.android.pcoc.enroll

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Test

class FileEnrolledPcocStoreTest {

    @Test
    fun `delete removes only the object file`() {
        val root = kotlin.io.path.createTempDirectory("pcoc-objects").toFile()
        val store = FileEnrolledPcocStore(root)
        val account = "ab".repeat(32)
        val other = "cd".repeat(32)
        store.write(account, byteArrayOf(1, 2, 3))
        store.write(other, byteArrayOf(9))
        File(root, "nsec").writeText("do-not-touch")
        val outside = File(root.parentFile, "credentials.json")
        outside.writeText("account-secret")

        store.delete(account)
        store.delete("../credentials")
        store.delete(outside.name)

        store.contains(account) shouldBe false
        store.read(other).contentEquals(byteArrayOf(9)) shouldBe true
        File(root, "nsec").readText() shouldBe "do-not-touch"
        outside.readText() shouldBe "account-secret"
    }

    @Test
    fun `the note is the file name and the date saved`() {
        val root = kotlin.io.path.createTempDirectory("pcoc-objects").toFile()
        val store = FileEnrolledPcocStore(root)
        val account = "ab".repeat(32)
        store.write(account, byteArrayOf(1, 2, 3), "report.pcoc", 1_759_622_400_000L)

        store.note(account) shouldBe EnrolledNote("report.pcoc", 1_759_622_400_000L)
        store.read(account).contentEquals(byteArrayOf(1, 2, 3)) shouldBe true
        File(root, "${account}.note").readText().contains(account) shouldBe false

        store.delete(account)
        store.note(account) shouldBe null
        File(root, "${account}.note").exists() shouldBe false
    }

    @Test
    fun `write rejects an id that is not 64 hex`() {
        val root = kotlin.io.path.createTempDirectory("pcoc-objects").toFile()
        val store = FileEnrolledPcocStore(root)

        shouldThrow<IllegalArgumentException> {
            store.write("nsec1notauserid", byteArrayOf(1))
        }
        root.list().orEmpty().none { it.contains("nsec") } shouldBe true
    }
}
