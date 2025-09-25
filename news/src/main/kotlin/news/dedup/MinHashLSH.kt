package news.dedup

import java.security.SecureRandom

class MinHashLSH(
    private val numHashes: Int = 64,
    private val bands: Int = 16,
    seed: Long = 1L
) {
    private val rowsPerBand = numHashes / bands
    private val prime = 2_147_483_647
    private val random = SecureRandom().apply { setSeed(seed) }
    private val coefficientsA = IntArray(numHashes) { random.nextInt(prime - 1) + 1 }
    private val coefficientsB = IntArray(numHashes) { random.nextInt(prime - 1) + 1 }

    init {
        require(numHashes % bands == 0) { "numHashes must be divisible by bands" }
    }

    fun signature(shingles: Set<String>): IntArray {
        val signature = IntArray(numHashes) { Int.MAX_VALUE }
        if (shingles.isEmpty()) return signature
        for (shingle in shingles) {
            val shingleHash = shingle.hashCode()
            for (i in 0 until numHashes) {
                val hash = ((coefficientsA[i].toLong() * (shingleHash and Int.MAX_VALUE) + coefficientsB[i]) % prime).toInt()
                if (hash < signature[i]) {
                    signature[i] = hash
                }
            }
        }
        return signature
    }

    fun bucketKeys(signature: IntArray): Sequence<String> {
        return sequence {
            for (band in 0 until bands) {
                val start = band * rowsPerBand
                val slice = signature.sliceArray(start until start + rowsPerBand)
                val key = slice.joinToString(separator = ":", prefix = "$band:")
                yield(key)
            }
        }
    }

    fun jaccardSimilarity(first: Set<String>, second: Set<String>): Double {
        if (first.isEmpty() && second.isEmpty()) return 1.0
        if (first.isEmpty() || second.isEmpty()) return 0.0
        val intersection = first.intersect(second).size.toDouble()
        val union = (first.size + second.size - intersection)
        if (union == 0.0) return 0.0
        return intersection / union
    }
}
