package jp.bunkaich.sukashimotion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Accept only visible, explicitly requested readings from Samsung's fold engine. */
final class WallpaperAngle {
    record Sample(long wallTime, float angle) {}
    private final Pattern pattern;

    WallpaperAngle(String action) {
        String expected = Pattern.quote(action), number = "([0-9]+(?:\\.[0-9]+)?)";
        pattern = Pattern.compile("^\\s*" + number + "\\s+\\d+\\s+\\d+\\s+I\\s+SprWallpaper\\|FoldInteractive:\\s+onCommand: (?:"
                + "action\\[" + expected + "\\], mCurrentAngle\\[" + number + "\\], isVisible\\[true\\]"
                + "|action=" + expected + ", mCurrentAngle=" + number + ", isVisible=true)\\s*$");
    }

    Sample parse(String line) {
        Matcher match = pattern.matcher(line);
        if (!match.matches()) return null;
        try {
            double millis = Double.parseDouble(match.group(1)) * 1000;
            float angle = Float.parseFloat(match.group(2) != null ? match.group(2) : match.group(3));
            if (!Double.isFinite(millis) || millis > Long.MAX_VALUE || !Float.isFinite(angle) || angle > 180) return null;
            return new Sample((long) millis, angle);
        } catch (NumberFormatException invalid) { return null; }
    }
}
