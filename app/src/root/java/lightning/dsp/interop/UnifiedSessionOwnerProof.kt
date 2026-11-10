package lightning.dsp.interop

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded callback challenge ties a remote owner token to the provider's actual Binder caller. */
internal class UnifiedSessionOwnerProof(private val onRevoked: () -> Unit) {
    private class Owner(val token: IBinder, val dead: AtomicBoolean, val death: IBinder.DeathRecipient, val onRevoked: () -> Unit) {
        @Volatile var expires = 0L
        fun alive() = !dead.get() && SystemClock.elapsedRealtime() < expires
        fun close() { dead.set(true); runCatching { token.unlinkToDeath(death, 0) }; onRevoked() }
    }
    private val owners = linkedMapOf<Pair<Int, Int>, Owner>()

    @Synchronized fun verify(token: IBinder, uid: Int, pid: Int, deadline: Long): (() -> Boolean)? {
        owners.entries.removeAll { (_, owner) -> if (!owner.alive()) { owner.close(); true } else false }
        val key = uid to pid
        var owner = owners[key]
        if (owner != null && owner.token != token) { owner.close(); owners.remove(key); owner = null }
        if (owner == null) {
            if (owners.size >= 32 || token is Binder) return null
            val dead = AtomicBoolean(false)
            val death = IBinder.DeathRecipient { dead.set(true); onRevoked() }
            try { token.linkToDeath(death, 0) } catch (_: Exception) { return null }
            owner = Owner(token, dead, death, onRevoked)
            owners[key] = owner
        }
        val verified = AtomicBoolean(false)
        val completed = CountDownLatch(1)
        val ack = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != CODE) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(DESCRIPTOR)
                if (UnifiedSessionOwnerAdmission.matches(uid, pid, getCallingUid(), getCallingPid())) verified.set(true)
                completed.countDown()
                return true
            }
        }
        val request = Parcel.obtain()
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeStrongBinder(ack)
            // The untrusted app must not be able to block a provider worker indefinitely.
            val sent = token.transact(CODE, request, null, IBinder.FLAG_ONEWAY)
            val acknowledged = sent && completed.await(
                minOf(400L, maxOf(0, deadline - SystemClock.elapsedRealtime())), TimeUnit.MILLISECONDS)
            if (!UnifiedSessionOwnerAdmission.accepts(acknowledged, verified.get(), owner.dead.get(),
                    SystemClock.elapsedRealtime(), deadline)) {
                owner.close(); owners.remove(key); return null
            }
            owner.expires = SystemClock.elapsedRealtime() + 6_000
            return owner::alive
        } catch (_: Exception) {
            owner.close(); owners.remove(key); return null
        } finally { request.recycle() }
    }

    companion object {
        const val DESCRIPTOR = "lightning.dsp.UnifiedSessionOwner"
        const val CODE = IBinder.FIRST_CALL_TRANSACTION + 731
    }
}
