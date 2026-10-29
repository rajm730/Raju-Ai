package com.raju.aiassistant.data.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import com.raju.aiassistant.domain.tools.ToolOutcome
import com.raju.aiassistant.domain.tools.normalizeForWhatsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/** Phone actions via public APIs and visible system UI only (dialer, launcher intents, settings screens). */
class AndroidDeviceTools(private val context: Context) : DeviceTools {

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override suspend fun findContacts(query: String): ContactLookup {
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            return ContactLookup.NeedsPermission(Manifest.permission.READ_CONTACTS, "Raju AI needs Contacts access to find \"$query\". Contacts are read on this phone only and never uploaded.")
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
}
