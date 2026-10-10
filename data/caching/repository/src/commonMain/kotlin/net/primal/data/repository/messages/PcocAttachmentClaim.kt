package net.primal.data.repository.messages

import net.primal.domain.nostr.NostrEvent

/**
 * A direct message the PCOC enrol path takes.
 * Returning true keeps that event out of the message store.
 * A null claim leaves every message on the existing path.
 */
fun interface PcocAttachmentClaim {
    fun claim(userId: String, event: NostrEvent): Boolean
}
