package io.github.desodre.adbutils.model

data class InstallOptions(
    val replace: Boolean = true,
    val grantRuntimePermissions: Boolean = false,
    val allowTestPackages: Boolean = false,
    val allowDowngrade: Boolean = false,
)

/** Successful package installation response. Failures throw PackageOperationException. */
data class InstallResult(val message: String)
