package com.mediahub.mobile.domain

enum class TransferKind { COPY, MOVE }
enum class Verification { NOT_CHECKED, SIZE_ONLY, CHECKSUM_MATCHED, BYTE_COMPARE_MATCHED }
enum class ConflictAction { ASK_USER, SKIP, REPLACE_WITH_APPROVAL }

/**
 * A completed upload alone is never grounds to delete a source.
 * Size-only checks are insufficient: the actual bytes must be verified.
 */
object TransferPolicy {
    fun mayDeleteSource(
        operation: TransferKind,
        verification: Verification,
        explicitApproval: Boolean
    ): Boolean = operation == TransferKind.MOVE &&
        explicitApproval &&
        (verification == Verification.CHECKSUM_MATCHED ||
            verification == Verification.BYTE_COMPARE_MATCHED)

    fun resolveConflict(destinationExists: Boolean, replaceApproved: Boolean): ConflictAction =
        when {
            !destinationExists -> ConflictAction.SKIP
            replaceApproved -> ConflictAction.REPLACE_WITH_APPROVAL
            else -> ConflictAction.ASK_USER
        }
}
