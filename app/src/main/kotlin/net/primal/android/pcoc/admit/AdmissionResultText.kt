package net.primal.android.pcoc.admit

/**
 * Text for a parsed service body only. Local refusals must not use this.
 * `verifier_ran` is the boolean from the body, or absent when the field was not a boolean.
 */
internal fun gateResultText(decision: AdmissionDecision, verifierRan: Boolean?): String {
    val word = when (decision) {
        AdmissionDecision.Accept -> ACCEPT
        AdmissionDecision.Deny -> DENY
    }
    val ran = when (verifierRan) {
        true -> TRUE
        false -> FALSE
        null -> ABSENT
    }
    return "The service said:\ndecision: $word\nverifier_ran: $ran"
}

private const val ACCEPT = "accept"
private const val DENY = "deny"
private const val TRUE = "true"
private const val FALSE = "false"
private const val ABSENT = "not in the response"
