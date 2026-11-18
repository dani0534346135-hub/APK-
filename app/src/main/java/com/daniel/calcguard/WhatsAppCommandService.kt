package com.daniel.calcguard

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class WhatsAppCommandService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val notification = sbn.notification ?: return
        // דילוג על התראת סיכום כללית ("X הודעות חדשות")
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras ?: return
        val sender = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: return

        // חילוץ הטקסט האחרון (תומך גם בהודעות מרובות)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val message = if (!textLines.isNullOrEmpty()) {
            textLines.last().toString().trim()
        } else {
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return
        }

        CommandExecutor.processIncomingCommand(this, sender, message) { replyText ->
            sendWhatsAppReply(sbn, replyText)
        }
    }

    private fun sendWhatsAppReply(sbn: StatusBarNotification, replyText: String) {
        val actions = sbn.notification.actions ?: return
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (remoteInput in remoteInputs) {
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(remoteInput.resultKey, replyText)
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                try {
                    action.actionIntent.send(this, 0, intent)
                } catch (_: Exception) { }
            }
        }
    }
}
