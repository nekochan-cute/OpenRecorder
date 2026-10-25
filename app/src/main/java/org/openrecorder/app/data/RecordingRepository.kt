package org.openrecorder.app.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.openrecorder.app.audio.AudioCodec
import org.openrecorder.app.audio.Mp3
import org.openrecorder.app.audio.RecordFormat
import org.openrecorder.app.audio.durationMs
import org.openrecorder.app.audio.remuxAdts
import org.openrecorder.app.core.*
import java.io.File
import java.util.UUID

class RecordingRepository(private val context: Context) {
    val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
    private val photoDir = File(context.filesDir, "photos").apply { mkdirs() }
    private val levelsDir = File(context.filesDir, "levels").apply { mkdirs() }
    private val wavCache = File(context.cacheDir, "wav")
    private val index = AtomicFile(File(context.filesDir, "recordings.json"))
    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings = _recordings.asStateFlow()
    val warning = MutableStateFlow<String?>(null)

    init {
        try {
            if (index.baseFile.exists() || File(index.baseFile.path + ".bak").exists()) {
                val a = JSONArray(index.openRead().bufferedReader().use { it.readText() })
                _recordings.value = (0 until a.length()).map { decode(a.getJSONObject(it)) }
            }
        } catch (e: Exception) {
            // Preserve corrupt metadata. Audio is never deleted on an index failure.
            runCatching { index.baseFile.takeIf { it.exists() }?.copyTo(File(context.filesDir, "recordings-corrupt-${System.currentTimeMillis()}.json")) }
            warning.value = "管理データを読み取れませんでした。音声ファイルを回収します。"
        }
    }

    fun file(r: Recording) = File(audioDir, r.fileName).also { require(it.canonicalFile.parentFile == audioDir.canonicalFile) }
    fun get(id: String) = _recordings.value.firstOrNull { it.id == id }
    fun format(r: Recording) = RecordFormat.ofFile(r.fileName)
    /** The recording as 16-bit WAV: the file itself, or a cached decode of an M4A/MP3 recording. */
    fun wav(r: Recording, progress: (Float) -> Unit = {}): File {
        if (format(r) == RecordFormat.Wav) return file(r)
        val cached = File(wavCache.apply { mkdirs() }, "${r.id}.wav")
        if (cached.isFile && cached.lastModified() >= file(r).lastModified()) return cached
        val part = File(wavCache, "${r.id}.part")
        AudioCodec.decodeToWav(file(r), part, progress)
        check(part.renameTo(cached))
        return cached
    }
    /** Waveform levels at 20 per second, computed once per recording and cached. */
    fun levels(r: Recording): ByteArray {
        val cache = File(levelsDir, "${r.id}.bin")
        if (cache.isFile && cache.lastModified() >= file(r).lastModified()) return cache.readBytes()
        return Levels.read(wav(r)).also { runCatching { cache.writeBytes(it) } }
    }
    fun saveLevels(id: String, levels: ByteArray) = runCatching { File(levelsDir, "$id.bin").writeBytes(levels) }

    /** Stores a rendered WAV as a new recording file in [format]; returns the file name and audio info. */
    private fun store(id: String, rendered: File, format: RecordFormat, progress: (Float) -> Unit = {}): Pair<String, WavInfo> {
        val info = Wav.inspect(rendered)
        val target = File(audioDir, "$id.${format.extension}")
        try {
            when (format) {
                RecordFormat.Wav -> check(rendered.renameTo(target) || run { rendered.copyTo(target, true); true })
                RecordFormat.Aac -> AudioCodec.encodeAac(rendered, target, progress)
                RecordFormat.Mp3 -> Mp3.encode(rendered, target, progress)
            }
        } finally { if (format != RecordFormat.Wav) rendered.delete() }
        return target.name to info
    }
    private fun temp(id: String) = File(audioDir, "$id.rendering.wav")

    @Synchronized private fun commit(next: List<Recording>) {
        val bytes = JSONArray().apply { next.forEach { put(encode(it)) } }.toString().toByteArray()
        val out = index.startWrite()
        try { out.write(bytes); index.finishWrite(out) } catch (e: Exception) { index.failWrite(out); throw e }
        _recordings.value = next.sortedByDescending { it.createdAt }
    }
    @Synchronized fun add(r: Recording) { require(get(r.id) == null); commit(_recordings.value + r) }
    @Synchronized fun update(id: String, change: (Recording) -> Recording) {
        commit(_recordings.value.map { if (it.id == id) change(it) else it })
    }
    /** Recordings this process is capturing right now. Their files are still being written, so recovery leaves them alone. */
    private val capturing = mutableSetOf<String>()

    /** AAC is written as ADTS (.aac) while recording and becomes .m4a in [complete]. Call [endCapture] once the file is closed. */
    @Synchronized fun create(rate: Int, channels: Int, format: RecordFormat = RecordFormat.Wav): Recording {
        val id = UUID.randomUUID().toString()
        val ext = if (format == RecordFormat.Aac) "aac" else format.extension
        val r = Recording(id = id, title = "録音 " + java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.JAPAN).format(java.util.Date()),
            fileName = "$id.$ext", sampleRate = rate, channels = channels, unfinished = true)
        if (format == RecordFormat.Wav) file(r).writeBytes(Wav.header(0, rate, channels)) else file(r).writeBytes(ByteArray(0))
        capturing += id
        try { add(r) } catch (e: Exception) { capturing -= id; file(r).delete(); throw e }
        return r
    }
    /** The capture of [id] has ended; if it was not completed, the next [recoverInterrupted] repairs it. */
    @Synchronized fun endCapture(id: String) { capturing -= id }
    /** Finalises a recording after a clean stop. */
    fun complete(id: String, durationMs: Long, segments: List<Segment>?, levels: ByteArray?) {
        val r = get(id) ?: return
        var fileName = r.fileName; var duration = durationMs
        when (r.fileName.substringAfterLast('.')) {
            "wav" -> duration = Wav.repair(file(r), r.sampleRate, r.channels).durationMs
            "aac" -> runCatching {
                val m4a = File(audioDir, "$id.m4a"); AudioCodec.remuxAdts(file(r), m4a); file(r).delete(); fileName = m4a.name
            }
        }
        update(id) { it.copy(fileName = fileName, durationMs = duration, unfinished = false, segments = segments ?: it.segments) }
        levels?.let { saveLevels(id, it) }
    }
    fun mark(id: String, time: Long, text: String, photo: String? = null) {
        update(id) { it.copy(marks = (it.marks + Mark(timeMs = time, text = text, photo = photo)).sortedBy(Mark::timeMs)) }
    }
    fun photo(uri: Uri): String {
        val name = "${UUID.randomUUID()}.jpg"
        val dest = File(photoDir, name)
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { output ->
                    val b = ByteArray(64 * 1024); var total = 0L
                    while (true) { val n = input.read(b); if (n < 0) break; total += n; require(total <= 32L * 1024 * 1024) { "画像は32MBまでです" }; output.write(b,0,n) }
                }
            }
            return name
        } catch (e: Exception) { dest.delete(); throw e }
    }
    fun photoFile(name: String) = File(photoDir, name).also { require(it.canonicalFile.parentFile == photoDir.canonicalFile) }

    fun edit(id: String, spans: List<Span>): Recording {
        val old = get(id) ?: error("録音がありません")
        require(!old.unfinished && old.deletedAt == null)
        val newId = UUID.randomUUID().toString()
        Wav.edit(wav(old), temp(newId), spans)
        val (name, info) = store(newId, temp(newId), format(old))
        val r = old.copy(id = newId, title = old.title + "（編集）", fileName = name,
            createdAt = System.currentTimeMillis(), durationMs = info.durationMs,
            marks = Timeline.marks(old.marks, spans), segments = Timeline.segments(old.segments, spans),
            summary = "", sourceId = old.id, peaks = emptyList())
        try { add(r) } catch (e: Exception) { File(audioDir, name).delete(); throw e }
        return r
    }
    /**
     * Imports any audio the device can decode. 16-bit PCM WAV is kept byte for byte; other
     * formats (M4A, MP3, OGG, FLAC, AMR…) are decoded once to WAV so editing stays sample exact.
     */
    fun importAudio(uri: Uri, title: String, progress: (Float) -> Unit = {}): Recording {
        val id = UUID.randomUUID().toString(); val dest = File(audioDir, "$id.wav")
        val staged = File(context.cacheDir, "import-$id").also { it.parentFile?.mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                staged.outputStream().use { output ->
                    val b = ByteArray(64 * 1024); var size = 0L
                    while (true) { val n = input.read(b); if (n < 0) break; size += n; require(size < Wav.MAX_BYTES) { "ファイルが大きすぎます" }; output.write(b,0,n) }
                }
            }
            val wav = runCatching { Wav.inspect(staged) }.getOrNull()?.takeIf { it.bytes > 0 }
            val info = if (wav != null) {
                if (!staged.renameTo(dest)) { staged.copyTo(dest, overwrite = true) }
                Wav.inspect(dest)
            } else AudioCodec.decodeToWav(staged, dest, progress)
            require(info.bytes > 0) { "音声が空です" }
            val r = Recording(id = id, title = title.ifBlank { "取り込んだ録音" }, fileName = dest.name,
                durationMs = info.durationMs, sampleRate = info.sampleRate, channels = info.channels)
            add(r); return r
        } catch (e: Exception) { dest.delete(); throw e }
        finally { staged.delete() }
    }
    /** Noise reduction / voice enhancement / loudness on a copy. The timeline is unchanged, so marks and text carry over. */
    fun process(id: String, options: ProcessOptions, progress: (Float) -> Unit = {}): Recording {
        val old = get(id) ?: error("録音がありません")
        require(!old.unfinished && old.deletedAt == null)
        val newId = UUID.randomUUID().toString()
        val source = wav(old)
        require(audioDir.usableSpace > source.length() * 2 + 32L * 1024 * 1024) { "空き容量が足りません" }
        AudioProcessor.process(source, temp(newId), options) { progress(it * 0.9f) }
        val (name, info) = store(newId, temp(newId), format(old)) { progress(0.9f + it * 0.1f) }
        val r = old.copy(id = newId, title = old.title + "（音声処理）", fileName = name, createdAt = System.currentTimeMillis(),
            durationMs = info.durationMs, sourceId = old.id, favorite = false, peaks = emptyList())
        try { add(r) } catch (e: Exception) { File(audioDir, name).delete(); throw e }
        return r
    }
    /**
     * Joins recordings in recording order into a new one. Rate and channels follow the highest of the
     * sources; marks and transcripts move to their place in the joined timeline.
     */
    fun merge(ids: List<String>, progress: (Float) -> Unit = {}): Recording {
        val parts = ids.mapNotNull(::get).filter { !it.unfinished && it.deletedAt == null }.sortedBy { it.createdAt }
        require(parts.size >= 2) { "2件以上の録音を選択してください" }
        val wavs = parts.mapIndexed { i, r -> wav(r) { progress(it * 0.3f * (i + 1) / parts.size) } }
        val infos = wavs.map { Wav.inspect(it) }
        val newId = UUID.randomUUID().toString()
        Concat.join(wavs, temp(newId), infos.maxOf { it.sampleRate }, infos.maxOf { it.channels }) { progress(0.3f + it * 0.6f) }
        val (name, info) = store(newId, temp(newId), format(parts.first())) { progress(0.9f + it * 0.1f) }
        val offsets = Concat.offsets(infos.map { it.durationMs })
        val r = Recording(id = newId, title = "${parts.first().title}（結合）", fileName = name, durationMs = info.durationMs,
            sampleRate = info.sampleRate, channels = info.channels, folder = parts.first().folder,
            marks = parts.zip(offsets).flatMap { (p, o) -> p.marks.map { it.copy(timeMs = it.timeMs + o) } },
            segments = parts.zip(offsets).flatMap { (p, o) -> p.segments.map { it.copy(startMs = it.startMs + o, endMs = it.endMs + o) } })
        try { add(r) } catch (e: Exception) { File(audioDir, name).delete(); throw e }
        return r
    }
    @Synchronized fun permanentlyDelete(id: String) {
        val r = get(id) ?: return
        require(r.deletedAt != null && !r.unfinished)
        val f = file(r)
        val tomb = File(audioDir, "${r.id}.deleted")
        if(f.exists()) check(f.renameTo(tomb)) { "音声を削除できませんでした" }
        try { commit(_recordings.value.filterNot { it.id == id }) }
        catch(e: Exception) { if(tomb.exists()) tomb.renameTo(f); throw e }
        tomb.delete()
        File(levelsDir, "${r.id}.bin").delete(); File(wavCache, "${r.id}.wav").delete()
        val used = _recordings.value.flatMap { it.marks }.mapNotNull { it.photo }.toSet()
        r.marks.mapNotNull { it.photo }.filterNot { it in used }.forEach { photoFile(it).delete() }
    }
    @Synchronized fun recoverInterrupted() {
        var all = _recordings.value
        // Roll back a delete interrupted before metadata commit; finish one committed before a crash.
        audioDir.listFiles()?.filter { it.extension == "deleted" }?.forEach { tomb ->
            val r = all.firstOrNull { it.id == tomb.nameWithoutExtension }
            if(r == null) tomb.delete() else if(!file(r).exists()) check(tomb.renameTo(file(r)))
        }
        all = all.map { r ->
            if (!r.unfinished || r.id in capturing) r else {
                try {
                    when (r.fileName.substringAfterLast('.')) {
                        "wav" -> r.copy(durationMs = Wav.repair(file(r), r.sampleRate, r.channels).durationMs, unfinished = false, title = r.title + "（中断から回収）")
                        "aac" -> {
                            // ADTS frames are self-contained: everything written before the crash is playable.
                            val m4a = File(audioDir, "${r.id}.m4a")
                            val name = runCatching { AudioCodec.remuxAdts(file(r), m4a); file(r).delete(); m4a.name }.getOrDefault(r.fileName)
                            r.copy(fileName = name, durationMs = AudioCodec.durationMs(File(audioDir, name)), unfinished = false, title = r.title + "（中断から回収）")
                        }
                        else -> r.copy(durationMs = AudioCodec.durationMs(file(r)), unfinished = false, title = r.title + "（中断から回収）")
                    }
                } catch (e: Exception) { warning.value = "回収できない録音があります。元ファイルは保持しています。"; r.copy(unfinished = false) }
            }
        }
        val names = all.map { it.fileName }.toSet()
        audioDir.listFiles()?.filter { it.extension == "wav" && it.name !in names }?.forEach { f ->
            runCatching {
                val info = Wav.inspect(f)
                all = all + Recording(title = "回収した録音", fileName = f.name, durationMs = info.durationMs,
                    sampleRate = info.sampleRate, channels = info.channels, peaks = Wav.peaks(f))
            }
        }
        if (all != _recordings.value) commit(all)
    }
    private fun encode(r: Recording) = JSONObject().apply {
        put("id",r.id); put("title",r.title); put("createdAt",r.createdAt); put("fileName",r.fileName)
        put("durationMs",r.durationMs); put("sampleRate",r.sampleRate); put("channels",r.channels)
        put("folder",r.folder); put("favorite",r.favorite); put("deletedAt",r.deletedAt ?: JSONObject.NULL)
        put("summary",r.summary); put("sourceId",r.sourceId ?: JSONObject.NULL); put("unfinished",r.unfinished); put("translationLanguage",r.translationLanguage)
        put("peaks", JSONArray(r.peaks))
        put("marks",JSONArray().apply { r.marks.forEach { m -> put(JSONObject().apply {
            put("id",m.id); put("timeMs",m.timeMs); put("text",m.text); put("photo",m.photo ?: JSONObject.NULL)
        }) } })
        put("segments",JSONArray().apply { r.segments.forEach { s -> put(JSONObject().apply {
            put("startMs",s.startMs); put("endMs",s.endMs); put("text",s.text); put("speaker",s.speaker); if(s.translation.isNotEmpty()) put("translation",s.translation)
        }) } })
    }
    private fun decode(j: JSONObject): Recording {
        fun array(key: String) = j.optJSONArray(key) ?: JSONArray()
        val marks = array("marks"); val segments = array("segments"); val peaks = array("peaks")
        return Recording(id = j.getString("id"), title = j.getString("title"), createdAt = j.getLong("createdAt"),
            fileName = j.getString("fileName"), durationMs = j.optLong("durationMs"), sampleRate = j.optInt("sampleRate",48000), channels = j.optInt("channels",1),
            folder = j.optString("folder"), favorite = j.optBoolean("favorite"), deletedAt = if (j.isNull("deletedAt")) null else j.getLong("deletedAt"),
            summary = j.optString("summary"), translationLanguage = j.optString("translationLanguage"), sourceId = if(j.isNull("sourceId")) null else j.getString("sourceId"), unfinished = j.optBoolean("unfinished"),
            peaks = (0 until peaks.length()).map { peaks.getDouble(it).toFloat().coerceIn(0f,1f) },
            marks = (0 until marks.length()).map { marks.getJSONObject(it).let { m -> Mark(m.getString("id"),m.getLong("timeMs"),m.getString("text"),if(m.isNull("photo")) null else m.getString("photo")) } },
            segments = (0 until segments.length()).map { segments.getJSONObject(it).let { s -> Segment(s.getLong("startMs"),s.getLong("endMs"),s.getString("text"),s.optString("speaker"),s.optString("translation")) } })
    }
}
