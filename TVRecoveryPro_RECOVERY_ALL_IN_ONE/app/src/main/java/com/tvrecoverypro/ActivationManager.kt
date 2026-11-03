package com.tvrecoverypro

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object ActivationManager {

    private const val PREFS = "tvrecovery_license"
    private const val DAYS = 365L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private const val PUBLIC_KEY =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAoG4VHYvcSeagFCrLHy18" +
        "FRqNgSUu+sYiXwWTMGFdvcX1Us/Up2iQdoJf31+7kKh5zOKtwDv76VHJbzSdgHAW" +
        "MbjWB1NQSzitqzB3kqP+OoXHJ+JjO7chmdx3844n+O/CuxJUGtTpJPtOgPpHcrN3" +
        "fSRPnqQuHEODGjxZHKa27uZ7mpsQYVhGQ/onHavkzuhtURGUXhilb8z8u0rPyEAK" +
        "V57qUSE/fA/kCTm1huEUD6+mVSPxhkTQVMAmhlN+BlJid0FEEcZgtF3vULZ4y6Ds" +
        "Kb+W2GuAUP1F2H5LtGALxqwjPO/22DutwVvlkYuAVylB0JpJc00A7FQwgcDjDHjj" +
        "1QIDAQAB"

    fun deviceId(context: Context): String =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN"

    private fun verifyKey(key: String, id: String): Boolean {
        return try {
            val parts = key.trim().split(".")
            if (parts.size != 2) return false

            val decoder = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            val payload = Base64.decode(parts[0], decoder)
            val signatureBytes = Base64.decode(parts[1], decoder)

            val text = String(payload, Charsets.UTF_8)
            val fields = text.split("|")
            if (fields.size != 3) return false
            if (fields[0] != id || fields[1] != "365") return false

            val publicBytes = Base64.decode(PUBLIC_KEY, Base64.DEFAULT)
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(publicBytes))

            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(payload)
            verifier.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }

    private fun isActivated(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val start = prefs.getLong("activated_at", 0L)
        val last = prefs.getLong("last_seen", 0L)
        val now = System.currentTimeMillis()

        if (start <= 0L || last <= 0L || now < last) return false
        if (now >= start + DAYS * DAY_MS) return false

        prefs.edit().putLong("last_seen", now).apply()
        return true
    }

    fun requireActivation(activity: Activity, onActivated: () -> Unit) {
        if (isActivated(activity)) {
            onActivated()
            return
        }

        val id = deviceId(activity)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
        }

        val info = TextView(activity).apply {
            text = "TVRecoveryPro Offline Activation\n\nDevice ID:\n$id\n\nValidity: 365 days from activation"
        }

        val input = EditText(activity).apply {
            hint = "Paste Activation Key"
            minLines = 2
            maxLines = 4
        }

        val copy = Button(activity).apply {
            text = "COPY DEVICE ID"
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Device ID", id)
                )
            }
        }

        layout.addView(info)
        layout.addView(copy)
        layout.addView(input)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("ACTIVATE TV RECOVERY PRO")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("ACTIVATE", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!verifyKey(input.text.toString(), id)) {
                    input.error = "Invalid key or key belongs to another device"
                    return@setOnClickListener
                }

                val now = System.currentTimeMillis()
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong("activated_at", now)
                    .putLong("last_seen", now)
                    .apply()

                dialog.dismiss()
                onActivated()
            }
        }

        dialog.show()
    }
}
