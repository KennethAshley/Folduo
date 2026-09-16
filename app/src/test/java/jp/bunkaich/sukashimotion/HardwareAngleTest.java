package jp.bunkaich.sukashimotion;

import org.junit.Test;
import static org.junit.Assert.*;

public class HardwareAngleTest {
    @Test public void acceptsOnlyNumericFold8HardwareEventsWithValidRangeAndTimestamp() {
        String line = "1789488642.289  1984  4567 I sensors-hal: handle_sns_client_event:107, [0]folding_angle ts=1043437599228467 ns value [ 39/0] ([0/0/1/1] [5.652/7.978/0.852] [-4.814/7.968/2.675] [1 0])";
        assertEquals(new HardwareAngle.Sample(1043437599, 39), HardwareAngle.parse(line));
        for (String invalid : new String[]{line.replace("sensors-hal:", "other:"), line.replace("[0]folding_angle", "[1]folding_angle"),
                line.replace("39/0", "181/0"), line.replace("39/0", "-1/0"), line.replace("39/0", "NaN/0"),
                line.replace("1043437599228467", "99999999999999999999999"), line.replace("1043437599228467", "0"),
                "1789488642.289 1984 4567 I sensors-hal: handle_sns_client_event:111, [0]folding_angle device_mode(0 -> 0), book_mode(1 -> 0)"}) {
            assertNull(invalid, HardwareAngle.parse(invalid));
        }
    }
}
