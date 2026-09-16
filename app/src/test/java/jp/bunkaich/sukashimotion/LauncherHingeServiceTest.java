package jp.bunkaich.sukashimotion;

import org.junit.Test;
import static org.junit.Assert.*;

public class LauncherHingeServiceTest {
    @Test public void acceptsOnlyThePairedLauncherPackage(){
        assertTrue(LauncherHingeService.ownsLauncher(new String[]{"shared.uid.app",LauncherHingeService.LAUNCHER_PACKAGE}));
        assertFalse(LauncherHingeService.ownsLauncher(null));
        assertFalse(LauncherHingeService.ownsLauncher(new String[]{"de.mm20.launcher2","de.mm20.launcher2.fold8.fake"}));
    }
    @Test public void yieldsToEitherInteractiveFolduoService(){
        assertTrue(LauncherHingeService.available(false,false));
        assertFalse(LauncherHingeService.available(true,false));
        assertFalse(LauncherHingeService.available(false,true));
        assertFalse(LauncherHingeService.available(true,true));
    }
    @Test public void unavailableIsEmittedOnceUntilANormalSampleRestoresTheFeed(){
        assertEquals(-1,LauncherHingeService.FEED_UNAVAILABLE);
        LauncherHingeService.FeedState feed=new LauncherHingeService.FeedState();
        assertTrue(feed.needsInitial());
        assertTrue(feed.unavailable());
        assertFalse(feed.unavailable());
        feed.sample();
        assertFalse(feed.needsInitial());
        assertTrue(feed.unavailable());
    }
    @Test public void onlyInteractiveOwnersCanReplaceAnotherReader(){
        Object launcher=new Object(),interactive=new Object();
        assertTrue(BridgeConnection.canStartAngles(null,launcher,false));
        assertTrue(BridgeConnection.canStartAngles(launcher,launcher,false));
        assertFalse(BridgeConnection.canStartAngles(interactive,launcher,false));
        assertTrue(BridgeConnection.canStartAngles(launcher,interactive,true));
    }
    @Test public void onlyTheCurrentActiveReaderReportsUnexpectedTermination(){
        assertTrue(ShellBridge.unexpectedReaderStop(7,7,true));
        assertFalse(ShellBridge.unexpectedReaderStop(7,8,true));
        assertFalse(ShellBridge.unexpectedReaderStop(7,7,false));
        assertTrue(LauncherHingeService.isReaderTermination(Float.NaN,-1));
        assertFalse(LauncherHingeService.isReaderTermination(0,-1));
        assertFalse(LauncherHingeService.isReaderTermination(Float.NaN,HardwareAngle.SOURCE));
    }
}
