package jp.bunkaich.sukashimotion;

import java.util.regex.Pattern;

/** Fold8 sensor HAL log samples. Logged in roughly 10-degree steps, not every degree. */
final class HardwareAngle {
    static final int SOURCE = 4;
    record Sample(long measuredAt, float angle) { }
    private static final Pattern LINE = Pattern.compile("^\\s*\\d+\\.\\d+\\s+\\d+\\s+\\d+\\s+I\\s+sensors-hal:\\s+handle_sns_client_event:\\d+, \\[0\\]folding_angle ts=(\\d+) ns value \\[\\s*(\\d+)/\\d+\\] \\(.*\\)\\s*$");
    static Sample parse(String line) {
        var match = LINE.matcher(line);
        if (!match.matches()) return null;
        try {
            long nanos = Long.parseLong(match.group(1));
            int angle = Integer.parseInt(match.group(2));
            if (nanos <= 0 || angle > 180) return null;
            return new Sample(nanos / 1_000_000, angle);
        } catch (NumberFormatException invalid) { return null; }
    }
}
