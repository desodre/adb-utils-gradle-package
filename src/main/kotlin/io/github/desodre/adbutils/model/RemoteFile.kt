package io.github.desodre.adbutils.model

data class RemoteFileStat(val mode: Int, val size: Long, val modifiedAtEpochSeconds: Long)
data class RemoteFile(val name: String, val stat: RemoteFileStat)
