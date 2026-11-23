package com.framepersecond.m3bitwarden.android.autofill

import android.view.autofill.AutofillId
import android.widget.inline.InlinePresentationSpec
import com.framepersecond.m3bitwarden.autofill.FillPlan
import com.framepersecond.m3bitwarden.autofill.classify.FormIntent
import com.framepersecond.m3bitwarden.autofill.match.FillTarget
import java.util.UUID

/**
 * Hands state from the autofill service to its activities without putting it into intents.
 * Entries live only in this process's memory and expire after a few minutes.
 */
object AutofillSessions {

    class Fill(
        val plan: FillPlan?,
        val target: FillTarget,
        val app: RequestingApp,
        val ids: Map<String, AutofillId>,
        val inlineSpec: InlinePresentationSpec?,
        val created: Long = System.currentTimeMillis(),
    )

    /** What the user submitted. Held only until the save sheet is answered or times out. */
    class Save(
        val username: String?,
        val password: String?,
        val intent: FormIntent,
        val target: FillTarget,
        val app: RequestingApp,
        val created: Long = System.currentTimeMillis(),
    )

    private val fills = HashMap<String, Fill>()
    private val saves = HashMap<String, Save>()

    @Synchronized
    fun put(fill: Fill): String = UUID.randomUUID().toString().also { prune(); fills[it] = fill }

    @Synchronized
    fun put(save: Save): String = UUID.randomUUID().toString().also { prune(); saves[it] = save }

    @Synchronized
    fun fill(token: String?): Fill? = token?.let { fills[it] }

    @Synchronized
    fun takeSave(token: String?): Save? = token?.let { saves.remove(it) }

    @Synchronized
    fun clear() {
        fills.clear()
        saves.clear()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        fills.entries.removeAll { it.value.created < cutoff }
        saves.entries.removeAll { it.value.created < cutoff }
    }

    private const val TTL_MS = 5 * 60 * 1000L
}
