package com.apsconnect.autodial

import org.junit.Assert.*
import org.junit.Test

class LivePhoneSetupPolicyTest {
    private fun snapshot(runtime: Boolean = true, global: Boolean = true, channel: Boolean = true) =
        LiveSetupSnapshot(2,"Carrier",true,false,true,true,true,runtime,global,channel,true,true,true,"1.13",true,true)

    @Test fun bridgeAllowsOnlyFixedNonGrantActions() {
        for (action in listOf("prepareLogout","openSetup","getSetupStatus")) assertTrue(LiveBridgePolicy.actionAllowed(action))
        for (action in listOf("grantPermission","setSim","dial","execute","",null)) assertFalse(LiveBridgePolicy.actionAllowed(action))
    }
    @Test fun safeStatusHasOnlyApprovedMetadata() {
        val data = snapshot().wire()
        assertEquals(setOf("supported","officeSim","permissions","defaultPhone","liveReporting","appVersion","autoDial"),data.keys)
        val sim = data["officeSim"] as Map<*,*>
        assertEquals(setOf("slot","carrier","selected","needsConfirmation"),sim.keys)
        assertEquals(2,sim["slot"])
        for (privateField in listOf("iccid","subId","cookie","deviceId")) assertFalse(data.toString().contains(privateField,true))
    }
    @Test fun effectiveNotificationsRequireRuntimeGlobalAndChannel() {
        val enabled: (LiveSetupSnapshot) -> Any? = { (it.wire()["permissions"] as Map<*,*>)["notifications"] }
        assertEquals(true,enabled(snapshot()))
        assertEquals(false,enabled(snapshot(runtime=false)))
        assertEquals(false,enabled(snapshot(global=false)))
        assertEquals(false,enabled(snapshot(channel=false)))
    }
    @Test fun ambiguousMultiSimNeverFallsBackToDefaultDirectCall() {
        assertFalse(LiveCallPolicy.directDialAllowed(false,2,true,false))
        assertFalse(LiveCallPolicy.directDialAllowed(true,2,true,true))
        assertFalse(LiveCallPolicy.directDialAllowed(false,0,false,false))
        assertFalse(LiveCallPolicy.directDialAllowed(false,2,false,true))
        assertTrue(LiveCallPolicy.directDialAllowed(false,2,true,true))
        assertTrue(LiveCallPolicy.directDialAllowed(false,1,true,false))
    }
}
