package io.github.desodre.adbutils.model

public data class InstallOptions(
    public val replace: Boolean = true,
    public val grantRuntimePermissions: Boolean = false,
    public val allowTestPackages: Boolean = false,
    public val allowDowngrade: Boolean = false,
)

/** Successful package installation response. Failures throw PackageOperationException. */
public data class InstallResult(public val message: String)
