package io.github.desodre.adbutils.model

/** Cumulative progress emitted after a SYNC data frame is transferred. */
public data class SyncTransferProgress(public val bytesTransferred: Long) {
    init {
        require(bytesTransferred >= 0) { "Transferred byte count cannot be negative" }
    }
}

/** Summary returned by file-based SYNC helpers. */
public data class SyncTransferResult(public val bytesTransferred: Long) {
    init {
        require(bytesTransferred >= 0) { "Transferred byte count cannot be negative" }
    }
}
