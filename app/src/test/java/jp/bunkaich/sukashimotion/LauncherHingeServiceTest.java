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
}
