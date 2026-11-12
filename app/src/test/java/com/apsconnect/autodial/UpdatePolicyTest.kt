package com.apsconnect.autodial
import org.junit.Assert.*
import org.junit.Test
class UpdatePolicyTest {
 @Test fun pendingAndPausedDownloadsNeverInstall() { assertEquals("waiting",UpdatePolicy.state(1,1000));assertEquals("waiting",UpdatePolicy.state(2,1000));assertEquals("waiting",UpdatePolicy.state(4,1000));assertEquals("failed",UpdatePolicy.state(16,1000));assertEquals("missing",UpdatePolicy.state(null,1000)) }
 @Test fun staleDownloadOffersRecoveryButCompletedDownloadCanProceed() { assertEquals("stalled",UpdatePolicy.state(2,301000));assertEquals("ready",UpdatePolicy.state(8,900000)) }
 @Test fun wrongPackageVersionOrCertificateCannotReachInstaller() {
  val expected=setOf("trusted-cert")
  assertTrue(UpdatePolicy.archiveAllowed("com.apsconnect.autodial","com.apsconnect.autodial",15,15,14,expected,expected))
  assertFalse(UpdatePolicy.archiveAllowed("other.app","com.apsconnect.autodial",15,15,14,expected,expected))
  assertFalse(UpdatePolicy.archiveAllowed("com.apsconnect.autodial","com.apsconnect.autodial",14,15,14,expected,expected))
  assertFalse(UpdatePolicy.archiveAllowed("com.apsconnect.autodial","com.apsconnect.autodial",16,15,14,expected,expected))
  assertFalse(UpdatePolicy.archiveAllowed("com.apsconnect.autodial","com.apsconnect.autodial",15,15,15,expected,expected))
  assertFalse(UpdatePolicy.archiveAllowed("com.apsconnect.autodial","com.apsconnect.autodial",15,15,14,setOf("wrong-cert"),expected))
  assertFalse(UpdatePolicy.archiveAllowed("com.apsconnect.autodial","com.apsconnect.autodial",15,15,14,emptySet(),expected))
 }
 @Test fun returningFromInstallPermissionContinuesButCancelNeverLoopsInstaller() { assertEquals("install",UpdatePolicy.resumeAction("permission",true));assertEquals("permission",UpdatePolicy.resumeAction("permission",false));assertEquals("retry-choice",UpdatePolicy.resumeAction("installer",true));assertEquals("download",UpdatePolicy.resumeAction("download",true)) }
 @Test fun apkLinksLeaveWebViewButArbitrarySchemesDoNot() { assertTrue(UpdatePolicy.webApk("https://github.com/example/releases/app.apk"));assertTrue(UpdatePolicy.webApk("https://example.com/APP.APK?download=1"));for(bad in listOf("intent://app.apk","file:///tmp/app.apk","content://x/app.apk","javascript:app.apk","https://example.com/crm/",null))assertFalse(UpdatePolicy.webApk(bad)) }
}
