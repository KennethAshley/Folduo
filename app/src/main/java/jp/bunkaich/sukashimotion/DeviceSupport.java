package jp.bunkaich.sukashimotion;

/** Model gate only; the display controller still validates the actual device states. */
public final class DeviceSupport {
    private DeviceSupport() {}
    public static boolean supports(String model) {
        return "SM-F966Z".equals(model) || "SM-F971U".equals(model);
    }
}
