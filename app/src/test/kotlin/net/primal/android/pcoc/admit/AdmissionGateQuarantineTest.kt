package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import net.primal.android.settings.groundinglab.AdmissionGateClient
import net.primal.android.settings.home.PrimalSettingsSection
import org.junit.Test

class AdmissionGateQuarantineTest {

    @Test
    fun `settings does not open the gate-direct sign-in`() {
        PrimalSettingsSection.entries.map { it.name }.none { it == "Groundinglab" } shouldBe true
        AdmissionGateClient.SIGN_IN_URL shouldBe "https://admission.pcoc.app/v1/sign-in"
    }
}
