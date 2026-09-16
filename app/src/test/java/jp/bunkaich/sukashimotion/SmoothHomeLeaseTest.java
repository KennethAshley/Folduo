package jp.bunkaich.sukashimotion;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmoothHomeLeaseTest {
    @Test public void rearmRequiresARealFoldAndCannotRepeatAtTheSameEndpoint(){
        SmoothHomeLease lease=new SmoothHomeLease(100);
        assertEquals(SmoothHomeLease.KEEP,lease.observe(true,false,200)); // Initial request still settling.
        assertEquals(SmoothHomeLease.KEEP,lease.observe(true,true,300));
        assertEquals(SmoothHomeLease.KEEP,lease.observe(false,true,400));
        assertEquals(SmoothHomeLease.REARM,lease.observe(true,false,500));
        assertEquals(SmoothHomeLease.KEEP,lease.observe(true,false,600));
        assertEquals(SmoothHomeLease.KEEP,lease.observe(true,true,700));
        assertEquals(SmoothHomeLease.STOP,lease.observe(true,false,800));
    }
    @Test public void missingHeartbeatAndFailedRequestsReleaseInsteadOfRetryingForever(){
        SmoothHomeLease lease=new SmoothHomeLease(100);
        assertEquals(SmoothHomeLease.STOP,lease.observe(true,false,1700));
        assertFalse(lease.expired(3099));
        assertTrue(lease.expired(3100));
        lease.renew(3000);
        assertFalse(lease.expired(3100));
        assertTrue(lease.expired(6000));
    }
    @Test public void theReadOnlyLauncherDoesNotGainDisplayControl(){
        assertTrue(LauncherHingeService.ownsSmoothHome(new String[]{"com.example.duofold.fine"}));
        assertFalse(LauncherHingeService.ownsSmoothHome(new String[]{LauncherHingeService.LAUNCHER_PACKAGE}));
        assertFalse(LauncherHingeService.ownsSmoothHome(new String[]{"com.example.duofold", "com.example.duofold.fine.fake"}));
        assertFalse(LauncherHingeService.ownsSmoothHome(null));
    }
}
