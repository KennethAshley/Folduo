package jp.bunkaich.sukashimotion;

import android.os.Build;
import android.os.Looper;

/** Read-only shell check: validates the real display API without requesting a state. */
public final class CompatibilityProbe {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        try (DualDisplayControl control = new DualDisplayControl()) {
            if (control.innerState == control.outerState)
                throw new AssertionError("Distinct concurrent display states required");
            System.out.println("DISPLAY_API_OK model=" + Build.MODEL + " " + control.describe());
        } catch (Throwable error) {
            error.printStackTrace(System.out);
            System.exit(1);
        }
        System.exit(0);
    }
}
