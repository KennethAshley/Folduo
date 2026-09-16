# Folduo for Galaxy Z Fold8

Animate the screen you are using as you open or close your Fold8. Folduo uses
hinge-driven blur, keeps the active app task, and reveals it at the destination
screen's size. Keep your existing launcher and saved wallpapers.

**Experimental build:** `0.1.27-fold8.36` (77). The installed app is named
**Folduo Fold8 Test**. This branch adapts [bunkaich/Folduo](https://github.com/bunkaich/Folduo)
and includes an original glass renderer using Android’s native blur.

[Install](#install-and-start) · [Controls](#controls) · [Settings](#animation-settings) ·
[Troubleshooting](#troubleshooting) · [Build from source](#build-from-source)

## Requirements

- **Tested device:** Galaxy Z Fold8 **SM-F971U**, Android 17 / One UI 9.0.
  Other Fold8 variants and other foldables have not been validated with this mode.
- **Shizuku**, installed, running, and authorized for Folduo.
- Permission to **display over other apps** and show notifications.
- Start with the phone **closed and unlocked**. Root and a replacement launcher
  are not required. The tested Fold8 supplies hinge readings without changing
  to a Samsung stock wallpaper.

## Install and start

### 1. Install the app

Download **Folduo-Fold8-0.1.27-fold8.36.apk** from the
[Fold8 experimental release](https://github.com/KennethAshley/Folduo/releases/tag/fold8-v77).
On your phone, open the APK and allow installation from that browser or file
manager when Android asks. Only the app APK is needed; source-code archives and
the development test APK are not needed for everyday use.

This is a pre-release for the tested Fold8, signed with the existing development
key. It uses the same package ID as upstream Folduo. See
[update and signing-key notes](#stop-resume-and-update) before replacing another
build. The original Folduo Fold7 download is a different version.

Prefer to compile it yourself? Follow [Build from source](#build-from-source).

### 2. Start Shizuku

Install Shizuku from its [official download page](https://shizuku.rikka.app/download/).
Use its [wireless debugging setup guide](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging)
to enable debugging, pair, and start Shizuku on the phone. Wait until Shizuku
reports that it is running.

Wireless startup does not require root or a computer. Shizuku needs to be
started again after a phone reboot. Its guide also explains startup using a
computer if you prefer that method.

### 3. Grant access

1. Open **Folduo Fold8 Test** and scroll to **Initial setup**.
2. Tap **Connect Shizuku** and allow Folduo in the Shizuku permission prompt.
3. Tap **Allow display over other apps** and enable that permission for Folduo.

Folduo also requests notification permission when you start it. Allow it so you
can reach **Stop** and **Resume** from the persistent notification.

### 4. Start the animation

1. Close the phone fully and unlock the cover screen.
2. Return to Folduo, read the screen-access explanation, and tap **Start animation**.
3. Wait for its notification to show **Active**.
4. Go Home and open an app normally, such as Calculator or Twitter/X.
5. Open and close the phone. The animation follows the hinge; the live app becomes
   interactive after the transition clears.

There is no session timer and no need to launch a test from a computer. Folduo
stays enabled until you tap **Stop**. Locking pauses screen capture and releases
display control; after unlocking, close fully once to prepare again.

## Controls

The cover uses Samsung's normal navigation. While Folduo is active, the inner
screen uses these controls:

| Action | Inner-screen gesture |
| --- | --- |
| Home | Swipe up from the small white line at the bottom. |
| Recent apps | Swipe up from that line and hold, then select an app card. |
| Back | Swipe inward from either side edge. |

Taps are blocked while a frozen transition image covers the app. The cover
fades to black during opening so the app can resize for the inner display
without exposing its wide layout outside. When closing, the cover reveals the
portrait app once it is ready.

For Samsung's normal inner-screen controls or notification shade, stop Folduo.
You can also access Folduo's notification from the cover screen.

## Animation settings

Open Folduo and scroll to **Animation settings**. Tap a setting to choose a preset.

| Setting | Choices | What changes |
| --- | --- | --- |
| **Blur strength** | Light / Default / Strong | How frosted the folding image looks. The temporary mask during app resizing stays protected. |
| **Responsiveness** | Quick / Default / Smooth | Quick follows the hinge sooner. Smooth softens stepped readings but follows your hand later. |
| **Outer-screen fade** | Earlier / Default / Later | When the cover darkens during opening. Later keeps it visible longer and delays the inner app resizing. Closing follows the same angle range in reverse. |

Choices are saved and apply between folds. Start with **Default** for the tested
look. **Reset to tested defaults** resets all three settings without changing
Start/Stop, permissions, launcher selection, or wallpapers.

Use **Language / 言語** at the top of the app to select English, Japanese, or the
system default. [README.ja.md](README.ja.md) contains the older upstream Fold7
instructions; this English guide describes the current Fold8 branch.

## Stop, resume, and update

- **Stop:** tap **Stop** in Folduo or its notification. This removes the animation
  and custom controls, restores native display dimensions, and releases display
  control. Stop before uninstalling.
- **Start again:** close and unlock the phone, check that Shizuku is running, and
  tap **Start animation**. If already enabled, use **Resume** in the notification.
- **After a reboot:** start Shizuku again, open Folduo, and close fully while
  unlocked. Tap **Start animation** if it is stopped.
- **Update:** stop Folduo, then install a newer APK signed with the same key over
  the existing app. Settings remain saved. Start again when ready.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| Shizuku is unavailable or disconnected | Start Shizuku, return to Folduo, and tap **Connect Shizuku**. Check Shizuku's authorized-app list if access was denied. |
| “Close the phone fully once to finish setup” | Close fully, unlock, and leave it closed until setup finishes. Starting while unfolded cannot prepare this mode. |
| The blur remains, controls stop responding, or a screen is stuck | Close the phone and tap **Stop** from the cover notification or Folduo. If you cannot reach either, reboot. Stop Folduo before restarting Shizuku. |
| Animation feels delayed or uneven | Try **Responsiveness → Quick**, or **Reset to tested defaults**. Hinge readings are stepped, and fast folds can outrun app preparation. |
| Android stops the app in the background | Use Folduo's **Open app battery settings** button. Consider unrestricted battery use for Folduo and Shizuku, then reconnect and start again. |

## Known limits and screen access

- Both displays are kept available while active, including between folds. This
  uses extra battery. Longer sessions and battery impact need further testing.
- Transitions use frozen app images. Video and games do not keep moving inside
  those images; live content returns when the transition clears.
- Protected screens cannot be captured. App resizing, rotation, fast folds, and
  individual app behavior can still cause problems. This is not an all-app
  compatibility guarantee.
- The mode relies on Samsung/Android private display APIs. Firmware updates may
  change their behavior. Losing Shizuku can interrupt animation and require recovery.
- While enabled and unlocked, Folduo captures screen content to prepare and draw
  transitions. Images stay in memory and are not saved or uploaded. The app has
  no internet permission, analytics, or ads. Shizuku is a separate privileged helper.

The standalone app passed two user-operated Twitter fold cycles in build 75.
Build 76 preserves those defaults and adds settings, with 56 unit checks and four
settings/rendering checks passing. Build 77 replaces the renderer with native
Gaussian frost and original glass projection; seven GPU rendering checks pass,
including repeated reversals at the inner panel’s full resolution. Physical
comparison of the new glass effect is pending. Alternate presets still need
subjective tuning.
See [FOLD8.md](FOLD8.md) for verification details and development history.

## Build from source

These commands are for macOS/Linux shells. The development build was verified on
macOS with JDK 21. On Windows, use Android Studio or `gradlew.bat` with equivalent
SDK setup.

### 1. Prepare the tools

Install Git, JDK 21, and the Android SDK command-line tools. Set `JAVA_HOME` to the
JDK and `ANDROID_HOME` to the SDK. Make `sdkmanager` and `adb` available on your PATH.
Then install the SDK packages and accept their licenses:

```sh
sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"
sdkmanager --licenses
```

### 2. Get this branch

```sh
git clone --branch codex/foldtoduo-fine --single-branch https://github.com/KennethAshley/Folduo.git
cd Folduo
```

### 3. Build the app

All shader code is included. If you previously built this branch with the external
shader, remove the old `app/src/main/res/raw/duo_fold.agsl` file first. The build
rejects that obsolete resource so it cannot accidentally enter a new APK.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Both debug and release variants use the same animation. The current Gradle release
configuration also uses a development signing key, as does this experimental APK.
A permanent release signing identity has not been established.
The initial build needs internet access for dependencies.

### 4. Install on the phone

Enable USB debugging, connect the phone, unlock it, and accept its USB-debugging
authorization prompt. With one Android device connected:

```sh
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
```

Then return to [Start Shizuku](#2-start-shizuku) above. See Android's
[ADB guide](https://developer.android.com/tools/adb) for connection help.

If installation reports a signing-key mismatch, your local build cannot update
that installed APK. To switch builds, first stop Folduo, then uninstall the old
copy and install yours; uninstalling removes Folduo's settings and permissions.
The current package identifier is shared with upstream Folduo, so the two cannot
be installed side by side.

## Credits and licensing

Original Folduo code is [MIT licensed](LICENSE), copyright bunkaich. This fork
adds Fold8 adaptations and its own glass renderer under the same MIT license.
Earlier local experiments used [kuris/foldtoduo](https://github.com/kuris/foldtoduo);
its shader is no longer included or required. See
[third-party notices](THIRD_PARTY_NOTICES.md) for bundled dependency licenses.

Apple/Samsung wallpapers, videos, and UI assets are not included in this source
repository. This project is not affiliated with Apple, Samsung, or Shizuku.
For the original Fold7 project, see the [upstream repository](https://github.com/bunkaich/Folduo).
