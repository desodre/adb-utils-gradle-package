package org.desodre.droidscope.model

data class InstallOptions(
    val replace: Boolean = true,
    val grantRuntimePermissions: Boolean = false,
    val allowTestPackages: Boolean = false,
    val allowDowngrade: Boolean = false,
)

data class InstallResult(val success: Boolean, val message: String)
