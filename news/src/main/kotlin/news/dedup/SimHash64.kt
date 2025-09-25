package news.dedup

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.text.Charsets

object SimHash64 {
    private val digestThreadLocal: ThreadLocal<MessageDigest> = ThreadLocal.withInitial {
        MessageDigest.getInstance("SHA-256")
    }

    fun hash(shingles: Set<String>): Long {
        if (shingles.isEmpty()) return 0L
        val vector = IntArray(64)
        for (shingle in shingles) {
            val hash = hash64(shingle)
            for (bit in 0 until 64) {
                val mask = 1L shl bit
                if (hash and mask != 0L) {
                    vector[bit] += 1
                } else {
                    vector[bit] -= 1
                }
            }
        }
        var result = 0L
        for (bit in 0 until 64) {
            if (vector[bit] > 0) {
                result = result or (1L shl bit)
            }
        }
        return result
    }

    fun hammingDistance(left: Long, right: Long): Int {
        var value = left xor right
        var distance = 0
        while (value != 0L) {
            value = value and (value - 1)
            distance++
        }
        return distance
    }

    private fun hash64(value: String): Long {
        val digest = digestThreadLocal.get()
        digest.reset()
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.wrap(bytes, 0, 8)
        return buffer.long
    }
}
