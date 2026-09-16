package jp.bunkaich.sukashimotion;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceSupportTest {
    @Test public void limitsDisplayControlToTheTwoInspectedModels() {
        assertTrue(DeviceSupport.supports("SM-F971U"));
        assertTrue(DeviceSupport.supports("SM-F966Z"));
        for (String model : new String[]{null, "", "SM-F971B", "SM-F966U", "Pixel Fold"})
            assertFalse(DeviceSupport.supports(model));
    }
}
