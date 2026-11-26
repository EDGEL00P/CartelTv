package com.carteltv.tv.update

import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Sideload self-updater using `PackageInstaller.Session` with embedded `.dm` ART profile
 * handoff, signing-certificate continuity checks, and strict anti-downgrade enforcement.
 */
object ApkInstaller {

    /**
     * Pure compatibility verdict so callers and JVM tests share exact package, version,
     * and signer-set rules.
     */
    fun isCompatibleSpec(
        installedPackage: String,
        candidatePackage: String,
        installedVersionCode: Long,
        candidateVersionCode: Long,
        installedSigners: Set<String>,
        candidateSigners: Set<String>,
    ): Boolean {
        if (candidatePackage != installedPackage) return false
        if (candidateVersionCode <= installedVersionCode) return false
        if (installedSigners.isEmpty()) return false
        return installedSigners == candidateSigners
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

}
