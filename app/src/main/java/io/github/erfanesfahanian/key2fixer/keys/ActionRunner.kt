package io.github.erfanesfahanian.key2fixer.keys

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import io.github.erfanesfahanian.key2fixer.R
import io.github.erfanesfahanian.key2fixer.ui.MainActivity
import io.github.erfanesfahanian.key2fixer.util.Prefs

/**
 * Runs [KeyAction]s. System actions (Back, Recents, Lock screen...) need the accessibility
 * service; everything else also works from a plain Context.
 */
class ActionRunner(private val context: Context, private val service: AccessibilityService?) {
    private val main = Handler(Looper.getMainLooper())

    /** Starts tracking torch state early so the first toggle knows whether it is on. */
    fun prepare() {
        runCatching { TorchState.ensureRegistered(context.getSystemService(CameraManager::class.java)) }
    }

    fun run(action: KeyAction) {
        try {
            when (action) {
                KeyAction.None -> Unit
                is KeyAction.Launch -> launchApp(action.packageName)
                is KeyAction.Builtin -> runBuiltin(action.kind)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Action $action failed", e)
            toast(context.getString(R.string.toast_action_failed))
        }
    }

    private fun runBuiltin(kind: BuiltinAction) {
        when (kind) {
            BuiltinAction.BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            BuiltinAction.HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            BuiltinAction.RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            BuiltinAction.NOTIFICATIONS -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            BuiltinAction.QUICK_SETTINGS -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            BuiltinAction.POWER_MENU -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            BuiltinAction.LOCK_SCREEN -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            BuiltinAction.SCREENSHOT -> global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            BuiltinAction.SPLIT_SCREEN -> global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            BuiltinAction.TORCH -> toggleTorch()
            BuiltinAction.CAMERA -> startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            BuiltinAction.ASSISTANT -> startAssistant()
            BuiltinAction.MEDIA_PLAY_PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            BuiltinAction.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            BuiltinAction.MEDIA_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            BuiltinAction.VOLUME_PANEL -> context.getSystemService(AudioManager::class.java)
                .adjustSuggestedStreamVolume(AudioManager.ADJUST_SAME, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
            BuiltinAction.TOGGLE_REELS -> {
                val prefs = Prefs(context)
                prefs.reelsEnabled = !prefs.reelsEnabled
                toast(context.getString(if (prefs.reelsEnabled) R.string.toast_reels_on else R.string.toast_reels_off))
            }
            BuiltinAction.OPEN_SETTINGS -> startActivity(Intent(context, MainActivity::class.java))
        }
    }

    private fun global(action: Int) {
        val s = service
        if (s == null || !s.performGlobalAction(action)) toast(context.getString(R.string.toast_needs_accessibility))
    }

    private fun launchApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            toast(context.getString(R.string.toast_app_missing, packageName))
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
    }

    private fun startActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(context.getString(R.string.toast_no_app_for_action))
        }
    }

    private fun startAssistant() {
        // ACTION_VOICE_COMMAND opens the voice assistant (Gemini, Google, or a FOSS one).
        val voice = Intent(Intent.ACTION_VOICE_COMMAND)
        if (voice.resolveActivity(context.packageManager) != null) startActivity(voice)
        else startActivity(Intent(Intent.ACTION_WEB_SEARCH))
    }

    private fun mediaKey(code: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun toggleTorch() {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = torchCameraId(cm) ?: run {
            toast(context.getString(R.string.toast_no_flash))
            return
        }
        TorchState.ensureRegistered(cm)
        cm.setTorchMode(id, !TorchState.isOn(id))
    }

    private fun torchCameraId(cm: CameraManager): String? = cm.cameraIdList.firstOrNull { id ->
        val c = cm.getCameraCharacteristics(id)
        c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    private fun toast(text: String) = main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }

    /** Tracks torch state so the flashlight can be toggled (CameraManager has no getter). */
    private object TorchState : CameraManager.TorchCallback() {
        private val on = HashMap<String, Boolean>()
        private var registered = false

        fun ensureRegistered(cm: CameraManager) {
            if (registered) return
            registered = true
            cm.registerTorchCallback(this, Handler(Looper.getMainLooper()))
        }

        fun isOn(id: String) = on[id] == true

        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            on[cameraId] = enabled
        }
    }

    companion object {
        private const val TAG = "Key2Fixer"
    }
}
