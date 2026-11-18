package com.usagelimits.statusicon

import com.usagelimits.core.model.MAX_PINNED_ACCOUNTS
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Every change to which accounts have status icons, applied one at a time: stored, then
 * published, before the next change is stored.
 *
 * The status icon step tells "turned on again" and "pinned again" from "unchanged" by what the
 * previous publication showed, so it must see each intermediate value. Two quick taps, off then
 * on, used to be two jobs: the second could store "on" while the first one's publication was still
 * repainting widgets, no status step ever read "off", and an icon the person had swiped away stayed
 * away.
 *
 * A pin change is one account and whether it should be pinned, applied to the list stored at the
 * moment it runs. A change waiting behind another one therefore cannot carry an older list back:
 * pinning C and then unpinning A while a publication runs leaves B and C, not whatever the screen
 * showed when each tap was made.
 *
 * The rules a pin must meet are applied here too, against what is stored and present then: an
 * account that no longer exists is not pinned and drops out of the list, and a fourth pin is
 * refused. Settings normally prevents both; a change that waited in the queue may no longer
 * meet them when it runs.
 */
class StatusIconChanges(
    private val readPinned: suspend () -> List<String>,
    private val writePinned: suspend (List<String>) -> Unit,
    private val writeEnabled: suspend (Boolean) -> Unit,
    /** The ids of the usage accounts that exist now, in the app's order. */
    private val usageAccountIds: suspend () -> List<String>,
    /** The publication that follows each stored change: a presentation change. */
    private val publish: suspend () -> Unit,
) {
    private val sequence = Mutex()

    suspend fun setEnabled(enabled: Boolean) = inTurn { writeEnabled(enabled) }

    /**
     * Pins or unpins [accountId]. Returns false when a pin is refused, because the account is
     * gone or three are pinned already; nothing is stored or published then.
     */
    suspend fun setPinned(accountId: String, pinned: Boolean): Boolean = sequence.withLock {
        val present = usageAccountIds().toSet()
        val stored = readPinned().filter { it in present }
        val next = when {
            !pinned -> stored - accountId
            accountId in stored -> stored
            accountId !in present || stored.size >= MAX_PINNED_ACCOUNTS -> return@withLock false
            else -> stored + accountId
        }
        writePinned(next)
        publish()
        true
    }

    /** Releases the pin of an account that was removed, so it no longer counts. */
    suspend fun release(accountId: String) {
        setPinned(accountId, pinned = false)
    }

    private suspend fun inTurn(store: suspend () -> Unit) = sequence.withLock {
        store()
        publish()
    }
}
