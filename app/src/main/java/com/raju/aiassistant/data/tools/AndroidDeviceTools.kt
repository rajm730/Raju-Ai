package com.raju.aiassistant.data.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.telephony.SmsManager
import android.view.KeyEvent
import android.net.Uri
import android.os.BatteryManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.raju.aiassistant.domain.assistant.SettingsPage
import com.raju.aiassistant.domain.model.MusicService
import com.raju.aiassistant.domain.tools.Contact
import com.raju.aiassistant.domain.tools.ContactLookup
import com.raju.aiassistant.domain.tools.DeviceTools
import com.raju.aiassistant.domain.tools.MediaCommand
import com.raju.aiassistant.domain.tools.ToolOutcome
import com.raju.aiassistant.domain.tools.TravelMode
import com.raju.aiassistant.domain.tools.normalizeForWhatsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date

/** Phone actions via public APIs and visible system UI only (dialer, launcher intents, settings screens). */
class AndroidDeviceTools(private val context: Context) : DeviceTools {

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override suspend fun findContacts(query: String): ContactLookup {
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            return ContactLookup.NeedsPermission(Manifest.permission.READ_CONTACTS, "Seema AI needs Contacts access to find \"$query\". Contacts are read on this phone only and never uploaded.")
        }
        return withContext(Dispatchers.IO) {
            try {
                val result = mutableListOf<Contact>()
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%${query.trim()}%"),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
                )?.use { c ->
                    while (c.moveToNext() && result.size < 20) {
                        val name = c.getString(0) ?: continue
                        val number = c.getString(1) ?: continue
                        result += Contact(name, number)
                    }
                }
                // Prefer exact name matches when there are any.
                val exact = result.filter { it.name.equals(query.trim(), ignoreCase = true) }
                ContactLookup.Found(exact.ifEmpty { result })
            } catch (e: SecurityException) {
                ContactLookup.NeedsPermission(Manifest.permission.READ_CONTACTS, "Contacts permission is required.")
            } catch (e: Exception) {
                ContactLookup.Failed("Could not read contacts: ${e.message}")
            }
        }
    }

    override fun dial(contact: Contact, direct: Boolean): ToolOutcome {
        val uri = Uri.parse("tel:" + contact.number.filter { it.isDigit() || it == '+' })
        val useDirect = direct && granted(Manifest.permission.CALL_PHONE)
        val intent = Intent(if (useDirect) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
        return start(intent,
            success = if (useDirect) "Calling ${contact.name} (${contact.number})." else "Dialer opened for ${contact.name} (${contact.number}). Tap the call button to call.",
            failure = "No phone app found to make the call.")
    }

    override fun openApp(name: String): ToolOutcome {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val wanted = name.trim().lowercase()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == wanted }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().startsWith(wanted) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(wanted) }
            ?: return ToolOutcome.Failed("No installed app called \"$name\" was found.")
        val label = match.loadLabel(pm).toString()
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return ToolOutcome.Failed("$label cannot be opened.")
        return start(intent, "Opened $label.", "$label could not be opened.")
    }

    override fun openSettings(page: SettingsPage): ToolOutcome {
        val intent = when (page) {
            SettingsPage.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
            SettingsPage.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            SettingsPage.DISPLAY -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            SettingsPage.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
            SettingsPage.APPS -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            SettingsPage.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            SettingsPage.BATTERY -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            SettingsPage.APP_PERMISSIONS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            SettingsPage.GENERAL -> Intent(Settings.ACTION_SETTINGS)
        }
        return start(intent, "Opened ${page.name.lowercase().replace('_', ' ')} settings.", "That settings page is not available on this phone.")
    }

    override fun batteryStatus(): ToolOutcome {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0) return ToolOutcome.Failed("Battery level is not available.")
        return ToolOutcome.Done("Battery is at $level%" + if (charging) " and charging." else ".")
    }

    override fun playMusic(query: String, service: MusicService): ToolOutcome {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val (pkg, deepLink) = when (service) {
            MusicService.YOUTUBE_MUSIC -> "com.google.android.apps.youtube.music" to "https://music.youtube.com/search?q=$encoded"
            MusicService.SPOTIFY -> "com.spotify.music" to "spotify:search:$encoded"
        }
        // 1) Ask the app to search and play (supported by both apps), 2) open its search, 3) web fallback.
        val playFromSearch = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(pkg)
            putExtra(android.app.SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        if (isInstalled(pkg)) {
            if (tryStart(playFromSearch)) return ToolOutcome.Done("Asked ${service.label} to play \"$query\". Playback depends on the app and your subscription.")
            if (tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).setPackage(pkg))) return ToolOutcome.Done("Opened ${service.label} search for \"$query\".")
        }
        val web = if (service == MusicService.SPOTIFY) "https://open.spotify.com/search/$encoded" else "https://music.youtube.com/search?q=$encoded"
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(web)),
            "${service.label} app is not installed — opened its website search for \"$query\".",
            "Could not open ${service.label}.")
    }

    override fun openWhatsApp(number: String?, text: String): ToolOutcome {
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isInstalled(it) }
            ?: return ToolOutcome.Failed("WhatsApp is not installed.")
        val normalized = number?.let { normalizeForWhatsApp(it) }
        val intent = if (normalized != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$normalized?text=${URLEncoder.encode(text, "UTF-8")}")).setPackage(pkg)
        } else {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg)
        }
        return start(intent, "WhatsApp opened with your message. Review it and tap send yourself — nothing was sent automatically.", "Could not open WhatsApp.")
    }

    override fun hasPermission(permission: String): Boolean = granted(permission)

    override fun setTimer(seconds: Int, note: String): ToolOutcome {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_400))
            .putExtra(AlarmClock.EXTRA_MESSAGE, note)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val shown = if (seconds >= 60) "${seconds / 60} min" + if (seconds % 60 != 0) " ${seconds % 60} sec" else "" else "$seconds sec"
        return start(intent, "Timer set for $shown.", "No clock app accepted the timer.")
    }

    override fun setAlarm(hour: Int, minute: Int, note: String): ToolOutcome {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, note)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return start(intent, "Alarm set for %02d:%02d.".format(hour, minute), "No clock app accepted the alarm.")
    }

    override fun setFlashlight(on: Boolean): ToolOutcome {
        val cm = context.getSystemService(CameraManager::class.java) ?: return ToolOutcome.Failed("Camera service is not available.")
        return try {
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return ToolOutcome.Failed("This phone has no flashlight.")
            cm.setTorchMode(id, on)
            ToolOutcome.Done("Flashlight is " + if (on) "on." else "off.")
        } catch (e: CameraAccessException) {
            ToolOutcome.Failed("Flashlight could not be changed (camera is in use).")
        } catch (e: IllegalArgumentException) {
            ToolOutcome.Failed("Flashlight could not be changed.")
        }
    }

    override fun setVolume(percent: Int): ToolOutcome {
        val am = context.getSystemService(AudioManager::class.java) ?: return ToolOutcome.Failed("Audio service is not available.")
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent.coerceIn(0, 100) / 100f).toInt().coerceIn(0, max)
        return try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            ToolOutcome.Done("Media volume set to about ${percent.coerceIn(0, 100)}%.")
        } catch (e: SecurityException) {
            ToolOutcome.Failed("Android did not allow changing the volume right now (Do Not Disturb?).")
        }
    }

    override fun mediaControl(command: MediaCommand): ToolOutcome {
        val am = context.getSystemService(AudioManager::class.java) ?: return ToolOutcome.Failed("Audio service is not available.")
        val code = when (command) {
            MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ToolOutcome.Done("Sent \"${command.name.lowercase()}\" to the active music app. It only works if some app is playing or paused.")
    }

    @Suppress("DEPRECATION")
    override fun sendSms(contact: Contact, text: String): ToolOutcome {
        if (!granted(Manifest.permission.SEND_SMS)) return ToolOutcome.NeedsPermission(Manifest.permission.SEND_SMS, "SMS permission is required.")
        val number = contact.number.filter { it.isDigit() || it == '+' }
        if (number.length < 3) return ToolOutcome.Failed("\"${contact.number}\" is not a valid phone number.")
        return try {
            val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
            ToolOutcome.Done("SMS handed to Android for ${contact.name} ($number). Delivery is not confirmed.")
        } catch (e: Exception) {
            ToolOutcome.Failed("SMS could not be sent: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun addCalendarEvent(title: String, startMillis: Long, durationMinutes: Int, location: String?, rrule: String?): ToolOutcome {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + durationMinutes.coerceIn(1, 24 * 60) * 60_000L)
        if (!location.isNullOrBlank()) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        if (!rrule.isNullOrBlank()) intent.putExtra(CalendarContract.Events.RRULE, rrule)
        val shown = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startMillis))
        val repeats = when {
            rrule.isNullOrBlank() -> ""
            rrule.contains("BYDAY") -> " (Mon–Fri)"
            rrule.contains("DAILY") -> " (every day)"
            else -> " (every week)"
        }
        return start(intent, "Calendar opened with \"$title\" on $shown$repeats. Tap Save there to add it (a reminder is added by your calendar app).", "No calendar app is installed.")
    }

    override fun navigate(destination: String, mode: TravelMode): ToolOutcome {
        val q = Uri.encode(destination)
        val mapsMode = when (mode) { TravelMode.DRIVE -> "d"; TravelMode.WALK -> "w"; TravelMode.BIKE -> "b"; TravelMode.TRANSIT -> "d" }
        val webMode = when (mode) { TravelMode.DRIVE -> "driving"; TravelMode.WALK -> "walking"; TravelMode.BIKE -> "bicycling"; TravelMode.TRANSIT -> "transit" }
        val ok = (mode != TravelMode.TRANSIT && isInstalled(MAPS_PKG) &&
            tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q&mode=$mapsMode")).setPackage(MAPS_PKG))) ||
            tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$q&travelmode=$webMode"))) ||
            tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
        return if (ok) ToolOutcome.Done("Directions to \"$destination\" opened.") else ToolOutcome.Failed("No maps app or browser could open directions.")
    }

    override fun copyToClipboard(text: String): ToolOutcome {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return ToolOutcome.Failed("Clipboard is not available.")
        return try {
            cm.setPrimaryClip(ClipData.newPlainText("Seema AI", text))
            ToolOutcome.Done("Copied to clipboard (${text.length} characters).")
        } catch (e: RuntimeException) {
            ToolOutcome.Failed("Could not copy to the clipboard.")
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun tryStart(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun start(intent: Intent, success: String, failure: String): ToolOutcome =
        if (tryStart(intent)) ToolOutcome.Done(success) else ToolOutcome.Failed(failure)

    override fun openWebPage(url: String): ToolOutcome =
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Opened $url", "No browser could open $url")

    override fun shareTextFile(name: String, content: String): ToolOutcome = try {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, name)
        file.writeText(content)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "text/plain"
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Save or share $name").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(chooser, "File \"$name\" ready (${content.length} characters) — choose where to save or share it.", "Could not open the share sheet for $name.")
    } catch (e: Exception) {
        ToolOutcome.Failed("Could not save $name: ${e.message}")
    }

    private companion object {
        const val MAPS_PKG = "com.google.android.apps.maps"
    }
}
