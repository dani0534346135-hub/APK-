package com.daniel.calcguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

object CommandExecutor {

    private var activeRingtone: Ringtone? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun processIncomingCommand(
        context: Context,
        sender: String,
        message: String,
        replyCallback: (String) -> Unit
    ) {
        val prefs = context.getSharedPreferences("CalcGuardPrefs", Context.MODE_PRIVATE)
        val allowedContact = prefs.getString("allowed_contact", "")?.trim() ?: ""

        if (allowedContact.isNotEmpty() && !sender.contains(allowedContact, ignoreCase = true)) {
            return
        }

        val rules = TriggerRule.deserializeList(prefs.getString("custom_triggers", null))
        val matchedRule = rules.firstOrNull {
            message.trim().equals(it.keyword.trim(), ignoreCase = true)
        } ?: return

        when (matchedRule.action) {
            ActionType.SEND_LOCATION -> {
                setGpsState(context, true)
                fetchAndSendLocation(context, replyCallback)
            }
            ActionType.PLAY_ALARM -> {
                playAlarm(context)
                replyCallback("🔊 האזעקה הופעלה במכשיר.")
            }
            ActionType.STOP_ALARM -> {
                stopAlarm()
                replyCallback("🔇 האזעקה כובתה.")
            }
            ActionType.FLASHLIGHT_ON -> {
                val ok = setFlashlight(context, true)
                replyCallback(if (ok) "🔦 הפנס הודלק." else "⚠️ לא ניתן להדליק את הפנס.")
            }
            ActionType.FLASHLIGHT_OFF -> {
                val ok = setFlashlight(context, false)
                replyCallback(if (ok) "🌑 הפנס כובה." else "⚠️ לא ניתן לכבות את הפנס.")
            }
            ActionType.RINGER_NORMAL -> {
                setRingerNormal(context)
                replyCallback("🔔 מצב השמע הועבר לצלצול רגיל בעוצמה גבוהה.")
            }
            ActionType.GPS_ON -> {
                setGpsState(context, true)
                replyCallback("📍 ה-GPS הופעל.")
            }
            ActionType.GPS_OFF -> {
                setGpsState(context, false)
                replyCallback("📴 ה-GPS כובה.")
            }
            ActionType.DATA_ON -> {
                val ok = setMobileDataState(context, true)
                if (ok) replyCallback("📶 הנתונים הסלולריים הופעלו.")
                else replyCallback("⚠️ נכשלה הפעלת נתונים (וודא שהרשאת ADB פעילה).")
            }
            ActionType.DATA_OFF -> {
                val ok = setMobileDataState(context, false)
                if (ok) replyCallback("📵 הנתונים הסלולריים כובו.")
                else replyCallback("⚠️ נכשל כיבוי נתונים (וודא שהרשאת ADB פעילה).")
            }
            ActionType.HOTSPOT_ON -> {
                startLocalHotspot(context, replyCallback)
            }
            ActionType.BATTERY_SAVER_ON -> {
                val ok = setBatterySaver(context, true)
                if (ok) replyCallback("🪫 מצב חיסכון בסוללה הופעל.")
                else replyCallback("⚠️ נכשלה הפעלת חיסכון בסוללה (וודא שהרשאת ADB פעילה).")
            }
            ActionType.MAKE_CALL -> {
                val number = matchedRule.extraText.trim()
                if (number.isNotEmpty()) {
                    makeCall(context, number)
                    replyCallback("📞 מחייג למספר $number על רמקול...")
                } else {
                    replyCallback("⚠️ לא הוגדר מספר טלפון בטריגר.")
                }
            }
            ActionType.END_CALL -> {
                val ended = endCall(context)
                if (ended) replyCallback("📵 השיחה הפעילה נותקה.")
                else replyCallback("⚠️ אין שיחה פעילה לניתוק או שההרשאה חסרה.")
            }
            ActionType.DEVICE_STATUS -> {
                replyCallback(getFullDeviceStatus(context))
            }
            ActionType.CUSTOM_REPLY -> {
                val txt = matchedRule.extraText.ifEmpty { "✅ הפקודה בוצעה בהצלחה." }
                replyCallback(txt)
            }
        }
    }

    private fun setFlashlight(context: Context, enable: Boolean): Boolean {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull() ?: return false
            cm.setTorchMode(cameraId, enable)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun setRingerNormal(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            val maxRing = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            am.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)
        } catch (_: Exception) { }
    }

    private fun setBatterySaver(context: Context, enable: Boolean): Boolean {
        return try {
            Settings.Global.putInt(context.contentResolver, "low_power", if (enable) 1 else 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun setGpsState(context: Context, enable: Boolean) {
        try {
            val mode = if (enable) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, mode)
        } catch (_: Exception) { }
    }

    private fun setMobileDataState(context: Context, enable: Boolean): Boolean {
        return try {
            Settings.Global.putInt(context.contentResolver, "mobile_data", if (enable) 1 else 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocalHotspot(context: Context, replyCallback: (String) -> Unit) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            hotspotReservation?.close()
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    hotspotReservation = reservation
                    val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        reservation?.softApConfiguration?.ssid ?: "AndroidAP"
                    } else {
                        @Suppress("DEPRECATION")
                        reservation?.wifiConfiguration?.SSID ?: "AndroidAP"
                    }
                    val pass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        reservation?.softApConfiguration?.passphrase ?: ""
                    } else {
                        @Suppress("DEPRECATION")
                        reservation?.wifiConfiguration?.preSharedKey ?: ""
                    }
                    replyCallback("📡 נקודה חמה הופעלה!\nשם רשת: $ssid\nסיסמה: $pass")
                }

                override fun onFailed(reason: Int) {
                    replyCallback("⚠️ הפעלת נקודה חמה נכשלה (קוד: $reason). וודא שהרשאת המיקום מאושרת.")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            replyCallback("⚠️ שגיאה בהפעלת נקודה חמה: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchAndSendLocation(context: Context, replyCallback: (String) -> Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val bat = getBattery(context)

        val bestLast = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }

        if (bestLast != null) {
            val link = "https://maps.google.com/?q=${bestLast.latitude},${bestLast.longitude}"
            replyCallback("📍 מיקום:\n$link\n🔋 סוללה: $bat%")
            return
        }

        try {
            val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                LocationManager.NETWORK_PROVIDER
            } else {
                LocationManager.GPS_PROVIDER
            }
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, { loc ->
                val link = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                replyCallback("📍 מיקום נוכחי:\n$link\n🔋 סוללה: $bat%")
            }, Looper.getMainLooper())
        } catch (_: Exception) {
            replyCallback("⚠️ לא הצלחתי למשוך מיקום. וודא שהרשאת מיקום מאושרת תמיד.\n🔋 סוללה: $bat%")
        }
    }

    private fun playAlarm(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            activeRingtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            activeRingtone?.play()
        } catch (_: Exception) { }
    }

    private fun stopAlarm() {
        try {
            activeRingtone?.stop()
        } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun makeCall(context: Context, phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_IN_CALL
                am.isSpeakerphoneOn = true
            } catch (_: Exception) { }
        }, 2500)
    }

    @SuppressLint("MissingPermission")
    private fun endCall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        return tm?.endCall() ?: false
    }

    private fun getBattery(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getFullDeviceStatus(context: Context): String {
        val bat = getBattery(context)
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val chargeStr = if (isCharging) "בטעינה ⚡" else "לא בטעינה"

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val netStr = when {
            caps == null -> "אין חיבור רשת"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi 📶"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "רשת סלולרית 📡"
            else -> "מחובר"
        }

        return "📊 דוח מצב מכשיר:\n🔋 סוללה: $bat% ($chargeStr)\n🌐 חיבור: $netStr"
    }
}
