package dev.easyide.app.ui.screens.home

/**
 * The letters shown in a project's tile: initials of the first two words
 * ("my-app" -> "MA"), or the first two characters of a single word ("notes" ->
 * "NO"). Words split on anything that is not a letter or digit, so kebab, snake
 * and camel-free names all read naturally.
 */
fun monogramLetters(name: String): String {
    val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    val letters = when {
        words.isEmpty() -> name.trim().take(MONOGRAM_LENGTH)
        words.size == 1 -> words[0].take(MONOGRAM_LENGTH)
        else -> words.take(MONOGRAM_LENGTH).joinToString("") { it.take(1) }
    }
    return letters.uppercase()
}

/**
 * A stable bucket in `0 until buckets` for [name], so a project keeps its tile
 * colour across launches and renames that only change case. FNV-1a rather than
 * `String.hashCode()` because its distribution over short strings is better and
 * its definition is fixed - a tile colour must not change with a JDK.
 */
fun monogramBucket(name: String, buckets: Int): Int {
    require(buckets > 0) { "buckets must be positive" }
    var hash = FNV_OFFSET_BASIS
    for (char in name.trim().lowercase()) {
        hash = (hash xor char.code) * FNV_PRIME
    }
    return Math.floorMod(hash, buckets)
}

private const val MONOGRAM_LENGTH = 2
private const val FNV_OFFSET_BASIS = -0x7ee3623b // 2166136261 as a signed Int
private const val FNV_PRIME = 16777619
