package io.github.whywood.deepdeep.plugin.micloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class MiCloudSessionState {
    SIGNED_OUT,
    VERIFIED,
    EXPIRED,
    UNVERIFIED,
}

internal data class MiCloudSessionView(
    val state: MiCloudSessionState,
    val nickname: String?,
    val checkedAt: Long?,
)

internal class MiCloudSession(
    private val cookie: suspend () -> String?,
    private val probe: suspend () -> MiCloudProfile,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 60_000L,
    private val expiredTtlMillis: Long = 600_000L,
) {
    private val mutex = Mutex()
    private val stateLock = Any()
    private var cache: MiCloudSessionView? = null
    private var lastVerified: MiCloudSessionView? = null
    private var fingerprint: String? = null
    private var fingerprintKnown = false
    private var revision = 0L

    suspend fun status(force: Boolean = false): MiCloudSessionView = mutex.withLock {
        resolveStatus(force)
    }

    fun cached(): MiCloudSessionView? = synchronized(stateLock) { cache }

    private suspend fun resolveStatus(force: Boolean): MiCloudSessionView {
        while (true) {
            val current = cookie()?.takeIf { it.isNotBlank() }
            val (version, previous) = synchronized(stateLock) {
                if (current == null) return signedOut()
                if (fingerprintKnown && fingerprint != current) {
                    cache = null
                    lastVerified = null
                    revision++
                }
                fingerprint = current
                fingerprintKnown = true
                cache?.let { if (!force && isFresh(it)) return it }
                revision to (lastVerified ?: cache)
            }
            val view = try {
                val profile = probe()
                MiCloudSessionView(MiCloudSessionState.VERIFIED, profile.nickname, now())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: MiCloudAuthException) {
                MiCloudSessionView(MiCloudSessionState.EXPIRED, previous?.nickname, now())
            } catch (_: Exception) {
                MiCloudSessionView(MiCloudSessionState.UNVERIFIED, previous?.nickname, previous?.checkedAt)
            }
            if (cookie()?.takeIf { it.isNotBlank() } != current) continue
            return synchronized(stateLock) {
                if (revision != version) return@synchronized checkNotNull(cache)
                cache = view
                if (view.state == MiCloudSessionState.VERIFIED) lastVerified = view
                view
            }
        }
    }

    fun markVerified(profile: MiCloudProfile) {
        synchronized(stateLock) {
            val view = MiCloudSessionView(MiCloudSessionState.VERIFIED, profile.nickname, now())
            cache = view
            lastVerified = view
            revision++
        }
    }

    fun markExpired() {
        synchronized(stateLock) {
            cache = MiCloudSessionView(
                MiCloudSessionState.EXPIRED,
                lastVerified?.nickname ?: cache?.nickname,
                now(),
            )
            revision++
        }
    }

    fun markSignedOut() {
        synchronized(stateLock) { signedOut() }
    }

    private fun signedOut(): MiCloudSessionView {
        val view = MiCloudSessionView(MiCloudSessionState.SIGNED_OUT, null, null)
        cache = view
        lastVerified = null
        fingerprint = null
        fingerprintKnown = false
        revision++
        return view
    }

    private fun isFresh(view: MiCloudSessionView): Boolean {
        val checkedAt = view.checkedAt ?: return false
        val age = now() - checkedAt
        return when (view.state) {
            MiCloudSessionState.VERIFIED -> age < ttlMillis
            MiCloudSessionState.EXPIRED -> age < expiredTtlMillis
            else -> false
        }
    }
}
