package io.github.desodre.adbutils.model

public data class RemoteFileStat(public val mode: Int, public val size: Long, public val modifiedAtEpochSeconds: Long)
public data class RemoteFile(public val name: String, public val stat: RemoteFileStat)
