package news.dedup

object Shingles {
    fun generate(tokens: List<String>, minSize: Int = 5, maxSize: Int = 8): Set<String> {
        if (tokens.isEmpty() || minSize <= 0) return emptySet()
        val normalizedMax = maxOf(minSize, maxSize)
        val result = LinkedHashSet<String>()
        for (size in minSize..normalizedMax) {
            if (tokens.size < size) break
            for (index in 0..tokens.size - size) {
                val shingle = tokens.subList(index, index + size).joinToString(separator = " ")
                result.add(shingle)
            }
        }
        return result
    }
}
