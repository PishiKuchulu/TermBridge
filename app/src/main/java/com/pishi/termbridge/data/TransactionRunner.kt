package com.pishi.termbridge.data

/**
 * Security fix 16 (M3, L4): a single-database transaction
 * boundary for operations that span several repositories/DAOs.
 * Room transactions are per-database, and every repository in
 * this app shares one database — but repositories do not hold
 * the database, so the boundary is injected instead of reached
 * for. Production passes the real database's withTransaction
 * (see TermBridgeApplication); tests pass a direct runner or a
 * recording/rollback runner. The default runs the block
 * directly, so existing callers and tests keep compiling and
 * keep the old sequential behaviour unless a runner is given.
 *
 * The contract a runner must honour: if [block] throws, NONE
 * of its writes are visible afterwards. Room's withTransaction
 * provides this; a test double that merely runs the block does
 * not, and tests using such a double must say so.
 */
fun interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}
