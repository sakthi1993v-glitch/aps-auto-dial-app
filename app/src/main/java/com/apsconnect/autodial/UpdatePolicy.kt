package com.apsconnect.autodial

/** DownloadManager values are stable platform constants; policy is testable without a handset. */
object UpdatePolicy {
    fun webApk(url: String?): Boolean = runCatching { val u=java.net.URI(url ?: "");u.scheme in listOf("https","http") && !u.host.isNullOrBlank() && u.path?.lowercase()?.endsWith(".apk")==true }.getOrDefault(false)
    fun state(status: Int?, elapsedMs: Long): String = when {
        status == 8 -> "ready"
        status == 16 -> "failed"
        status == null -> "missing"
        elapsedMs > 5*60*1000L -> "stalled"
        else -> "waiting"
    }
    fun archiveAllowed(packageName: String, expectedPackage: String, version: Long, expectedVersion: Long, installed: Long, signers: Set<String>, installedSigners: Set<String>) =
        packageName == expectedPackage && version == expectedVersion && version > installed && signers.isNotEmpty() && signers == installedSigners
    fun resumeAction(phase: String, canInstall: Boolean): String = when(phase) {
        "permission" -> if(canInstall) "install" else "permission"
        "installer" -> "retry-choice" // never loop after the user cancels Android's installer
        else -> "download"
    }
}
