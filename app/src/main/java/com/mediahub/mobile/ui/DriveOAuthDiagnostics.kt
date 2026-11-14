package com.mediahub.mobile.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Safe, local OAuth diagnostics for the exact installed APK. Never exposes tokens,
 * password data, or private signing keys: only the certificate's public SHA-1.
 */
internal data class DriveOauthIdentity(val packageName: String, val signingSha1: String) {
    fun summary() = "Пакет: $packageName\nSHA-1: $signingSha1"
}

internal object DriveOAuthDiagnostics {
    fun sha1Fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    @Suppress("DEPRECATION")
    fun read(context: Context): DriveOauthIdentity {
        val name = context.packageName
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val info = context.packageManager.getPackageInfo(name, flags)
            val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                info.signingInfo?.apkContentsSigners?.firstOrNull()
            else info.signatures?.firstOrNull()
            DriveOauthIdentity(
                name,
                signature?.let { sha1Fingerprint(it.toByteArray()) } ?: "Не определён"
            )
        } catch (_: Exception) {
            DriveOauthIdentity(name, "Не удалось получить сертификат")
        }
    }
}
