# Fold8 experimental branch

## Accepted build: v74 / 0.1.27-fold8.33

Tested on Galaxy Z Fold8 SM-F971U, Android 17 / One UI 9.0, with Shizuku.
The latest accepted result is the continuous Twitter trial in
`TwitterFoldTrialTest.oneUserStartedPhysicalCycle`. It preserves the active app
task, prepares the inner view under GPU frost, fades the cover with hinge movement
before widening the app, and reveals the fresh portrait frame when closing.

The trial keeps both panels available and uses custom navigation on the inner
screen. It does not replace the selected launcher or saved wallpapers. Samsung's
hardware hinge log supplies stepped angle readings; no stock wallpaper change is
needed for that source on the tested phone. The normal app Enable button runs a
separate older mode and does not start this trial.

Closing reuses the opaque native cover while restoring the portrait app, avoiding
a hidden placeholder and hidden layout blend. Three timing samples improved from
663/714/715 ms to 680/629/632 ms; these are controlled stationary measurements,
not a guarantee for every physical folding speed. The user accepted the result.

Validation for v74: 53 JVM checks, eight device handoff checks, and a stationary
Twitter round trip passed. Coverage includes reversals, interrupted cleanup,
five repeated cycles, held angles, actual destination pixels, inner taps, and
removal of the cover shade after stopping. The preceding GPU implementation also
passed 26 rendering checks; the shader was unchanged for v74.

This remains a development trial. Normal-speed smoothness, other apps, rotation,
battery impact, and longer sessions need further testing. Protected captures are
not supported. Test images remain in memory; diagnostic logs and user screenshots
are not part of this repository.

### Build and run the accepted trial

Use JDK 17 or newer, Android SDK 37, Build Tools 36.0.0, and the Gradle wrapper.
The exact shader dependency and its source are documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -d shell am instrument --no-hidden-api-checks -w -r \
  -e folduoHardware true -e continuous true \
  -e class jp.bunkaich.sukashimotion.TwitterFoldTrialTest#oneUserStartedPhysicalCycle \
  jp.bunkaich.sukashimotion.test/androidx.test.runner.AndroidJUnitRunner
```

Have Shizuku running and grant Folduo overlay and notification access. Close and
unlock the phone, tap **Start Twitter test**, and wait for Twitter before folding.
The same session stays active across folds. Expand Folduo's notification and tap
**Stop** to end it; locking or the ten-minute limit also ends the trial. Cleanup
restores the original display size and releases the display request. The separate
Home tests require the locally modified `com.example.duofold.fine` companion.

## Earlier experiments

## v62 / 0.1.21-fold8.21: preserve cover detail until the final layout change — visual check pending

The user confirms v61 clears after closing, but the outside looks like solid blur
throughout folding. The feedback trace has five completed transitions, with three
native cover captures ready in 60–127 ms; each reaches reveal and removes the
frozen windows. One initial source capture took 960 ms and was skipped. Thus the
reported visual problem is the overly strong 44-to-28 dp blur floor throughout
closing, rather than a stuck reveal. Evidence: `closing-v61-feedback-service.txt`
and `closing-v61-feedback-system.log` under `../.toolchain`.

The extra blur floor is now zero above 35 degrees and smoothly rises to 28 dp
between 35 and 10 degrees. The normal spatial hinge blur remains throughout the
rest of closing, preserving recognizable image detail. The native-size capture,
160 ms blurred layout blend, 200 ms clearing phase, proportional crop, and stable
physical screen assignments remain. Opening is unchanged. This is a visual tuning
change; the next check is whether the cover remains recognizable during folding.

v62 is built and installed. All 43 JVM tests pass; lint reports zero errors and
101 warnings. Four focused device checks pass in 2.614 seconds, covering paired
alignment, proportional cover crop, native cover blend/reveal, and service
lifecycle. Evidence: `closing-v62-build.log` and `closing-v62-checks.log` in
`../.toolchain`. Folduo is re-enabled after those checks, with the existing Twitter
task restored; the service reports running, unlocked, and idle. The physical
closing check starts at `closing-v62-twitter-start.txt`. Visual improvement still
needs the user's confirmation.

## v61 / 0.1.21-fold8.20: native cover reveal works; folding blur too strong

The user approved three closing changes: preserve image proportions, conceal the
wide-to-narrow layout change with blur, and blend into the correctly sized native
cover image before clearing. Kvaesitso and the accepted opening behavior remain.

The paired inner-left preview now uses a uniform scale and centered crop within
that pane, instead of independent horizontal/vertical scaling. It fills the cover
without black borders. Its contents are still a snapshot of the wide app until the
native layout becomes available. Closing cover frost follows the hinge with a
44-to-28 dp minimum blur; inner rendering and the opening blur curve are unchanged.

After the physical closed endpoint and native app draw readiness, RevealService
captures logical display 0 while excluding its own frozen surfaces. A native-sized
capture is prepared in memory, blended under 28 dp blur over 160 ms, then cleared
over 200 ms. Choreographer uses elapsed time, independent of Samsung's animator
speed. The final clear frame commits before power pins release and the overlay
fades. The service retains the source-primary concurrent mapping during folding;
there is no early destination-primary swap. Capture failure, wrong dimensions, or
a 1.2-second capture timeout fall back to revealing the live app. Stop/lock and
late-result generation checks prevent a delayed capture restoring an overlay.

The circle-aspect regression failed before the change (120-pixel height versus
60-pixel width); it now passes. GPU tests verify opaque intermediate colors, hidden
sharp details during the exchange, full-width destination content, and the existing
opening effects. All 26 rendering/surface tests pass in 10.502 seconds. The native
closing regression initially could not run while the physical phone changed
position; after the user held it closed, v60 failed at the expected missing cover
capture. v61 passes successful capture, protected-frame fallback, stop during a
blocked capture, and power-pin cleanup. Stable mapping/reversal, service lifecycle,
and update restore also pass: four tests in 8.469 seconds. These stationary checks
use synthetic frames and real Samsung display requests; actual visual quality and
real-app capture timing still need the user's closing check.

v61 is installed and enabled; the existing Twitter task is in front. The user
can open fully, then close while watching the cover. The visual check starts at
`closing-v61-twitter-start.txt`; no physical smoothness claim has been made yet.

All 43 JVM tests pass. Build and lint pass (0 errors, 101 warnings). Evidence under
`../.toolchain`: `closing-v61-red-render.log`, `closing-v61-flow-red-confirmed.log`,
`closing-v61-build.log`, `closing-v61-service-tests.log`, and
`closing-v61-render-tests.log`. Initial source capture still has the existing
700 ms budget; very fast folds can still skip the effect.

## v60 / 0.1.21-fold8.19: opening improved; closing still feels off

The v59 user check was unchanged. Its compositor trace identifies why power pins
and retained buffers were insufficient: switching CONCURRENT_OUTER_DEFAULT to
CONCURRENT_INNER_DEFAULT (or back) creates black ColorFade layers, sets both
physical displays to layerStack=-1, and fades Folduo's overlay window tokens out.
For example, at 19:49:33.762 both displays lose their layer stacks, restored at
19:49:33.850. The cover's ColorFade layer remains until 33.858. This occurs while
physical OFF requests are suppressed by the ON overrides. Evidence:
`../.toolchain/paired-v59-flash-windows.log` and `paired-v59-flash-system.log`.

Removed the early destination-primary request and its destination recapture path.
Both frozen views retain source-primary mapping until the physical endpoint, as
in v57's user-confirmed paired behavior. The v58 elapsed-time smoothing and flat
projection remain. The real app resizes beneath the frost during the final native
handoff. Tradeoff: during folding, the incoming image is still the mapped source
image; the correct destination layout appears at completion. This does not solve
slow initial capture (the 700 ms capture cutoff remains).

A stationary hardware regression runs the real service with synthetic angle/input
images and real Samsung display requests. It failed against v59: two concurrent
requests, instead of one. It now verifies one source-primary request through
motion/reversal, no early native request or extra capture, both visible frost
views, and native endpoint cleanup of power pins. The first combined regression
also caught a screenshot timing race: a child GPU callback does not wait for the
parent visibility transaction. The retention test now waits for that transaction,
as SnapshotSurface already does on initial attachment. A later opacity assertion
hit Samsung's transient "Dual screen is on" notice; the saved screenshot confirms
the background was blue and the unexpected pixels belong to that system banner.
Run surface checks separately from the hardware request check to avoid that banner.

Build, 43 JVM tests, and lint pass (0 errors, 101 warnings). The stationary mapping,
Home gesture, cleanup, and update-restore checks pass. All four surface checks
also pass when run separately (4.971 seconds). v60 is installed and enabled, with
the existing Twitter task returned to the foreground. Physical black-flash removal
is not fully confirmed. The user reports opening is "so much better," while
closing feels off; the specific closing symptom is being clarified. The feedback
trace shows eight completed transitions and one late capture skipped during a
rapid endpoint reversal (`paired-v60-feedback-service.txt` and
`paired-v60-feedback-system.log`). Evidence: `paired-role-red-test.log`,
`paired-v60-build.log`, `paired-v60-regression-final.log`,
`paired-v60-occlusion.png`, and `paired-v60-surfaces-final.log` under `../.toolchain`.

## v59 / 0.1.21-fold8.18: retained buffers; user reports starting flash unchanged

The user said v58 feels much better, but a black flash returned at the START of
opening/closing. Power traces show the ON pins suppressing requested physical OFF
during controlled transitions. A focused on-device regression reproduced the
frozen SurfaceView buffer being destroyed by temporary invisibility: the original
SurfaceControl became released/null. This is evidence for a rendering-lifecycle
gap, not proof that every reported flash has the same cause.

SnapshotSurface now uses the attachment lifecycle on API 34+, retaining its buffer
through temporary logical display invisibility and releasing it on detach. The
service first holds both panels with the source still primary; it waits for both
ON states and both frozen frames before requesting destination-primary mode.
Smoothing, flat projection, and the correctly sized destination capture are retained.
The buffer regression now passes, alongside surface opacity, hidden-touch blocking,
frame commits, service cleanup, and restore: six device tests in 7.683 seconds.
All 43 JVM tests pass; lint has no errors and 101 warnings. The user tested this
build and reported the starting flash was unchanged. Evidence: `paired-buffer-red-test.log`,
`paired-v59-build.log`, and `paired-v59-regression.log` under `../.toolchain`.

The v58 trace also has two early capture skips (one took 978 ms; another completed
after Samsung had already changed panels) and a fold ending before capture was
ready. The buffer fix does not yet prove those fast-start cases are addressed.
Evidence: `paired-v58-flash-service.txt` and `paired-v58-flash-system.log`.

## v58 / 0.1.21-fold8.17: smoother tracking confirmed; starting flash reported

After confirming both screens animate in v57, the user reported skew and uneven
motion. Supplied photos `IMG_1286.HEIC` and `IMG_1287.HEIC` show an additional issue:
the cover temporarily displays a cropped tablet Twitter layout with a side rail,
then the correct phone layout. This is not only perspective distortion.

The paired renderer now disables added perspective without removing hinge blur.
Choreographer drives a 70 ms elapsed-time filter, replacing repeated 100 ms
ValueAnimators. The device's animator scale is 0.5, which shortened those old ramps
to 50 ms; no system settings were changed. Tracking runs in both directions and
settles toward the actual endpoint. The existing projection remains for legacy
rendering and previews.

The temporary concurrent request now makes the destination primary early, while
retaining physical power pins. Existing SnapshotSurface wrappers let captureBehind
exclude our visible frost without hiding it. Once the destination app has drawn
with stable geometry, its native-size capture replaces the temporary mapped image.
The native endpoint handoff, 30-second limit, and cleanup remain. If a fold finishes
before that capture is ready, the real app is still revealed at the endpoint; the
short-lived mapped image can remain during fast folds. Recreated surfaces cannot
repeat the initial display request.

The new GPU alignment test reproduced the skew before the fix, then passed. The
timing regression failed before the 70 ms filter and now passes at 60/120 Hz and
on reversal. All 43 JVM tests and 22 on-device rendering/service/restore tests pass;
the final callback guard's service check passes separately in 1.653 seconds. Lint
has no errors and 101 warnings. No claim of visually smoother folding or correct
native destination preview is made until the next physical check. Evidence under
`../.toolchain`: `paired-{skew,timing}-red-test.log`, `paired-v58-build.log`,
`paired-v58-regression.log`, and `paired-v58-final-service-test.log`.

## Requirement clarified: both faces animate together

The user reports the outer screen stays black after a single switch-off while
opening in Twitter and Calculator. That is v56's intended early native switch,
not a reported black-then-relight event. The clarified goal requires the cover and
inner-left pane to animate oppositely, simultaneously, from the first motion.
The one-screen v56 transition is therefore incomplete for this goal.

Firmware inspection found `IDisplayManager.setDisplayStateOverrideWithDisplayId`
accepts a Binder-owned, timed physical power override. Its permission is DEVICE_POWER,
which shell has. Samsung's LocalDisplayDevice tracks logical state independently
from the physical override, allowing LogicalDisplayMapper to complete its transition
while physical ON is retained. The capability check passed in 0.121 seconds.

A diagnostic-only source-primary concurrent closing test pinned both logical
displays ON for at most 35 seconds, drew test colors, then released to native CLOSED
before clearing the pins. It passed in 24.542 seconds. The user watched the cover
and confirmed no black gap before Calculator returned. The power trace explicitly
shows the cover's requested OFF being deferred by its ON override; the physical
cover never powered OFF during handoff. Calculator task 3136 and 1,234 were retained.
Both pins were cleared (UNKNOWN), and no device-state override remains. The matching
opening test passed in 22.663 seconds. The user watched the inner screen and confirmed
no black gap between colors and Calculator. Task 3136 and 1,234 were retained again;
the inner ON override suppressed its requested OFF, and the outgoing cover switched
off once at the endpoint. Both tests cleaned up their temporary power pins.

### Paired blur integrated in v57 / 0.1.21-fold8.16 — user confirms both screens

RevealService now prepares both frozen views before requesting source-primary
concurrent mode through Shizuku. The cover receives the inner-left image; opening
maps the cover image into the inner display. Existing hinge shaders apply opposing
blur, with 100 ms smoothing of the stepped angle feed. At the physical endpoint,
the service requests the native destination while retaining both physical power
pins. It releases the pins only after the destination app is drawn, then fades
the overlays. Logical-display remapping updates each view's panel identity and
texture. Stop, lock, helper death, the 30-second fold limit, and the OS's 35-second
power timeout bound the temporary control.

The updated service regression failed against v56 (no paired request), then passed
on v57 alongside the inner-left transfer and package-update restore checks: three
on-device tests in 2.635 seconds. The native Enable/Home check also passed on the
open inner screen in 1.597 seconds: a real bottom-edge swipe returned from
Calculator to Kvaesitso while enabled. All 42 JVM tests passed, lint reported no
errors (101 warnings), and `git diff --check` passed. v57 is installed but disabled
between tests.

The first physical test expired before Start was tapped. In the retry the user
confirmed: "both screens do what we want." The trace shows both frost views
committing, then a partial closing motion reversing back to OPENED and completing
normally. Another closing motion began immediately afterward. The diagnostic
incorrectly treated the earlier completion counter as completion of this new fold
and failed "Frost removed" while the service was still waiting for native CLOSED.
Its cleanup then stopped the service. This was a test termination bug, not evidence
that the user-reported animation failed. The condition now also waits for no active
overlay and accepts multiple completed motions. Production animation code is
unchanged. The interrupted check did not reach its final Calculator task/value
assertions. The corrected diagnostic builds successfully but has not had another
physical run.

Retry evidence: `paired-v57-close-retry-{test,system}.log` and
`paired-v57-close-retry-handoff.txt`. Both power pins were released and device-state
override was empty afterward.

During the following normal-use check Folduo was already enabled. The live service
reported four completed transitions, zero skipped transitions, no error, and no
remaining overlay. The trace includes native CLOSED and OPENED handoffs, each
followed by power release and the completed reveal. Capture preparation ranged
from 181 to 352 ms. This verifies production completion in both directions after
the diagnostic ended, alongside the user's visual confirmation. It does not replace
the interrupted Calculator task/value assertions or prove all apps preserve state.
The working production build is left enabled for ordinary use. Evidence:
`paired-v57-normal-use-{service,state}.txt` and `paired-v57-normal-use-system.log`.

Evidence: `power-lock-capability.log`, `power-lock-{close,open}-{test,system}.log`,
`handoff-power-lock-dual-{close,open}-incoming.{txt,png}`, `paired-red-test.log`,
`paired-v57-build.log`, `paired-v57-regression.log`, and
`paired-v57-native-home-test.log` under `../.toolchain`.
Firmware evidence: `SamsungDisplayBinder.smali`, `SamsungLogicalDisplayMapper.smali`,
`SamsungLocalDisplayDevice.smali`, and `SamsungLocalDisplayRequest.smali`.

## Ready for everyday trial (superseded by the paired-animation requirement above)

The native Enable/Home check passed in 1.568 seconds: the actual Enable button
started RevealService, a real bottom-edge swipe from Calculator reached Kvaesitso,
and the service stayed enabled without an overlay or legacy MotionService. This
stationary check was on the cover; inner navigation has not yet had its own physical
check in v56. Evidence: `../.toolchain/native-home-test.log`.

Both integrated fold directions passed with the user's visual confirmation. After
the bounded checks, Folduo was enabled through its setup button for ordinary use.
The next useful observation is a normal fold from a different app or Home, rather
than repeating the Calculator diagnostic. Stop remains available in the notification.

## Native handoff integrated in v56 / 0.1.21-fold8.15

Enable and restore now start RevealService. It captures the current display when
fine hinge motion begins, prepares the existing frost textures, commits an opaque
SnapshotView on logical display 0, and then requests the ordinary native destination
state through Shizuku. Samsung resizes the real app behind that view. At the physical
endpoint the request is released and the overlay fades after the app draws. This
path does not use concurrent display states, task migration, mirroring, or custom
navigation. Separate wallpapers and launcher selection are not modified.

Integrated opening passed in 22.651 seconds, closing in 23.952 seconds, including
their Start waits. The user independently watched each incoming screen and confirmed
the blur revealed Calculator without a black flash. Both retained task 3136 and 1,234
at the destination's native size. Opening capture/texture preparation took 290 ms;
closing took 360 ms. Each trace shows one outgoing OFF and one incoming ON, without
an endpoint power cycle. Evidence: `native-service-{open,close}-{test,system}.log` and
`handoff-service-{open,close}-incoming.{txt,png}` under `../.toolchain`.

The Shizuku native-state capability passed in 1.458 seconds without instrumentation
adopting shell permissions. A regression first failed because the old service held
screens before the capture existed. Its replacement passes late-capture cancellation,
protected capture, frame-before-request, and explicit Stop checks. Package-update
restore targets the native service. Those two on-device checks passed in 2.202 seconds.
All 42 JVM tests pass; lint completes with no errors (99 warnings, including the
existing private-API reflection warnings). `git diff --check` passes.

Safety bounds: capture/texture preparation over 700 ms is skipped; a fold has a
30-second limit; lock, Stop, and helper loss remove the overlay and release control.
Incomplete/fast folds, reversal, other apps, and separate Home wallpaper rendering
have not yet had a full physical regression in this version. The native Home gesture
and Enable button are being checked automatically next. No claim of all-app or
all-speed compatibility is made from Calculator alone.

## Confirmed blur closing result and integration plan

The closing blur check passed in 33.768 seconds including Start wait. Frost committed
before requesting native CLOSED at 152 degrees. The inner panel powered OFF once and
the cover ON once. The request was released at physical CLOSED/3 degrees without a
second panel power transition. The user watched ONLY the cover and confirmed the blur
revealed Calculator without a black flash. Task 3136 retained 1,234. Cleanup restored
native CLOSED with no override or Folduo service.

Evidence: `frost-single-close-incoming-test.log`, `frost-single-close-incoming-system.log`,
and `handoff-frost-single-screen-early-close-incoming.txt`/`.png` under `../.toolchain`.

Integration steps:
1. Preserve the demonstrated native request through Shizuku's existing control owner
   and watchdog, with a stationary capability check.
2. Replace RevealService's concurrent-screen path with capture-on-motion, committed
   SnapshotView frost, native destination request, and a reveal after the app draws.
   Keep cancellation for lock, Stop, reversal, stale capture, and a 30-second hold.
3. Point Enable/restore at this service and describe native Samsung navigation.
4. Run build/lint/unit/lifecycle checks, then one physical direction per Start test.
   Passing the precaptured diagnostic does not yet prove capture timing in the service.

## Confirmed blur opening result

The diagnostic blur opening passed in 50.673 seconds including the Start wait.
Frost committed before the native OPENED request at 26 degrees. The cover powered
OFF once and the inner panel ON once. At 170 degrees the request was released, then
the frost faded away after the underlying app was drawn. There was no endpoint
panel power transition. Calculator task 3136 retained 1,234 at native 2448×1848.
The user watched ONLY the incoming inner screen and confirmed: "Blur reveals
Calculator without a black flash."

Evidence under `../.toolchain`: `frost-single-open-incoming-test.log`,
`frost-single-open-incoming-system.log`, and
`handoff-frost-single-screen-early-open-incoming.txt`/`.png`.
Cleanup verified no override, no Folduo service, and enabled=false.
The matching incoming-cover closing blur test is staged next.

## Confirmed single-screen closing result

The closing retry passed in 27.113 seconds including the Start wait. The native
CLOSED state was requested at 163 degrees and released when the physical base
became CLOSED at 4 degrees. The inner panel turned OFF once and the cover ON once;
release caused no further panel power transition because the native state already
matched. Calculator task 3136 retained 1,234 at native 1248×1972. The user explicitly
watched the outgoing inner screen and confirmed it turned off once and stayed off.

Evidence under `../.toolchain`: `single-close-outgoing-retry-test.log`,
`handoff-single-screen-early-close-outgoing-retry.txt`, and
`single-close-outgoing-retry-system.log`.

Both outgoing-screen checks now pass. A diagnostic-only frost overlay reuses
SnapshotView and FrameTexture over logical display 0 before requesting the native
destination state. It follows the hinge, then fades after the destination app is
drawn. The blur test APK builds and lint passes; the incoming-inner opening trial
is staged with an on-phone Start button. No visual blur result is established yet.
Production remains v55 and disabled; the native-state request still runs under
instrumentation shell permissions rather than through the production Shizuku path.

## Confirmed single-screen opening result

Single-screen early opening passed in 26.304 seconds including the Start wait.
The native OPENED state was requested at 21 degrees; the inner display became the
real primary and the cover turned OFF once. The physical base became OPENED during
travel, then the request was released at 175 degrees. There was no additional panel
power transition at release. Calculator task 3136 and 1,234 were retained at native
2448×1848. The user explicitly watched the outgoing cover and confirmed it turned
off once and stayed off. The incoming-screen animation has not been tested.

Evidence: `single-open-outgoing-test.log`, `handoff-single-screen-early-open-outgoing.txt`,
`handoff-single-screen-early-open-outgoing.png`, and `single-open-outgoing-system.log`.
The earlier five-second closing run is not counted as a completed handoff; the
30-second retry above replaces it.

## Single-screen early handoff: timeout artifact isolated

The first single-screen early-close attempt switched directly to native CLOSED at
156 degrees, powering the inner panel off once and the cover on. The user saw the
inner screen relight. The trace identifies the cause: the diagnostic cancelled its
request after its five-second hold limit at 18:31:29.207; the physical base remained
OPENED, so Samsung powered the inner screen on again. Actual closure was reported at
18:31:33.412, roughly four seconds after cancellation. This run did not reach its
intended endpoint; it does not establish a spontaneous flash during a completed
single-screen handoff. Evidence: `single-close-outgoing-test.log`,
`handoff-single-screen-early-close-outgoing.txt`, and `single-close-outgoing-system.log`.

The user is allowed to fold slowly. The diagnostic hold limit is now 30 seconds;
Start wait remains 3 minutes, overall fold wait remains 60 seconds, and finally
still releases control. Fine-angle callbacks are now recorded to distinguish hinge
motion from base-state timing. A single-screen early-opening test is staged next,
watching only the outgoing cover; closing must then be repeated with the longer limit.

## Visual comparison restarted at the user's request

Restarted native opening, watching the outgoing cover, passed in 26.023 seconds.
The user saw 1,234 stay on the cover until roughly halfway open, then the inner
screen took over and the cover turned black. No black-then-relight was reported.
Thus both outgoing-screen baselines now have direct observations.

A smaller alternative is under test: request the destination's ordinary CLOSED or
OPENED state early, without a concurrent/dual display state. That should avoid
re-enabling the outgoing panel after the normal primary-display swap. The capability
probe requested the already-current native state using adopted shell permissions,
then released it; it passed in 0.322 seconds. This proves API permission for the
instrumentation probe, not yet the actual early handoff or a production Shizuku path.
The single-screen physical closing test is now staged with a Start button; no blur,
mirroring, custom navigation, or production behavior changes have been added.

Restarted native closing, watching the outgoing inner screen, passed its state check
in 24.032 seconds. The user clarified that Calculator stayed continuously visible
while closing, with no black-then-relight interruption. The inner power log shows
one ON->OFF at closure, with no intervening relight. This is the first confirmed
visual baseline after the restart. Evidence: `restart-native-close-outgoing-test.log`,
`handoff-native-close-outgoing.txt`, and `restart-native-close-outgoing-system.log`
under `../.toolchain`.

The user noticed flashes on the opposite/outgoing screens and withdrew the earlier
visual judgments. All earlier "no flash" reports are now unconfirmed. The recorded
Calculator task/value preservation remains valid, but it does not establish a seamless
transition on either physical panel. Future physical checks explicitly name ONE
observed screen (outgoing or incoming), with separate traces and result files.
Turning off once at handoff is distinct from a black-then-relight flash during folding.

The destination-primary opening diagnostic retained task 3136 and 1,234 and completed
in 25.498 seconds. It changed primary mapping at 17 degrees and released at 175 degrees.
The inner panel remained ON through release; the outgoing cover had ON->OFF->ON at
activation and ON->OFF at release. This is a concrete candidate for the user's
opposite-screen flash. Source-primary early closing also retained state (11.174 seconds),
but its cover had ON->OFF->ON at release. These are power traces, not camera measurements.

All tests were stopped before restarting. Verified no display override, no Folduo
services, no projection, and native unfolded size. Start with ordinary native closing,
watching only the outgoing inner display.

## Native handoff comparison (in progress)

The early-activation closing comparison was prepared, but its three-minute Start
wait expired without a tap. No display request or physical comparison ran; this is
not a failed handoff result. Cleanup leaves the animation disabled and native display
control restored. Resume with the same early-close diagnostic when the user is ready.

The user clarified the intended contract: during folding, briefly show the previous
screen beneath a frosted transition; after unfolding or closing, the real app must
occupy the destination with native input, focus, and navigation. Persistent mirroring
does not satisfy that contract. Production Folduo remains disabled.

A new opt-in `NativeHandoffTest` checks one physical fold after an on-phone Start tap.
It asserts the same Calculator task and the value 1,234 on the destination's native
size. Baseline mode never starts Shizuku angle monitoring, requests displays, mirrors,
moves tasks, or starts an animation service. Early mode adds only a source-primary
dual-display request during hinge travel, releases it at the physical endpoint, and
restores control in finally. It requires both displays to have reported ON before
handoff. A held request is bounded to five seconds; the fold wait is sixty seconds.

The first completed baseline closing test passed (59.738 seconds including Start
wait); task 3136 retained 1,234. The user reported no black flash. The phone reported
both logical displays OFF briefly, even in this visually clean baseline. Those events
alone do not prove a visible blackout. The first screenshot was taken before native
power-on completed and is black; it is not evidence contradicting the user's report.
The diagnostic now waits for a nonblack captured frame, which still cannot establish
absence of an intermediate physical blackout. Logs: `../.toolchain/native-close-test.log`,
`handoff-native-close.txt`, and `native-close-system.log`. An earlier passive attempt
ended with the phone asleep and supplies no folding result.

## Current status: experiment paused, effect off

Version 55 was installed after build, lint, and JVM checks passed. The on-device
explicit-Stop regression passed (0.981 seconds). Final checks confirmed enabled=false,
no Folduo service, no display override, and native 2448×1848 unfolded size.

Version 55 removes the failed v54 cover input monitor and restores v53 behavior.
The native input-monitor API requires MONITOR_INPUT, which Shizuku's shell UID does
not have. The failed guard and its wiring are archived in
`../.toolchain/cover-guard-v54-source.tar.gz` for diagnosis, not shipped.

The v53 physical trial started successfully and kept its mirror alive. The user
confirmed Calculator on the cover before opening, then saw Kvaesitso instead.
The event buffer recorded Samsung's display-0 edge Back gesture and Calculator
finishing at 17:40:13. This supports unintended physical cover input during opening;
it does not establish that inner navigation itself failed. See
`../.toolchain/fold-events-full.log` and `../.toolchain/stable-physical-fold-retry.log`.
Automated navigation success is not a passing physical folding result.

No dependable daily-use solution has been established. A further candidate,
Samsung SemInputDeviceManager.setTspEnabled(TSP_SUB, FORCE_OFF/FORCE_ON, phase),
was inspected but never executed. Its service clears isTspForceOff during native
display-state changes. Permissions, touch restoration on helper death, and real
hardware behavior remain unverified. Do not enable this path without a bounded
restoration test. No Samsung touchscreen setting was changed by this investigation.

# Fold8 hinge-frost prototype

Local adaptation of [Folduo v0.1.21](https://github.com/bunkaich/Folduo/tree/v0.1.21),
retaining the upstream MIT license and notices.

## Current status

Version `0.1.21-fold8.12` (53) connects the live inner workspace and swipe controls
to the regular `MotionService` fold animation. Apps remain on logical display 0;
the service resizes that workspace under the frost and restores the native cover
layout before the closing reveal. The inner task mirror sits above Samsung's saved
inner wallpaper thumbnail, which was read and visually verified as the ship image.
No launcher or wallpaper selection changed.

The first version-52 physical trial stopped when the setup activity finished.
Samsung's ContentRecorder keeps old task listeners when a session is retargeted;
removing a previously captured task can therefore stop the current projection.
The new regression reproduced this failure before the fix. Version 53 keeps its
task capture attached to the selected Home task and uses `mirrorDisplay(0)` for
other apps. Switching between those layers avoids retargeting recording sessions.
Home retains the ship wallpaper; translucent app/system windows can expose the
cover wallpaper through the full-display mirror.

The fixed regression passes in 7.303 seconds, including setup-task removal,
controlled opening/closing, mirrored Calculator, Home, cover-size restoration,
and Stop cleanup. The complete production navigation check passes in 20.235
seconds (Home, Recents, Calculator selection, Back); its saved Home frame shows
the ship wallpaper. The combined regression run passes 21 tests in 12.655 seconds.
Build, lint, and JVM tests pass. A real-fold visual retry is
pending; synthetic angle traces do not prove that the physical endpoints are free
of black flashes. The physical harness now fails if its live mirror stops.
The version-53 retry expired waiting for the first closure (the base state stayed
OPENED and no fine angle samples arrived); it never reached the Start dialog.
This run supplies no evidence about physical animation quality. Cleanup disabled
the service and released capture/display control.

`InnerWorkspace` owns the inner wallpaper/mirror window; `WorkspaceMirror` runs in
the existing Shizuku bridge and owns task capture, touch forwarding, and the
temporary display-size override. Stop, lock, app Binder death, and the bridge's
heartbeat cleanup release these resources before releasing the fixed display state.
The older debug-only `HomeMirrorProbe` remains available for comparison, but the
regular switch now uses the production bridge. This still renders a static version
of the selected inner wallpaper, and the inactive cover has a letterboxed layout
while the inner workspace is expanded. Closing restores the cover layout.

Evidence: `../.toolchain/mirror-removal-red.log`, `mirror-removal-green.log`,
`stable-navigation.log`, `stable-home.png`, and `mirror-stable-build.log`.
The combined run is `stable-mirror-regressions.log`; the current physical retry
is recorded in `stable-physical-fold.log`.

## Earlier diagnostics

Version `0.1.21-fold8.10` (51) is installed on SM-F971U, Android 17 / One UI 9.0,
build F971USQS2AZH7. The user chose fixed-screen smooth mode with Folduo inner-screen
swipes after the endpoint power-off diagnosis. Start, resume, and update/boot recovery
now select `MotionService`. During the version 51 trial the user confirmed smooth
opening, then confirmed the blur clears into the cover without going black when
closing. The animation remains off outside bounded testing. Kvaesitso and both
personal wallpapers stay selected.

**Navigation blocker identified:** this firmware's `FoldDisplayController$1`
implements `shouldNotTopDisplay(id)` as `id == 1`. `RootWindowContainer.positionChildAt`
therefore rejects attempts to focus the inner panel in cover-primary mode. The real
log says `positionChildAt: can't gain focus display=Display{#1 ...}`. This is a
hard-coded rule in the phone's `/system/framework/services.jar`, with no settings
lookup in that method. Moving a task or receiving an `ok=true` Binder result does
not establish usable keyboard/Back focus. The app remains disabled.

The user requires a different wallpaper on each physical screen. A whole-display
mirror worked, but showed the cover wallpaper on the inner screen and was rejected.
A subsequent **debug-only Home mirror** now shows the original inner wallpaper
under transparent Kvaesitso content while the cover retains its own wallpaper.
An Android-generated finger swipe opens Kvaesitso's search panel and keyboard in
that mirror. The user also confirmed that a physical swipe opened the panel.
The stationary swipe-and-Back diagnostic now **passes both tests (12.714 seconds)**.
The earlier Back failure was stale accessibility data: captured frames showed Home
on both screens, and clearing UiAutomation's cache fixed the assertion.
The debug mirror now follows the foreground task and connects the existing inner
Home/Back swipes and Recents panel. Its complete automatic navigation check passed
in 14.45 seconds: mirror Calculator, swipe Home, hold for Recents, select Calculator,
and swipe Back. Captured Calculator frames are compared against the actual app on
display 0, so launching the app without showing it on the inner screen fails the check.
No production service has switched to mirroring, and Folduo remains disabled.
The complete physical navigation check now also **passes**: the user used Home,
Recents, selected Calculator, and used Back, and independently confirmed all four
responded. The test reports `MIRROR_PHYSICAL_NAVIGATION_OK` / `OK (1 test)` in
64.68 seconds including the on-phone Start wait. The user confirmed the last empty
manual Home run was a timing miss, not evidence that finger input failed.

The user confirmed version 44 works for the most part, with a
brief black screen on closing. Its real counters showed 14 completed effects, zero
skipped captures, and captures around 29–34 ms. The user tested version 45 and reported
the black gap unchanged. Its logs confirmed the new endpoint/app-frame wait ran
successfully (552 ms), so image retention alone did not solve the panel power cycle.
Kvaesitso and both personal Gallery wallpapers remain selected.

After verifying the fixed-screen launch repair, the user initially chose **native
Samsung controls with the brief closing black gap** as the mode to use going forward.
The subsequent visual animation check failed: the user reports inconsistent starts,
black flashes, and the cover staying black too often. `RevealService` was force-stopped
and Samsung's override was confirmed empty. The native-controls animation is **not
ready for daily use**; the successful Calculator routing test does not validate it.
The fixed-screen service is now selected in the app, but has not been left enabled.

The user confirmed the cover works when fully closed with the effect stopped. They
could not confidently distinguish partly folded black periods from fully closed
ones. The saved enable setting was subsequently set to false as well as force-stopping
the app, because the app had restarted from its previous On setting. Version 50 adds
bounded in-memory event history and debug logs for hinge events, power broadcasts,
panel state, capture, first draw, handoff, and cancellation reasons. It changes no
animation policy. A two-minute external trace watchdog stops the app and sets the
saved enable flag off afterward. This diagnostic is intended to establish the
interruption sequence before another behavior change. That trace is now complete.
The user clarified that the blackout happens at both fully open and fully closed,
and wants a continuous blur-to-clear reveal with no black frame. Native-controls
mode does not meet that goal. The effect remains stopped with its saved enable flag
off. Version 51 replaces the fixed-mode inner button bar with Folduo swipe controls;
Samsung's system navigation setting is unchanged.

Two previously separate prerequisites are now verified:

- Both physical panels can display content concurrently while partly folded. The user
  confirmed green inside and blue outside in a stationary 45-second test. Both windows
  committed frames, both panels stayed ON, and normal display control was restored.
- Samsung's `sensors-hal` log contains finer folding angles without the stock wallpaper.
  A live read captured 14, 26, 37, 49, 60 … 178 degrees. Logged samples usually changed
  by about 10–15 degrees, with 80–120 ms gaps in the normal-speed sample; most log
  delivery ages measured over USB were 2–5 ms. Slow movement produces longer gaps.
  These are stepwise measurements, not a guaranteed per-degree or fixed-rate stream.

The earlier post-switch blur was too late and did not meet the user's mid-fold goal.
Its real capture took 169 ms; an 80 ms cutoff merely suppressed the effect. That
post-switch trigger has been replaced, not presented as a successful fix.

## Endpoint blackout diagnosis

The version 50 trace (`../.toolchain/native-trace-v50.log`) records Samsung changing
which physical panel owns logical display 0 at each endpoint. Both logical displays
report OFF during that change even while Folduo reports its frost overlays showing.
For example, opening reports both OFF at 13:50:34.026 and the destination ON at
13:50:34.079; closing reports both OFF at 13:51:05.028 and the destination ON at
13:51:05.111. App-frame readiness follows at 13:50:34.303 and 13:51:05.334 respectively.
These are observed system-state times, not camera measurements of visible darkness.
Holding or fading an overlay cannot cover an interval when its panel is powered off.

Two additional issues appeared: overlay taps cancelled two transitions (at 8 and 16
degrees), and startup capture/first-draw latency varied. Neither explains away the
endpoint power-off sequence. Captured SCREEN_OFF cancellations coincided with a
not-interactive device, so there is no evidence to remove the lock/power guards.
No animation policy fix is included in version 50.

Fixed-screen mode avoids the primary-display swap and is the only mode for which the
user has confirmed the black gap is gone. Its current inner controls are Folduo's
swipes. The read-only `WindowPolicyProbe` on this firmware found native navigation
on display 0, but `shouldShowSystemDecors=false` and `hasNavigationBar=false` on display
1. Both displays already have engagement mode 3; changing that is not a demonstrated
solution. Runtime `IWindowManager` has no `setShouldShowSystemDecors` setter. Android's
[system decoration documentation](https://source.android.com/docs/core/display/multi_display/system-decorations)
describes optional secondary navigation and dynamic content modes, but does not prove
Samsung supports them for this panel. The runtime DisplayManager connection-preference
API is exposed as `setExternalDisplayConnectionPreference`, for external displays.
No native-navigation enable path was established, and no such setting was changed.

Logs: `../.toolchain/window-complete-probe.log` and
`../.toolchain/display-native-controls.log`. The probe builds and runs successfully.
The user then approved Folduo swipe controls for fixed-screen mode. Version 51 implements
Back from either side, Home from an upward bottom swipe, and Recent apps after an upward
swipe pauses for 350 ms. The idle controls occupy narrow edge windows, not a full-screen
input layer. They hide during folding and are removed when the service pauses or stops.
Tap/short movement, cancelled touches, multitouch, and pending holds after removal do
not navigate. Accessibility click and long-click actions remain available. The existing
recent-app panel and its Settings button are reused; no launcher replacement is added.

The swipe regression failed on version 50, then passed on version 51. The update-restore
regression first encountered a locked phone; after unlocking it failed at the expected
service-selection assertion. Both checks now pass. Seven navigation checks and three
service checks passed in 10.412 seconds; 42 JVM tests passed and lint reported no errors.
The final accessibility click path passed all seven navigation checks again in 7.349
seconds, with no InnerNavigation lint warnings. These checks verify control logic and
lifecycle. In the three-minute physical trial, the user confirmed smooth opening and
closing without the black flash. The native display log recorded no primary-display
swaps while the trial was active; full closure only disabled/re-enabled secondary
display 1. Service snapshots showed completed handoffs and zero recovery errors.
The user reported that Home, Back, and Recents did not work in the physical trial.
The build is therefore not ready for daily use. An opt-in check using Android's actual
input dispatcher delivered Home, hold-for-Recents, and both Back swipes to the inner
overlay (3.737 seconds). A stationary finger check first ended when the phone locked.
On retry, actual finger swipes reached the Home action and the helper returned `ok=true`,
but the user still reported no visible Home. That return value does not establish
launcher visibility. The user clarified that the underlying screen was black.

### Home handoff evidence

- The earlier focus-only success had no captured frame and did not prove visibility.
  The current opt-in `checkHome` hardware check captures the real frames and still
  fails while waiting for Home focus on display 1.
- `moveTaskToRootTask` rejects a HOME destination. The router now resumes the selected
  Home child through `startActivityFromRecents`; the regression mock rejects the
  unsupported operation too. The failing regression was reproduced, then all 14
  router tests passed. This repairs task transfer, not the focus restriction.
- SurfaceFlinger showed Kvaesitso's drawn window under a hidden Home parent. A bounded
  WindowContainerTransaction hide/show diagnostic made the date and swipe-up app list
  visible, with a black wallpaper background. Focus still remained elsewhere.
- Explicit root focus, child focusability and display-focus requests did not resolve
  it. Native focus logs show `appWindowsAreFocusable=true`, `hasOwnFocus=false`, and
  the display not on top. The final display-focus request was explicitly rejected by
  the hard-coded FoldDisplayController policy above. A standard-task reparent was
  also rejected because Android cannot change an existing Home activity's type.

Evidence: `../.toolchain/inner-home-surfaces-v51.txt`,
`../.toolchain/inner-home-reset-drawer.png`, and
`../.toolchain/home-system-focus-last.log`. The unsuccessful diagnostic branches were
removed from the instrumentation test. Both temporary focus-log groups were restored
to disabled. No visibility reset or focus experiment was added to production.

### Wallpaper-preserving mirror diagnostic

`HomeMirrorProbe` (debug source set only) runs a native MediaProjection task mirror
inside its own Shizuku process. Its grant and capture share the same shell UID;
adopting shell permissions in the instrumentation process does not change its UID
and cannot start a projection granted to the shell. This is enforced by Android.

The single-task capture needs both a launch cookie and the existing Home task ID.
Setting only the task ID silently chooses whole-display capture on this firmware.
The offscreen task check returned 271,655 transparent pixels out of 271,800; it
preserves Home's transparent background. Native keyboard content also appeared in
the live Home mirror during the search check.

`WallpaperMirrorHardwareTest` snapshots the currently selected inner wallpaper
while normally unfolded, holds cover-primary mode, and waits for **physical IDs**
to settle before temporarily sizing the primary app layout to 2448×1848. Merely
waiting for both panels to turn ON races the mapping change. The original wallpaper
selection is never changed. This first diagnostic uses a static wallpaper snapshot;
it does not yet preserve animated wallpaper behavior or handle startup while closed.

The inner overlay forwards touches into the cover's letterboxed physical viewport.
`MotionEvent.transform` changes view coordinates but leaves raw coordinates intact;
the isolated regression reproduced expected raw X=624 versus actual X=1224.
`applyTransform` passes that regression. Use Android's normal synthetic finger for
the full check; a manually built unknown-tool event did not reproduce finger input.
The resulting images show Kvaesitso search and its keyboard above the inner ship
wallpaper, with the Batman wallpaper still visible on the cover. An earlier
Calculator-label assertion was incorrect because the keyboard covered that list
entry; the current assertion checks the actual visible editable search field.

Current result: the stationary swipe-and-Back check passes, including coordinate
mapping (2 tests, 12.714 seconds; 43 forwarded events). Saving frames before the Back
assertion showed that both screens had already returned to Home. UiAutomation's
cached Compose nodes still reported the old search field; clearing the cache before
each query removed the false failure. No production navigation code changed for
this correction.

The first physical trial ended without receiving a swipe. On retry, real input
reached the overlay and the user confirmed the panel opened. That trial subsequently
failed its search-visible assertion; its saved frame shows Home again, so it does
not establish that the panel remained open or that the manual Back sequence ran.
The subsequent automatic trial with fresh accessibility data passed.

### Connected mirror navigation

`HomeMirrorProbe` now follows the foreground task on display 0 and handles the
existing navigation actions there. `WallpaperMirrorHardwareTest` installs the
existing `InnerNavigation` controls over the mirror. The helper remains debug-only
and expires after at most 60 seconds. The navigation binding is currently in the
instrumentation test; `MotionService` has not switched to this architecture.

Changing the task mirror on this firmware requires clearing the old native content
recording session before setting the next task on the same virtual display. A direct
replacement is ignored by `ContentRecordingController`. The diagnostic polls the
foreground task every 50 ms; it has not been assessed for continuous battery use.

The integration exposed two shared production bugs, both fixed at their existing
call sites:

- `TaskDisplayRouter.recentApps` loaded Samsung Calendar's live icon inside the shell
  helper. Its text renderer aborted because app_process had no initialized font
  runtime. Recents now returns package metadata; `InnerNavigation` loads icons in
  the application process that draws the cards.
- `selectRecent` and `launchSelected` queried and focused the old top task before
  the new launch finished. Device logs showed Calculator resume followed immediately
  by Home being refocused. Both paths now focus the exact selected task ID. Two new
  regressions failed with expected task 31 / actual Home task 7 before the fix.

The routing/control regression run reports `OK (24 tests)`; its separate opt-in
physical swipe check is skipped without the hardware flag. Build and lint pass.
The complete hardware navigation flow reports `MIRROR_NAVIGATION_OK` / `OK (1 test)`
in 14.45 seconds. Recents shows over the inner ship wallpaper, and selecting its
Calculator card shows the actual app in the inner mirror. Back removes one activity
from Calculator's existing history; expecting it always to go Home was incorrect.
The test now verifies the activity-count change and uses SINGLE_TOP for the initial
launch to avoid adding more duplicate Calculator screens.

A 45-second physical trial ran but recorded no navigation actions (completed mask
0 instead of 15). No corresponding navigation touch events appeared in the log.
The user subsequently reported that Calculator buttons worked but navigation swipes
did not. A later device check found ordinary `MotionService` running with no active
MediaProjection, so the mode used for that report is uncertain. The app's switch
still starts the old path; it does not start this mirror diagnostic. The old service
was stopped and its saved enable flag set false, with no service or size override
remaining.

Use `-e navigation true
-e manualNavigation true` with the same hardware test; wait for
`MIRROR_NAVIGATION_READY` before asking for Home, Recents, Calculator selection, and
Back. It reports `MIRROR_PHYSICAL_NAVIGATION_OK` only after all four actual actions.
For the narrowed finger-input check, use `-e navigation true -e manualHome true`.
Wait for `MIRROR_HOME_READY`, then make one upward swipe from the bottom white line.
The test reports `MIRROR_PHYSICAL_HOME_OK` only after the navigation action fires and
Kvaesitso becomes the actual foreground app. Navigation and mirror input logs now
include device IDs and raw coordinates for correlating a physical swipe with the
window that receives it. No gesture behavior has changed for this diagnostic.
The narrowed check ran for 53.709 seconds including setup, but recorded no Home
action (expected mask 1, actual 0). Its window-touch log and bounded inner raw-event
capture were empty. The user confirmed afterward that they had not touched the
phone before it ended. This run does not demonstrate a gesture implementation
failure.
Cleanup verified physical size 2448x1848 with no override, MediaProjection null,
no Folduo service, and the saved enable flag false. Evidence:
`../.toolchain/finger-home-result.log`, `finger-home-input.txt`, and
`finger-home-cleanup.txt` in the same directory.
The manual Home check now waits for an on-phone **Start swipe check** button, for
up to three minutes while normal Samsung display control remains active. Only after
that tap does it set up the mirror and start the 45-second swipe interval. A visible
instruction appears above Calculator, and the check prevents automatic screen-off
while the Start dialog or instruction is visible. Explicit locking and folding
still cancel the check. These changes affect only the test APK; the mirror helper
still has its original 60-second limit.
The user-started Home check **passed in 15.496 seconds**. The physical inner
touchscreen (device 8) delivered a bottom swipe from raw (1273, 1821) to (1469, 1126),
the handler emitted Home, and the test verified Kvaesitso in the foreground.
The user independently confirmed Home appeared. Cleanup again verified normal
2448x1848 size, no projection, and no Folduo service. Evidence:
`../.toolchain/manual-start-result.log`, `manual-start-touch.log`, and
`manual-start-cleanup.txt`.
The full manual navigation check now uses the same Start button and updates its
on-phone instruction after Home, Recents, and Calculator selection.
It passed with actual device-8 Home, hold-for-Recents, and right-edge Back gestures;
the Calculator card selection and resulting app/activity states were also verified.
The user confirmed all four responded. Evidence:
`../.toolchain/manual-navigation-start-result.log`,
`manual-navigation-start-touch.log`, and `manual-navigation-start-cleanup.txt`.
Fold animation integration, continuous use, and lifecycle recovery still need work.
No wallpaper or launcher setting changed.

The helper exits after at most 60 seconds (15 for automated checks). Cleanup restores
the size override and Samsung display control and removes the overlay. The correct
Shizuku destroy transaction is `FIRST_CALL_TRANSACTION + 16777114`; an early diagnostic
used the raw ID, leaving an idle helper after its capture timed out. That helper was
identified and stopped; the corrected cleanup was verified with no active projection,
no helper process, no size override, and no running Folduo service.

Evidence: `../.toolchain/task-mirror-transparency.log`,
`../.toolchain/mirror-coordinates-red.log`, `../.toolchain/wallpaper-mirror-hardware.log`,
`../.toolchain/wallpaper-mirror-finger-retry.log`,
`../.toolchain/wallpaper-mirror-back-hardware.log`,
`../.toolchain/wallpaper-mirror-cache-hardware.log`,
`../.toolchain/mirror-navigation-red.log`,
`../.toolchain/mirror-navigation-crash.log`,
`../.toolchain/navigation-selection-red.log`,
`../.toolchain/navigation-selection-green.log`,
`../.toolchain/mirror-navigation-verified.log`,
and the `wallpaper-mirror-*.png` frames in `../.toolchain/`.

## Current behavior

`ShellBridge` reuses its existing filtered logcat reader for Samsung's hardware angle
messages. `HardwareAngle` strictly parses the expected tag, event, sensor index, angle
range, and monotonic timestamp. Old, future, or malformed readings are ignored.
The coarse public hinge sensor remains a fallback. A fresh hardware angle takes
priority over coarse midpoint reports.

`RevealService` starts a transition when the hinge leaves an endpoint. It briefly
requests both panels while preserving whichever panel was already primary, captures
that primary screen through Shizuku, and draws a cropped frost image on both panels.
Blur follows the measured angle, smoothing the logged steps over 120 ms. The first
version uses the same source image on both panels; it does not render the destination
app's future layout or the upstream parallax effect.

Near the endpoint (within 10 degrees), a visible effect waits for Samsung's base
state to reach CLOSED or OPENED before releasing the display request. Its overlay
remains through the native panel remapping until the destination app reports two
stable drawn frames, then fades out. The wait is bounded to 1.8 seconds and remains
under the existing five-second overall watchdog. Tap, stop, lock, or failure clears
the overlay immediately. Captures that have not yet appeared are cancelled at the
endpoint immediately. Separate capture/control executors prevent a slow screenshot
from delaying that release. Captures over 500 ms old or protected/unavailable captures
are skipped. Images stay in memory and are discarded after use. Disabled Android
animations skip the effect. Samsung performs the final screen/app handoff.

The service does not move app tasks, change the default home, hide status icons, or
change wallpaper. Overlay bounds leave system bars outside the frozen image. Actual
navigation and visual smoothness through repeated physical folds still need checking.

## Verification

- Current debug APK and instrumentation APK build; 42 JVM tests pass; lint has no errors.
- Fixed-screen startup regression failed on version 46 and passed on version 47.
  Alongside the coarse-sensor safety check: `OK (2 tests)`, 1.608 seconds.
- The new physical-overlay regression failed on version 44 and passed on version 45:
  frost stays up when the app is not ready and clears once its frame is drawn. The
  safety regression passed again alongside it (`OK (2 tests)`, 7.287 seconds).
- Two new device checks passed (`OK (2 tests)`, 7.556 seconds): early hardware-angle
  triggering, cancellation during a slow capture, unavailable protected captures,
  five-second release, explicit stop, and update restoration of the current service.
- Earlier checks passed: 20 upstream GPU/overlay tests; real Shizuku capture and Bitmap
  parcel transfer; temporary Calculator display migration and restoration.
- Stationary concurrent-display diagnostic: `OK (1 test)`, 46.362 seconds, plus user
  confirmation that both colors were visible.
- The earlier color test had falsely passed after full closure cancelled state 4 at
  four seconds. The diagnostic now waits for frame commits and fails/cleans up if a
  panel turns off during its 45-second interval.

Long-term battery use, background recovery, and log availability after firmware
updates remain untested. The current hardware log is a private Samsung implementation
and may change. Shizuku must be started again after reboot.

Logs from version 44 show Samsung physically switching panels off/on during logical
display remapping and waiting about 300 ms for screen contents. Keeping an overlay
cannot illuminate a panel while its hardware is off. The user confirmed the revised
handoff did not reduce the visible gap and authorized a bounded fixed-screen test
with Folduo's inner navigation controls. Kvaesitso and the wallpapers remain selected.

## Fixed-screen experiment

`FixedScreenTest` explicitly requires `-e folduoHardware true`. It runs the existing
`MotionService` for three minutes after the cover-primary concurrent layout arms,
then stops the service and releases its owned display request. It does not select
a different HOME app or wallpaper. The user confirmed **the closing black gap is
gone** in version 47's fixed-screen trial. Sampled service state showed repeated
opening/closing transitions, zero recoveries, cover-primary state 5, and working
inner navigation creation. The user then confirmed Kvaesitso appeared through the
inner Home control, but tapping Calculator did nothing. Samsung logged `activity
launch is not allowed on rear display` and failed to place Calculator's task on
display 1, despite accepting the launch on the primary display. The trial finished
after 181.777 seconds and released its override. The earlier `RevealService` was
re-enabled afterward, restoring native controls with its known brief closing gap.

The fixed-screen trial proves the visual result, not full launcher compatibility.
Version 49 now repairs Kvaesitso app launches: both the real device check and the
user confirmed Calculator opened on the inner screen from Kvaesitso's app list.
The user then chose native Samsung controls for regular use. These fixed-screen
checks remain bounded trials rather than the selected daily mode.

`TaskLaunchProbe` is a debug-only, read-only shell listener that unregisters after
two minutes. It resolves transaction numbers from Samsung's own ITaskStackListener
stub rather than assuming AOSP numbering, and accepts callbacks only from system
UID 1000. The first follow-up window had no user tap. On the next attempt, the user
tapped Calculator from Kvaesitso: Samsung placed task 2948 on display 0 and emitted
two `onActivityLaunchOnSecondaryDisplayFailed` callbacks requesting display 1.
The probe does not move tasks or launch apps.

Version 48 adds `SecondaryLaunchListener` to the Shizuku helper. It listens only
while the fixed-screen inner controls are active. Only system-UID callbacks for an
actual task requested on display 1 are accepted. The helper rechecks the owned
display request, unlocked/open state, generation, and event freshness, then resumes
that exact standard task on display 1 using the existing task API. It confirms the
destination before counting success. Duplicate events cannot move a task already on
display 1. HOME/system tasks and unrelated foreground apps are not substituted.
Stopping, folding, locking, or a transfer error unregisters the listener.

The build, 42 JVM tests, and lint pass. Fourteen task-router device tests passed,
including exact-task/duplicate handling and rejection of a resume that did not
actually change displays. The callback validation test also passed (0.656 seconds),
covering caller identity, requested display, invalid task IDs, malformed payloads,
and a wrong interface token. Its first fixture failed because Samsung requires a
real task WindowContainerToken; the corrected fixture uses the test activity's own
detached task information.

The automatic `LaunchRecoveryHardwareTest` reproduced Samsung's rear-display
rejection using an ordinary activity launch on display 1. Version 48 received the
callback but failed its awake/unlocked check with `ApplicationSharedMemory not
initialized`: Android 17's cached PowerManager query is unavailable in this
Shizuku app_process. Version 49 reads the power and keyguard Binder services directly,
retaining both checks and failing closed if either query fails. The same test then
passed in 2.490 seconds: exactly one recovery, no helper error, and Calculator focused
and drawn on display 1. Logs measured 31 ms between the rejected launch and the
confirmed task migration; this is not a measured time to a visible frame. Test cleanup
released the display request and stopped angle monitoring.

Build, all 42 JVM tests, and lint passed again for version 49. The hardware test is
opt-in and requires the phone unlocked and fully unfolded. Its optional `manualTap`
check returns Calculator to the cover, shows Kvaesitso on the inner display, and waits
up to 90 seconds for the user's app-list tap. It requires a second confirmed recovery
and drawn Calculator frame, then cleans up. It does not alter the default launcher.
Automatic launcher UI attempts could not reliably expose Calculator's entry on the
secondary display; those attempts never tapped it and are not launch-repair failures.
The unreliable gesture automation was removed in favor of the bounded manual check.

The final manual check passed in 27.107 seconds. It observed two total recoveries:
one ordinary test-app launch and one actual Kvaesitso app-list tap. Samsung's native
log identifies the latter caller as Kvaesitso (UID 10477); the helper repaired that
rejected task in about 36 ms. The test confirmed Calculator focused and drawn on
display 1 with no helper error, and the user separately confirmed it appeared inside.
Cleanup left `mOverrideState=Optional.empty` and `Override Request active: false`.
This validates Calculator's launcher path; other apps and full fixed-mode daily use
have not yet been exercised.

Version 47 adapts the old service to the stepped hardware source: motion endpoints
use 10/170-degree thresholds, while the original fine-source thresholds remain.
The native screen switch can reset the angle reader after the last closing sample.
A real public CLOSED reading can therefore prepare the already-active cover mapping;
coarse samples alone still cannot start an animation or move an app. Once held,
motion uses the hardware log and existing app-task transfer and inner navigation.
The first live attempt on version 46 never armed; version 47 confirmed both panels
ON, cover-primary state 5, and zero recovery errors while awaiting the user's fold.

## Angle investigation

The public type-36 hinge sensor reports 0/90/180. Direct registration for Samsung's
finer type-65686 sensor and secondary gyro/accelerometer is denied to shell by SSENSOR.
`dumpsys sensorservice` masks those private sensor values, and the tested sysfs paths
are not readable. The usable alternative is the existing `sensors-hal` log output,
produced while Samsung's InputManager has the folding sensor active on this phone.

Original Folduo's stock interactive wallpaper is another angle source. It is not
needed for the newly found log source, and no wallpaper was replaced to obtain it.

## Settings checked

- Default launcher remains Kvaesitso:
  `de.mm20.launcher2.release/de.mm20.launcher2.ui.launcher.LauncherActivity`.
- Both home wallpapers use Samsung's Gallery LayeredWallpaperService.
- Fold-lock policy: `stay_awake_on_fold_key`. Display → Continue apps on cover screen
  controls native continuity; it does not expose angles.
- All three Android animation scales are 0.5×. No animation setting was changed.
- MultiStar 11.2.03 offers app sizing, rotation, and black letterbox wallpaper. Its
  “Continue All Apps” control redirects to Display settings. The inspected UI has no
  early panel activation or hinge-data control. No Good Lock settings were changed.
- Samsung's Cover screen mirroring controls separate/mirrored home layouts, not panel
  activation. The user's selected launcher remains responsible for its home screen.

## Build and checks

Use SDK platform `android-37.0`, build-tools `36.0.0`, and Temurin 21 (Java source 17).
This checkout uses workspace-local JAVA_HOME, ANDROID_HOME, ANDROID_USER_HOME, and
GRADLE_USER_HOME directories under `../.toolchain`.

```sh
./gradlew --offline --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -d shell am instrument -w -r -e class jp.bunkaich.sukashimotion.RevealServiceTest jp.bunkaich.sukashimotion.test/androidx.test.runner.AndroidJUnitRunner
adb -d shell dumpsys activity service jp.bunkaich.sukashimotion/.RevealService
```

Explicit hardware color diagnostic, automatically cleaned up after 45 seconds. Keep
an unlocked phone partly open and still; closing it fully cancels the display request.

```sh
adb -d shell am instrument -w -r -e folduoHardware true -e class jp.bunkaich.sukashimotion.ConcurrentDisplayTest jp.bunkaich.sukashimotion.test/androidx.test.runner.AndroidJUnitRunner
```

Device tests stop the service afterward. Enable in Folduo for a physical animation
check, then fully open or close once so its hinge trigger can initialize.

Unfolded, unlocked launch-repair regression (add `-e manualTap true` for the
90-second Kvaesitso app-list tap check):

```sh
adb -d shell am instrument -w -e folduoHardware true -e class jp.bunkaich.sukashimotion.LaunchRecoveryHardwareTest jp.bunkaich.sukashimotion.test/androidx.test.runner.AndroidJUnitRunner
```
