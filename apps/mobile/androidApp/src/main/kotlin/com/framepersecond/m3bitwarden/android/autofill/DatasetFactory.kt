package com.framepersecond.m3bitwarden.android.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.drawable.Icon
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.InlinePresentation
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.framepersecond.m3bitwarden.android.R
import com.framepersecond.m3bitwarden.autofill.FieldAssignment

/** Builds the entries shown in the autofill drop-down and above the keyboard. */
class DatasetFactory(private val context: Context) {

    data class Label(val title: String, val subtitle: String, val icon: Int = R.drawable.ic_autofill_key)

    /** Dataset carrying the real values: used only while the vault is unlocked. */
    @Suppress("DEPRECATION")
    fun direct(
        assignments: List<FieldAssignment>,
        ids: Map<String, AutofillId>,
        label: Label,
        inlineSpec: InlinePresentationSpec?,
        value: (FieldAssignment) -> String?,
    ): Dataset? {
        val b = Dataset.Builder(remoteViews(label))
        var any = false
        for (a in assignments) {
            val id = ids[a.fieldId] ?: continue
            val v = value(a) ?: continue
            b.setValue(id, AutofillValue.forText(v), remoteViews(label))
            any = true
        }
        if (!any) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) inline(label, inlineSpec)?.let { b.setInlinePresentation(it) }
        return b.build()
    }

    /** Dataset without values: tapping it opens [intent] (unlock, confirm, search) first. */
    @Suppress("DEPRECATION")
    fun authenticated(
        assignments: List<FieldAssignment>,
        ids: Map<String, AutofillId>,
        label: Label,
        inlineSpec: InlinePresentationSpec?,
        intent: Intent,
    ): Dataset? {
        val fieldIds = assignments.mapNotNull { ids[it.fieldId] }.distinct().ifEmpty { ids.values.take(1) }
        if (fieldIds.isEmpty()) return null
        val b = Dataset.Builder(remoteViews(label))
        fieldIds.forEach { b.setValue(it, null, remoteViews(label)) }
        b.setAuthentication(intentSender(intent))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) inline(label, inlineSpec)?.let { b.setInlinePresentation(it) }
        return b.build()
    }

    fun intentSender(intent: Intent): IntentSender =
        PendingIntent.getActivity(
            context,
            REQUEST_CODES.incrementAndGet(),
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender

    private fun remoteViews(label: Label): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, label.title)
            setTextViewText(R.id.autofill_subtitle, label.subtitle)
            setImageViewResource(R.id.autofill_icon, label.icon)
        }

    @SuppressLint("RestrictedApi")
    private fun inline(label: Label, spec: InlinePresentationSpec?): InlinePresentation? {
        if (spec == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
        val attribution = PendingIntent.getActivity(
            context,
            0,
            Intent(context, AutofillPickerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val slice = InlineSuggestionUi.newContentBuilder(attribution)
            .setTitle(label.title)
            .setSubtitle(label.subtitle)
            .setStartIcon(Icon.createWithResource(context, label.icon))
            .build()
            .slice
        return InlinePresentation(slice, spec, false)
    }

    private companion object {
        val REQUEST_CODES = java.util.concurrent.atomic.AtomicInteger(1000)
    }
}
