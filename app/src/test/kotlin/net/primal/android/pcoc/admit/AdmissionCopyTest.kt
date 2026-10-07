package net.primal.android.pcoc.admit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import net.primal.android.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdmissionCopyTest {

    @Test
    fun `post outcomes use plain words and do not state a policy`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val delivered = context.getString(R.string.pcoc_admit_delivered)
        val badSignature = context.getString(R.string.pcoc_admit_bad_signature)
        val failed = context.getString(R.string.pcoc_admit_failed)

        delivered shouldBe "Delivered. The signature was made as the correct signatory. " +
            "Look at the website page for welcome, refused, or progress."
        badSignature shouldBe "The signature did not match. This is not a refusal."
        failed shouldBe "The sign-in was not delivered. This is not a refusal."

        delivered.shouldContain("correct signatory")
        delivered.shouldContain("website page")
        listOf(delivered, badSignature, failed).forEach { text ->
            text.shouldNotContain("decision:")
            text.shouldNotContain("verifier_ran")
            text.shouldNotContain("provisional")
            text.shouldNotContain("checking")
        }
        badSignature.shouldContain("not a refusal")
        failed.shouldContain("not a refusal")
    }
}
