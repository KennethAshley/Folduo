package jp.bunkaich.sukashimotion;

import org.junit.Test;
import static org.junit.Assert.*;

public class WallpaperAngleTest {
    private final WallpaperAngle reader = new WallpaperAngle("jp.bunkaich.sukashimotion.READ_ANGLE");
    private final String prefix = "1789470000.125  1234  5678 I SprWallpaper|FoldInteractive: onCommand: ";
    private final String fold8 = "action=jp.bunkaich.sukashimotion.READ_ANGLE, mCurrentAngle=47.25, isVisible=true";

    @Test public void acceptsObservedFold8AndLegacyFold7Formats() {
        for (String message : new String[]{fold8,
                "action[jp.bunkaich.sukashimotion.READ_ANGLE], mCurrentAngle[47.25], isVisible[true]"}) {
            WallpaperAngle.Sample sample = reader.parse(prefix + message);
            assertNotNull(sample);
            assertEquals(47.25f, sample.angle(), 0);
            assertEquals(1789470000125L, sample.wallTime());
        }
    }

    @Test public void rejectsInvisibleUnrelatedAndInvalidReadings() {
        for (String line : new String[]{"", prefix + fold8.replace("true", "false"),
                prefix + fold8.replace("READ_ANGLE", "OTHER"),
                prefix.replace("FoldInteractive", "OtherWallpaper") + fold8,
                prefix + fold8.replace("47.25", "181"), prefix + fold8.replace("47.25", "-1"),
                prefix + fold8.replace("47.25", "NaN"), prefix + fold8.replace("47.25", "9.9.9"),
                prefix + fold8 + "garbage"}) assertNull(line, reader.parse(line));
    }
}
