# KEY2 Keyboard Fixer

An Android app that fixes the BlackBerry **KEY2** physical keyboard on **LineageOS 22/23 (Android 15/16)** and other custom ROMs:

| Problem on LineageOS | What the app does |
|---|---|
| Swiping on the keyboard in **Instagram Reels / YouTube Shorts / TikTok** scrolls a little and gets stuck between two videos | In the apps you choose, a keyboard swipe becomes a real screen swipe: **swipe up = next video, swipe down = previous**, one video per swipe (like the Clicks Communicator) |
| **Swipe-to-predict** (flick up on the keyboard to insert a suggested word) is gone | A small keyboard (IME) shows BlackBerry-style **three predictions**; swipe up from the left / middle / right part of the keyboard to insert that word. It also moves the cursor with sideways swipes, learns your words, and turns two spaces into ". " |
| The **Speed Key** (the 3×3-dot key) does nothing | **Speed Key + letter** opens the shortcut you set for that letter from any app, with a second shortcut when you hold the letter (52 shortcuts, like stock BlackBerry) |
| The **Convenience key** (side "action" button) does nothing | Press / hold / double-press run any action: flashlight, camera, an app, screenshot, play/pause, lock screen, notifications… |

Normal typing is not changed: Alt, Shift, Sym and the `$`/`0` keys still work exactly as the ROM defines them.

---

## Why these things broke (root cause)

I read the source of the KEY2 LineageOS builds ([device tree](https://github.com/tim-ecoder/android_device_blackberry_sdm660-common/tree/lineage-23.2), [frameworks patches](https://github.com/tim-ecoder/android_frameworks_base/tree/lineage-23.2-athena), [patch list](https://github.com/tim-ecoder/lineageos_blackberry_athena/tree/lineage-23.2-athena)) to find out exactly what happens:

1. **Reels/Shorts get stuck.** The keyboard's touch layer is a separate input device (`touch_keypad`, `touch.deviceType = touchPad`) that sends `SOURCE_TOUCHPAD` events. The ROM adds `ViewRootImpl.SyntheticTouchKeypadHandler`, which turns those touches into **mouse-wheel `ACTION_SCROLL` events** inside every app. Full-screen video feeds (RecyclerView + `PagerSnapHelper`, `ViewPager2`) only snap to the next item after a *touch* fling. A mouse wheel moves the list by a fixed amount and never triggers the snap, so you end up halfway between two videos.
   **Fix:** Android 14 added `AccessibilityService.onMotionEvent()` with `setMotionEventSources()`. While one of your chosen apps is in front (and you're not typing), the app claims `SOURCE_TOUCHPAD` events before they reach the app. It recognises the swipe and plays a real touchscreen flick with `dispatchGesture()`, so the app's own paging logic runs, exactly as if your finger were on the screen. Everywhere else the ROM's normal keyboard scrolling stays untouched.

2. **Swipe-to-predict is gone.** "Flick up to pick a word" was a feature of the *BlackBerry Keyboard* app, not of Android, and it isn't available on LineageOS. Gboard, SwiftKey and AOSP keyboards don't read the KEY2's touch layer.
   **Fix:** the included keyboard is an `InputMethodService`. The active keyboard gets touch-layer events before the app does (`InputMethodService.onGenericMotionEvent`), so it can read upward swipes and map the start position (left / middle / right third of the keyboard) to the three predictions.

3. **Speed Key / Convenience key do nothing.** In `stmpe.kl` the Speed Key is just a modifier code (right Shift / Fn). The side Convenience key is mapped to `ASSIST`, and `config_deviceHardwareKeys = 87` leaves out the Assist bit. That means LineageOS's *Buttons* settings never show it, and it can only "launch the default assistant", which does nothing if you don't have one.
   **Fix:** the accessibility service filters key events (`onKeyEvent`) and runs your shortcuts.

---

## Install

1. **Download the APK**
   - From the repository's [**Actions**](../../actions) tab: open the latest green "Build APK" run, then **Artifacts → Key2KeyboardFixer-apk** (needs a GitHub login), or
   - from [**Releases**](../../releases) if a version tag has been published.

   Use `Key2KeyboardFixer.apk`. All builds are signed with the same key, so newer versions install over older ones and keep your settings.
2. Open the app. The **Setup** section walks you through these steps:
   1. **Turn on the accessibility service** "KEY2 Keyboard Fixer".
      If the switch is greyed out ("restricted setting"): *Settings → Apps → KEY2 Keyboard Fixer → ⋮ → Allow restricted settings*, then try again. Android does this for every app installed from an APK file.
   2. **Choose the predictive keyboard**: enable "KEY2 Predictive Keyboard" and select it. (Optional. Skip this if you only want the Reels and key fixes.)
   3. **Default digital assistant → "None"** (or "KEY2 Keyboard Fixer"), so a press on the Convenience key doesn't also start Google Assistant / Gemini.

ADB alternative for step 2.1 (useful if your ROM hides the restricted-settings menu):
```sh
adb shell settings put secure enabled_accessibility_services io.github.erfanesfahanian.key2fixer/io.github.erfanesfahanian.key2fixer.a11y.KeyboardFixerService
adb shell settings put secure accessibility_enabled 1
```
(This replaces the list of enabled services. If you use other accessibility services, append them separated by `:`.)

---

## Using it

### Reels, Shorts & TikTok
- Default apps: Instagram, YouTube (and ReVanced/RVX), TikTok, Snapchat, Facebook. Change them under **Apps**.
- **Swipe style**
  - *Smart* (default): on full-screen video pagers every swipe flips exactly one video; on normal feeds in the same app the swipe is replayed as a finger drag of similar length and speed.
  - *Always flip one full page*, or *always replay as a finger drag*.
- **Swipe distance** sets how far you must swipe. **Reverse direction** flips the direction if your ROM's touch layer is mounted the other way.
- While a text field is focused (comments, search, DMs), the app hands the keyboard back so you can type and use predictions.
- Requires **Android 14+** (the API this depends on doesn't exist before that).

### Predictive keyboard
- The bar shows up to three words: the best one in the **middle**. Swipe up starting from the left, middle or right part of the keyboard to insert that word plus a space. You can also tap the word.
- With an empty word, it predicts the **next word** from what you've typed before.
- Sideways swipes **move the cursor** (hold Shift to select). You can switch this to "swipe left deletes the last word" or turn it off.
- Words are learned only on the phone, never in password or incognito fields. Clear them with *Forget learned words*.
- English word list (~107k words, from AOSP).

### Speed Key
- Assign shortcuts under **Letter shortcuts** (press and hold for each of A–Z).
- If your keyboard app uses the Speed Key as **Ctrl** (e.g. K12KB), turn on *Let the Speed Key through while typing*.
- If nothing happens, use **Which key is the Speed Key** and press it once. The app learns the code your ROM sends.

### Convenience key
- Default: press = flashlight, hold = camera. Change them, or add a double-press action.
- Use **Which key is the Convenience key** if your ROM sends a different code.

### Diagnostics
**Key & keyboard-touch tester** shows every key code, scan code and input device, plus the raw touch-layer coordinates and the recognised swipes. All remapping pauses while it's open. If something doesn't work on your build, check here first.

---

## Build from source

```sh
./gradlew testDebugUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```
Requirements: JDK 17 and the Android SDK (API 36). GitHub Actions builds and tests every push (`.github/workflows/build.yml`). Push a tag like `v1.0.0` to publish a Release with the APK attached.

The word list is generated with `tools/build_wordlist.py` from AOSP's `en_US_wordlist.combined.gz`.

### Project layout
```
app/src/main/java/io/github/erfanesfahanian/key2fixer/
  a11y/KeyboardFixerService.kt   accessibility service: touch-layer interception, keys
  a11y/GestureInjector.kt        flicks / drags via dispatchGesture
  a11y/PagerDetector.kt          "is this a full-screen video pager?"
  gesture/SwipeClassifier.kt     one-swipe-per-touch recogniser (unit tested)
  gesture/TouchpadGeometry.kt    raw touch_keypad coordinates -> keyboard units
  ime/PredictiveKeyboardService.kt  the keyboard (suggestion bar + swipe-up)
  ime/WordPredictor.kt           completion + typo correction + learning (unit tested)
  keys/KeyRouter.kt              Speed Key / Convenience key state machine (unit tested)
  keys/ActionRunner.kt           runs the chosen actions
  ui/…                           settings, shortcut editor, key tester, assist fallback
```

## Privacy
No internet permission, no analytics. The accessibility service only looks at key presses, keyboard-touch events and which app is in front, to perform the actions above.

## Credits
- Research based on the open-source KEY2 LineageOS work by BotchedRPR (Igor), krab-ubica / tim-ecoder, FumoEnterprises and the Lineage-BBKey2 team, and on the K12KB and Pastiera keyboard projects.
- English word list: Android Open Source Project (Apache 2.0).

License: Apache 2.0 (see `LICENSE` and `NOTICE`).
