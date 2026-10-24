package org.openrecorder.app.core

import kotlin.math.sqrt

/**
 * Offline speaker clustering for per-utterance speaker embeddings.
 * Leader clustering seeds speakers from reliable (long) utterances, k-means style passes refine
 * the centroids, close clusters are merged, and short or vector-less utterances inherit the
 * nearest speaker. Linear in the number of utterances, so hour-long recordings stay fast.
 */
object Diarization {
    data class Utterance(val vector: FloatArray?, val frames: Int)

    /** Returns a speaker index per utterance, numbered 0.. in order of first appearance; -1 if nothing was clusterable. */
    fun cluster(utterances: List<Utterance>, threshold: Float = 0.5f, maxSpeakers: Int = 8, minFrames: Int = 100): IntArray {
        val vectors = utterances.map { u -> u.vector?.takeIf { it.isNotEmpty() }?.let(::normalize) }
        val reliable = vectors.indices.filter { vectors[it] != null && utterances[it].frames >= minFrames }
            .ifEmpty { vectors.indices.filter { vectors[it] != null } }
        if (reliable.isEmpty()) return IntArray(utterances.size) { -1 }
        val dim = vectors[reliable.first()]!!.size
        var centroids = mutableListOf<FloatArray>()
        for (i in reliable) {
            val v = vectors[i]!!
            if (v.size != dim) continue
            val (best, distance) = nearest(v, centroids)
            if (best < 0 || (distance > threshold && centroids.size < maxSpeakers)) centroids += v.copyOf()
        }
        repeat(5) {
            val sums = List(centroids.size) { FloatArray(dim) }
            val counts = IntArray(centroids.size)
            for (i in reliable) {
                val v = vectors[i]!!.takeIf { it.size == dim } ?: continue
                val c = nearest(v, centroids).first
                for (d in 0 until dim) sums[c][d] += v[d] * utterances[i].frames.coerceAtLeast(1)
                counts[c]++
            }
            centroids = sums.indices.filter { counts[it] > 0 }.map { normalize(sums[it]) }.toMutableList()
        }
        // Merge clusters whose centroids ended up closer than the threshold.
        var merged = true
        while (merged && centroids.size > 1) {
            merged = false
            loop@ for (a in centroids.indices) for (b in a + 1 until centroids.size) {
                if (distance(centroids[a], centroids[b]) < threshold * 0.8f) {
                    centroids[a] = normalize(FloatArray(dim) { centroids[a][it] + centroids[b][it] })
                    centroids.removeAt(b); merged = true; break@loop
                }
            }
        }
        val raw = IntArray(utterances.size) { i -> vectors[i]?.takeIf { it.size == dim }?.let { nearest(it, centroids).first } ?: -1 }
        // Vector-less utterances take the previous speaker, or the next one at the start.
        for (i in raw.indices) if (raw[i] < 0) raw[i] = (i - 1 downTo 0).firstOrNull { raw[it] >= 0 }?.let { raw[it] }
            ?: (i + 1 until raw.size).firstOrNull { raw[it] >= 0 }?.let { raw[it] } ?: -1
        val order = LinkedHashMap<Int, Int>()
        return IntArray(raw.size) { i -> if (raw[i] < 0) -1 else order.getOrPut(raw[i]) { order.size } }
    }

    /** Unit-length copy, so cosine distance is 1 - dot product. */
    fun unit(v: FloatArray): FloatArray = normalize(v)

    fun distance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return 1f - dot
    }

    private fun nearest(v: FloatArray, centroids: List<FloatArray>): Pair<Int, Float> {
        var best = -1; var bestDistance = Float.MAX_VALUE
        centroids.forEachIndexed { i, c -> val d = distance(v, c); if (d < bestDistance) { best = i; bestDistance = d } }
        return best to bestDistance
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm).takeIf { it > 0f } ?: 1f
        return FloatArray(v.size) { v[it] / norm }
    }

    fun label(index: Int) = if (index < 0) "" else "話者${index + 1}"
}

/**
 * Speaker assignment while recording: each new utterance joins the nearest known speaker, or starts
 * a new one when nobody is close. Centroids are running means, so speakers stabilise as they talk.
 * The final transcript is re-clustered offline with [Diarization.cluster] when the recording ends.
 */
class OnlineDiarizer(private val threshold: Float = 0.5f, private val maxSpeakers: Int = 8, private val minFrames: Int = 100) {
    private val sums = ArrayList<FloatArray>()
    private val centroids = ArrayList<FloatArray>()
    private var last = -1

    /** Returns the speaker index, or the previous speaker for utterances too short to judge. */
    fun assign(vector: FloatArray?, frames: Int): Int {
        if (vector == null || vector.isEmpty() || (frames < minFrames && centroids.isNotEmpty())) return last.coerceAtLeast(if (centroids.isEmpty()) -1 else 0)
        val v = Diarization.unit(vector)
        var best = -1; var bestDistance = Float.MAX_VALUE
        centroids.forEachIndexed { i, c -> val d = Diarization.distance(v, c); if (d < bestDistance) { best = i; bestDistance = d } }
        val index = if (best >= 0 && (bestDistance <= threshold || centroids.size >= maxSpeakers)) best else {
            sums += FloatArray(v.size); centroids += v.copyOf(); centroids.size - 1
        }
        val sum = sums[index]
        for (d in v.indices) sum[d] += v[d]
        centroids[index] = Diarization.unit(sum)
        last = index
        return index
    }
}
