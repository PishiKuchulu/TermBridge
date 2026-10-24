package com.pishi.termbridge.domain

/**
 * Prompt 10 jump-host chain model. A chain is an ORDERED list of
 * jump host ids for one target host: the first id is contacted
 * first (directly, or via the configured proxy), each later hop
 * is reached through the previous hop's direct-tcpip channel, and
 * the target is reached through the last hop. Order is the whole
 * point — a chain stored out of order is a different route — so
 * the repository persists sortOrder and returns hops in that order.
 *
 * No credential appears here: hop credentials are loaded from
 * HostRepository at connect time only, per hop, and wiped after
 * the chain connects (see SshTransport.connectChain).
 */
data class JumpChain(
    val targetHostId: Long,
    /** Jump host ids in connection order. Empty = no jump hosts. */
    val jumpHostIds: List<Long>
)

/** Pure chain validation — structural only, JVM-testable. The
 *  repository additionally checks that every referenced host exists. */
object JumpChainValidator {
    const val MAX_HOPS = 5

    fun validate(chain: JumpChain): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val hops = chain.jumpHostIds
        if (hops.isEmpty()) {
            issues += ValidationIssue("chain", "A jump chain needs at least one jump host.")
        }
        if (hops.size > MAX_HOPS) {
            issues += ValidationIssue(
                "chain",
                "A jump chain may have at most $MAX_HOPS hops."
            )
        }
        if (hops.toSet().size != hops.size) {
            issues += ValidationIssue("chain", "A host may appear only once in a jump chain.")
        }
        if (chain.targetHostId in hops) {
            issues += ValidationIssue(
                "chain",
                "The target host cannot also be one of its own jump hosts."
            )
        }
        return issues
    }
}
