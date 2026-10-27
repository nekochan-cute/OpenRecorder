package org.openrecorder.app.core

import kotlin.math.sqrt

/**
 * Speaker labels from per-utterance speaker embeddings, taken in the order the utterances were spoken, so captions
 * while recording and the transcript afterwards come from the same clustering.
 *
 * Each utterance joins the speaker whose running mean is nearest, if within [threshold] (cosine distance), or starts
 * a new one. The embeddings of single utterances vary a lot (emotion, distance, turning the head), so one person
 * was often counted as several: a voice therefore becomes a speaker only once it is heard a second time. Until then
 * it shows as the nearest confirmed speaker, and earlier utterances are relabelled when it is confirmed.
 */
class SpeakerClusters(private val threshold: Float = 0.5f, private val maxSpeakers: Int = 8, private val minFrames: Int = 100) {
    private val sums = ArrayList<FloatArray>()
    private val centroids = ArrayList<FloatArray>()
    private val counts = ArrayList<Int>()
    /** Cluster → speaker number, in order of confirmation. */
    private val number = LinkedHashMap<Int, Int>()
    /** Per utterance: its cluster, or -1 when too short or without an embedding. */
    private val cluster = ArrayList<Int>()
    private val units = ArrayList<FloatArray?>()
    private val frames = ArrayList<Int>()

    /** Adds the next utterance; [frames] is its length in 10 ms frames. */
    fun add(vector: FloatArray?, frames: Int) {
        val v = vector?.takeIf { it.isNotEmpty() }?.let(::unit)
        units += v; this.frames += frames
        if (v == null || (frames < minFrames && centroids.isNotEmpty())) { cluster += -1; return }
        var best = -1; var bestDistance = Float.MAX_VALUE
        centroids.forEachIndexed { i, c -> val d = 1f - dot(v, c); if (d < bestDistance) { best = i; bestDistance = d } }
        val index = if (best >= 0 && (bestDistance <= threshold || number.size >= maxSpeakers)) best else {
            sums += FloatArray(v.size); centroids += v.copyOf(); counts += 0; centroids.size - 1
        }
        val sum = sums[index]
        for (d in v.indices) sum[d] += v[d]
        centroids[index] = unit(sum); counts[index] += 1
        if (index !in number && (counts[index] >= 2 || number.isEmpty())) number[index] = number.size
        cluster += index
    }

    /**
     * Labels as they stand while recording: confirmed speakers by number, a voice heard once as the nearest
     * confirmed speaker, short or embedding-less utterances as the one before; -1 before anyone is heard.
     */
    fun current(): IntArray {
        var last = -1
        return IntArray(cluster.size) { i ->
            val c = cluster[i]
            if (c >= 0) last = number[c] ?: number.getValue(nearestConfirmed(centroids[c]))
            last
        }
    }

    /**
     * Labels once everything is heard: every utterance joins the nearest confirmed speaker's final centroid, which
     * corrects early choices made before the speakers had settled; a speaker left with a single reliable
     * utterance joins the nearest other one. Numbered in order of appearance; -1 only if nobody was heard.
     */
    fun final(): IntArray {
        if (number.isEmpty()) return IntArray(cluster.size) { -1 }
        val confirmed = number.entries.sortedBy { it.value }.map { centroids[it.key] }
        var last = -1
        val labels = IntArray(cluster.size) { i ->
            val v = units[i]
            if (cluster[i] >= 0 && v != null) last = confirmed.indices.maxBy { dot(v, confirmed[it]) }
            last
        }
        mergeSingles(labels)
        val first = labels.firstOrNull { it >= 0 } ?: -1
        for (i in labels.indices) { if (labels[i] >= 0) break; labels[i] = first }
        return labels
    }

    private fun nearestConfirmed(v: FloatArray): Int = number.keys.maxBy { dot(v, centroids[it]) }

    /** Speakers with fewer than two reliable utterances join the nearest other speaker, weakest first; then renumbers. */
    private fun mergeSingles(labels: IntArray) {
        while (true) {
            val ids = labels.filter { it >= 0 }.distinct().sorted()
            if (ids.size < 2) break
            val support = ids.associateWith { k -> labels.indices.count { labels[it] == k && frames[it] >= minFrames } }
            val weak = ids.filter { support.getValue(it) < 2 }
            if (weak.isEmpty()) break
            val total = ids.associateWith { k -> labels.indices.sumOf { if (labels[it] == k) frames[it] else 0 } }
            val small = weak.minWith(compareBy({ support.getValue(it) }, { total.getValue(it) }))
            val centre = ids.associateWith { k ->
                val sum = FloatArray(units.firstNotNullOf { it }.size)
                for (i in labels.indices) { val v = units[i]; if (labels[i] == k && v != null) for (d in v.indices) sum[d] += v[d] * frames[i] }
                unit(sum)
            }
            val target = ids.filter { it != small }.maxBy { dot(centre.getValue(it), centre.getValue(small)) }
            for (i in labels.indices) if (labels[i] == small) labels[i] = target
        }
        val order = LinkedHashMap<Int, Int>()
        for (i in labels.indices) if (labels[i] >= 0) labels[i] = order.getOrPut(labels[i]) { order.size }
    }

    companion object {
        /** "話者1", "話者2", …; empty for no speaker. */
        fun label(index: Int) = if (index < 0) "" else "話者${index + 1}"

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        private fun unit(v: FloatArray): FloatArray {
            var norm = 0f
            for (x in v) norm += x * x
            norm = sqrt(norm).takeIf { it > 0f } ?: 1f
            return FloatArray(v.size) { v[it] / norm }
        }
    }
}
