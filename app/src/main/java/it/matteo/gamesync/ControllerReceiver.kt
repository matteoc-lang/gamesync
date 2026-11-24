package it.matteo.gamesync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice

class ControllerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        if (!Store.autoOpen) return
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (hasGamepad(context)) {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                }
            } catch (e: Exception) {
                Log.w("GameSync", "Avvio da background bloccato: concedi SYSTEM_ALERT_WINDOW", e)
            } finally {
                pending.finish()
            }
        }, 1200)
    }

    private fun hasGamepad(ctx: Context): Boolean {
        val im = ctx.getSystemService(InputManager::class.java) ?: return false
        return im.inputDeviceIds.any { id ->
            val d = im.getInputDevice(id) ?: return@any false
            val src = d.sources
            val gamepad = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            val joystick = (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            !d.isVirtual && (gamepad || joystick)
        }
    }
}
