package jp.bunkaich.sukashimotion;

import org.junit.Test;
import static org.junit.Assert.*;

public class HingeTriggerTest {
    @Test public void startsBeforeEndpointsInBothDirectionsWithoutRepeatingOrReplaying() {
        HingeTrigger hinge = new HingeTrigger();
        assertEquals(HingeTrigger.NONE, hinge.update(90)); // Startup halfway is not a new fold.
        assertEquals(HingeTrigger.NONE, hinge.update(180));
        assertEquals(HingeTrigger.START, hinge.update(90));
        assertEquals(HingeTrigger.NONE, hinge.update(90));
        assertEquals(HingeTrigger.NONE, hinge.update(Float.NaN));
        assertEquals(HingeTrigger.NONE, hinge.update(-1));
        assertEquals(HingeTrigger.FINISH, hinge.update(0));
        assertEquals(HingeTrigger.START, hinge.update(90));
        assertEquals(HingeTrigger.FINISH, hinge.update(180));
        assertEquals(HingeTrigger.START, hinge.update(90));
        assertEquals(HingeTrigger.FINISH, hinge.update(180)); // Reversal also releases.
        assertEquals(HingeTrigger.START, hinge.update(156));
        assertEquals(HingeTrigger.FINISH, hinge.update(8)); // Logs may stop before exact zero.
        assertEquals(HingeTrigger.START, hinge.update(22));
        assertEquals(HingeTrigger.FINISH, hinge.update(170));
        hinge.reset(); // Reconnect/unlock must not replay a stale motion.
        assertEquals(HingeTrigger.NONE, hinge.update(90));
    }
    @Test public void nearClosedAngleMustWaitForSamsungToRecognizeClosure() {
        HingeTrigger hinge = new HingeTrigger();
        hinge.update(178); hinge.update(166);
        assertEquals(HingeTrigger.FINISH, hinge.update(8));
        assertFalse(HingeTrigger.nativeEndpoint(false, "OPENED"));
        assertFalse(HingeTrigger.nativeEndpoint(false, "TENT"));
        assertFalse(HingeTrigger.nativeEndpoint(false, null));
        assertTrue(HingeTrigger.nativeEndpoint(false, "CLOSED"));
        assertFalse(HingeTrigger.nativeEndpoint(true, "CLOSED"));
        assertTrue(HingeTrigger.nativeEndpoint(true, "OPENED"));
    }
}
