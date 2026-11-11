package org.openrecorder.app.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Recording situations the automatic mode can tell apart by sound. */
enum class Scene { Conference, Meeting, Memo, Ambient }

/**
 * Listens to the start of a recording and decides its [Scene].
 *
 * Ported operation for operation from the Python reference used for tuning. 48 kHz input is decimated to
 * 16 kHz by two cascaded 3-tap box filters; analysis frames are 512 samples (32 ms) with a 160
 * sample hop and a periodic Hann window. Window features cover the last 3 s. A linear
 * discriminant (fitted on simulated phone captures) picks the scene; the decision is committed
 * once its margin exceeds [MARGIN] after [DECIDE_AFTER] seconds, and at [DEADLINE] at the latest.
 *
 * All buffers are allocated in the constructor; [feed] allocates nothing.
 */
class SceneDetector(private val rate: Int) {
    init {
        require(rate == RATE16 || rate == RATE48) { "rate must be 16000 or 48000" }
    }

    private val decim = rate / RATE16

    // ------------------------------------------------------------ constants
    private companion object {
        const val RATE16 = 16000
        const val RATE48 = 48000
        const val FRAME = 512
        const val HOP = 160
        const val WINDOW = 300
        const val LO_BIN = 3
        const val HI_BIN = 191
        const val BAND_BINS = HI_BIN - LO_BIN + 1
        const val EPS = 1e-20f
        const val MARGIN = 1.0f
        const val DECIDE_AFTER = 4.5
        const val CHECK_FIRST = 3.2
        const val CHECK_STEP = 0.5
        const val DEADLINE = 9.9
    }

    private val order = arrayOf(Scene.Conference, Scene.Meeting, Scene.Memo, Scene.Ambient)

    // ------------------------------------------------------------ input path
    private var xPrev1 = 0f
    private var xPrev2 = 0f
    private var f1Prev1 = 0f
    private var f1Prev2 = 0f
    private var inputIndex = 0L
    private var totalSamples = 0L

    private val frameRing = FloatArray(FRAME)
    private var frameWrite = 0
    private var frameFill = 0
    private var sinceHop = 0
    private var framesDone = 0

    // ------------------------------------------------------------ frame spectra
    private val hann = FloatArray(FRAME) { 0.5f - 0.5f * cos(2.0 * PI * it / FRAME).toFloat() }
    private val cosTab = FloatArray(FRAME / 2) { cos(2.0 * PI * it / FRAME).toFloat() }
    private val sinTab = FloatArray(FRAME / 2) { -sin(2.0 * PI * it / FRAME).toFloat() }
    private val bitRev = IntArray(FRAME).also { rev ->
        var j = 0
        for (i in 0 until FRAME) {
            rev[i] = j
            var bit = FRAME shr 1
            while (bit != 0 && (j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
        }
    }
    private val re = FloatArray(FRAME)
    private val im = FloatArray(FRAME)
    private val power = FloatArray(FRAME / 2 + 1)
    private val prevBand = FloatArray(4)
    private var firstFrame = true

    // ------------------------------------------------------------ feature rings
    private val dbRing = FloatArray(WINDOW)
    private val hfRing = FloatArray(WINDOW)
    private val fluxRing = FloatArray(WINDOW)
    private val harmRing = FloatArray(WINDOW)
    private val flatRing = FloatArray(WINDOW)
    private val centRing = FloatArray(WINDOW)
    private val b0Ring = FloatArray(WINDOW)
    private val b1Ring = FloatArray(WINDOW)
    private val b3Ring = FloatArray(WINDOW)
    private var ringPos = 0

    // ------------------------------------------------------------ window scratch
    private val wDb = FloatArray(WINDOW)
    private val wHf = FloatArray(WINDOW)
    private val wFlux = FloatArray(WINDOW)
    private val wHarm = FloatArray(WINDOW)
    private val wFlat = FloatArray(WINDOW)
    private val wCent = FloatArray(WINDOW)
    private val wB0 = FloatArray(WINDOW)
    private val wB1 = FloatArray(WINDOW)
    private val wB3 = FloatArray(WINDOW)
    private val sortA = FloatArray(WINDOW)
    private val actDb = FloatArray(WINDOW)
    private val actFlat = FloatArray(WINDOW)
    private val d5 = FloatArray(WINDOW - 5)
    private val slopes = FloatArray(WINDOW)
    private val medScratch = FloatArray(WINDOW)
    private val actMask = BooleanArray(WINDOW)
    private val modCos = FloatArray(17 * WINDOW) {
        val k = it / WINDOW + 2
        val n = it % WINDOW
        cos(2.0 * PI * k * n / WINDOW).toFloat()
    }
    private val modSin = FloatArray(17 * WINDOW) {
        val k = it / WINDOW + 2
        val n = it % WINDOW
        sin(2.0 * PI * k * n / WINDOW).toFloat()
    }
    private val pw = FloatArray(17)
    private val feat = FloatArray(27)

    // Linear discriminant fitted on simulated phone captures (speech in measured rooms, music, environmental sound).
    private val mu = floatArrayOf(
        18.7695f, 0.367055f, -4.61594f, -68.8716f, 0.819177f, 5.95478f, 0.33203f, 0.0304351f,
        0.235386f, 10.5262f, -22.2975f, 0.875872f, 0.314528f, 0.601779f, 11.1811f, 9.19099f,
        12.5068f, 0.245018f, 9.47566f, 0.46286f, 0.552359f, 0.0186341f, 10.5053f, -16.5483f,
        4.17325f, 0.00314136f, 13.3409f)
    private val sd = floatArrayOf(
        10.2433f, 0.256626f, 2.52295f, 45.8247f, 0.209244f, 2.7833f, 0.228511f, 0.0281595f,
        0.17594f, 8.02093f, 4.43906f, 0.180299f, 0.225319f, 0.312995f, 7.60589f, 7.01976f,
        6.3349f, 0.366269f, 5.46523f, 0.216985f, 0.331417f, 0.0314502f, 9.67579f, 5.25542f,
        2.36411f, 0.696665f, 7.47563f)
    private val weights = arrayOf(
        floatArrayOf(0.870534f, 1.16269f, 1.03305f, 0.242222f, -0.601876f, -0.811774f, 0.514261f, -0.107515f,
        0.0852506f, 0.290369f, -0.25403f, 3.73346f, 4.00286f, 0.941371f, -0.0557484f, -0.01156f,
        -1.11662f, -0.420512f, 1.81566f, 0.0971571f, -0.204899f, -0.195981f, -1.06473f, 0.148321f,
        -1.31545f, 0.485934f, 0.364514f),
        floatArrayOf(-1.0595f, 1.60986f, 0.278959f, 0.291386f, 0.060359f, 0.397686f, -0.392609f, 0.231204f,
        0.00581925f, -0.337646f, -0.0831418f, 1.68465f, 3.69995f, 1.34152f, 0.614597f, -0.0236397f,
        0.717654f, -0.616171f, -0.315991f, -1.03909f, 1.36248f, -0.34617f, 0.151409f, 0.0961717f,
        0.17676f, 0.550331f, 0.615787f),
        floatArrayOf(-0.347603f, -0.525109f, -1.70948f, -0.645146f, 1.4709f, 0.0124013f, -1.59242f, -0.0150645f,
        -0.198585f, -0.954538f, 0.0933263f, -4.85382f, -4.30978f, 0.834448f, -1.83447f, -2.25954f,
        3.00046f, -0.340871f, -2.82276f, -1.2488f, 2.0128f, 0.0784474f, 3.07114f, -1.04193f,
        2.80499f, -0.313011f, -0.538856f),
        floatArrayOf(0.536573f, -2.24744f, 0.39747f, 0.111538f, -0.929378f, 0.401686f, 1.47077f, -0.108625f,
        0.107515f, 1.00182f, 0.243845f, -0.564294f, -3.39303f, -3.11734f, 1.27562f, 2.29474f,
        -2.60149f, 1.37755f, 1.32309f, 2.19073f, -3.17038f, 0.463704f, -2.15782f, 0.797442f,
        -1.6663f, -0.723254f, -0.441445f))
    private val bias = floatArrayOf(-2.5212f, -2.15887f, -5.33637f, -6.1741f)
    // ------------------------------------------------------------ result
    private var nextCheck = CHECK_FIRST
    private var decidedScene: Scene? = null
    private var margin = 0f

    /** Input sample index at which the decision was committed, or -1. */
    var decidedSample: Long = -1
        private set

    /** Mono samples in [-1, 1] at the capture rate. Returns the scene once decided (and on every later call), else null. */
    fun feed(samples: FloatArray, count: Int = samples.size): Scene? {
        decidedScene?.let { return it }
        val n = min(max(count, 0), samples.size)
        for (i in 0 until n) {
            val x = samples[i]
            if (decim == 1) {
                pushDecimated(x)
            } else {
                val f1 = (x + xPrev1 + xPrev2) / 3f
                val f2 = (f1 + f1Prev1 + f1Prev2) / 3f
                xPrev2 = xPrev1
                xPrev1 = x
                f1Prev2 = f1Prev1
                f1Prev1 = f1
                if (inputIndex % 3L == 0L) pushDecimated(f2)
            }
            inputIndex++
        }
        totalSamples += n
        val t = totalSamples.toDouble() / rate
        if (t >= nextCheck) {
            if (framesDone >= WINDOW) evaluate(t)
            nextCheck = min(nextCheck + CHECK_STEP, DEADLINE)
        }
        return decidedScene
    }

    private fun pushDecimated(v: Float) {
        frameRing[frameWrite] = v
        frameWrite++
        if (frameWrite == FRAME) frameWrite = 0
        if (frameFill < FRAME) frameFill++
        sinceHop++
        if (frameFill == FRAME && sinceHop >= HOP) {
            processFrame()
            sinceHop = 0
        }
    }

    // ------------------------------------------------------------ frame features
    private fun fft() {
        for (i in 0 until FRAME) {
            val j = bitRev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= FRAME) {
            val half = len shr 1
            val step = FRAME / len
            var base = 0
            while (base < FRAME) {
                var k = 0
                var j = base
                while (j < base + half) {
                    val wr = cosTab[k]
                    val wi = sinTab[k]
                    val ur = re[j]
                    val ui = im[j]
                    val vr = re[j + half] * wr - im[j + half] * wi
                    val vi = re[j + half] * wi + im[j + half] * wr
                    re[j] = ur + vr
                    im[j] = ui + vi
                    re[j + half] = ur - vr
                    im[j + half] = ui - vi
                    j++
                    k += step
                }
                base += len
            }
            len = len shl 1
        }
    }

    private fun processFrame() {
        var idx = frameWrite
        for (i in 0 until FRAME) {
            re[i] = frameRing[idx] * hann[i]
            im[i] = 0f
            idx++
            if (idx == FRAME) idx = 0
        }
        fft()
        var b0 = 0f
        var b1 = 0f
        var b2 = 0f
        var b3 = 0f
        var sumLog = 0f
        var sumCent = 0f
        for (k in 0..FRAME / 2) power[k] = re[k] * re[k] + im[k] * im[k]
        for (k in LO_BIN..HI_BIN) {
            val p = power[k]
            sumLog += ln(p + EPS)
            sumCent += k * p
            when {
                k <= 15 -> b0 += p
                k <= 47 -> b1 += p
                k <= 95 -> b2 += p
                else -> b3 += p
            }
        }
        val total = b0 + b1 + b2 + b3
        val db = 10f * log10(total + EPS)
        val d0 = 10f * log10(b0 + EPS)
        val d1 = 10f * log10(b1 + EPS)
        val d2 = 10f * log10(b2 + EPS)
        val d3 = 10f * log10(b3 + EPS)
        val d3rel = 10f * log10(b3 / (total + EPS) + EPS)
        val hf = 10f * log10((b2 + b3 + EPS) / (b0 + b1 + EPS))
        val flat = exp(sumLog / BAND_BINS.toFloat()) / (total / BAND_BINS.toFloat() + EPS)
        val cent = sumCent / (total + EPS)
        var flux = 0f
        if (!firstFrame) {
            flux = (max(0f, d0 - prevBand[0]) + max(0f, d1 - prevBand[1]) +
                max(0f, d2 - prevBand[2]) + max(0f, d3 - prevBand[3])) / 4f
        } else {
            firstFrame = false
        }
        prevBand[0] = d0
        prevBand[1] = d1
        prevBand[2] = d2
        prevBand[3] = d3
        // normalized autocorrelation peak over lags 40..200 (80..400 Hz)
        for (k in 0..FRAME / 2) {
            re[k] = power[k]
            im[k] = 0f
        }
        for (k in 1 until FRAME / 2) {
            re[FRAME - k] = power[k]
            im[FRAME - k] = 0f
        }
        fft()
        val r0 = re[0]
        var harm = 0f
        if (r0 > 0f) {
            var mx = -1e30f
            for (lag in 40..200) if (re[lag] > mx) mx = re[lag]
            if (mx / r0 > 0.5f) harm = 1f
        }
        dbRing[ringPos] = db
        hfRing[ringPos] = hf
        fluxRing[ringPos] = flux
        harmRing[ringPos] = harm
        flatRing[ringPos] = flat
        centRing[ringPos] = cent
        b0Ring[ringPos] = d0
        b1Ring[ringPos] = d1
        b3Ring[ringPos] = d3rel
        ringPos++
        if (ringPos == WINDOW) ringPos = 0
        framesDone++
    }

    // ------------------------------------------------------------ window features
    private fun pct(a: FloatArray, n: Int, q: Float): Float {
        val i = (q * (n - 1)).toInt().coerceIn(0, n - 1)
        return a[i]
    }

    private fun median(a: FloatArray, n: Int): Float {
        System.arraycopy(a, 0, medScratch, 0, n)
        java.util.Arrays.sort(medScratch, 0, n)
        return if (n % 2 == 1) medScratch[n / 2]
        else (medScratch[n / 2 - 1] + medScratch[n / 2]) * 0.5f
    }

    private fun computeFeatures() {
        var idx = ringPos
        for (i in 0 until WINDOW) {
            wDb[i] = dbRing[idx]
            wHf[i] = hfRing[idx]
            wFlux[i] = fluxRing[idx]
            wHarm[i] = harmRing[idx]
            wFlat[i] = flatRing[idx]
            wCent[i] = centRing[idx]
            wB0[i] = b0Ring[idx]
            wB1[i] = b1Ring[idx]
            wB3[i] = b3Ring[idx]
            idx++
            if (idx == WINDOW) idx = 0
        }
        System.arraycopy(wDb, 0, sortA, 0, WINDOW)
        java.util.Arrays.sort(sortA)
        val p10 = pct(sortA, WINDOW, 0.10f)
        val p50 = pct(sortA, WINDOW, 0.50f)
        val p75 = pct(sortA, WINDOW, 0.75f)
        val p90 = pct(sortA, WINDOW, 0.90f)
        val p95 = pct(sortA, WINDOW, 0.95f)

        var activeFrac = 0
        var actCount = 0
        for (i in 0 until WINDOW) {
            if (wDb[i] > p10 + 10f) activeFrac++
            val a = wDb[i] > p10 + 8f
            actMask[i] = a
            if (a) actCount++
        }
        if (actCount < 30) {
            for (i in 0 until WINDOW) actMask[i] = true
            actCount = WINDOW
        }
        val nAct = actCount.toFloat()

        var sumDb = 0f
        var sumDb2 = 0f
        var sumDb3 = 0f
        var sumB0 = 0f
        var sumB1 = 0f
        var sumB3 = 0f
        var sumHf = 0f
        var sumFlat = 0f
        var sumCent = 0f
        var sumHarm = 0f
        var sxy = 0f
        var sx = 0f
        var sy = 0f
        var sxx = 0f
        var syy = 0f
        var sumHd = 0f
        var sumHd2 = 0f
        for (i in 0 until WINDOW) {
            if (!actMask[i]) continue
            val v = wDb[i]
            sumDb += v
            sumDb2 += v * v
            sumDb3 += v * v * v
            sumB0 += wB0[i]
            sumB1 += wB1[i]
            sumB3 += wB3[i]
            sumHf += wHf[i]
            sumFlat += wFlat[i]
            sumCent += wCent[i]
            sumHarm += wHarm[i]
            val x = wB0[i]
            val y = wB1[i]
            sxy += x * y
            sx += x
            sy += y
            sxx += x * x
            syy += y * y
            val d = wB3[i] - wB0[i]
            sumHd += d
            sumHd2 += d * d
        }
        val muA = sumDb / nAct
        val varA = max(0f, sumDb2 / nAct - muA * muA)
        val sdA = sqrt(varA)
        val skew = (sumDb3 / nAct - 3f * muA * (sumDb2 / nAct) + 2f * muA * muA * muA) /
            (sdA * sdA * sdA + 1e-9f)

        var actK = 0
        for (i in 0 until WINDOW) if (actMask[i]) actDb[actK++] = wDb[i]
        System.arraycopy(actDb, 0, sortA, 0, actCount)
        java.util.Arrays.sort(sortA, 0, actCount)
        val rangeA = pct(sortA, actCount, 0.95f) - pct(sortA, actCount, 0.05f)

        actK = 0
        for (i in 0 until WINDOW) if (actMask[i]) actFlat[actK++] = wFlat[i]
        System.arraycopy(actFlat, 0, sortA, 0, actCount)
        java.util.Arrays.sort(sortA, 0, actCount)
        val flatA = pct(sortA, actCount, 0.50f)

        var dSum = 0f
        var dCount = 0
        for (i in 1 until WINDOW) {
            if (actMask[i] && actMask[i - 1]) {
                dSum += abs(wDb[i] - wDb[i - 1])
                dCount++
            }
        }
        val roughA = if (dCount > 0) (dSum / dCount) / (sdA + 1e-9f) else 0f

        // whole-window statistics
        var sdDb = 0f
        var meanDb = 0f
        for (i in 0 until WINDOW) {
            meanDb += wDb[i]
            sdDb += wDb[i] * wDb[i]
        }
        meanDb /= WINDOW.toFloat()
        sdDb = sqrt(max(0f, sdDb / WINDOW - meanDb * meanDb))
        var roughSum = 0f
        for (i in 1 until WINDOW) roughSum += abs(wDb[i] - wDb[i - 1])
        val rough = (roughSum / (WINDOW - 1)) / (sdDb + 1e-9f)

        var acSxy = 0f
        var acSx = 0f
        var acSy = 0f
        var acSxx = 0f
        var acSyy = 0f
        for (i in 0 until WINDOW - 1) {
            val x = wDb[i]
            val y = wDb[i + 1]
            acSxy += x * y
            acSx += x
            acSy += y
            acSxx += x * x
            acSyy += y * y
        }
        val acN = (WINDOW - 1).toFloat()
        val ac1 = if (sdDb <= 1e-6f) 1f else corr(acN, acSxy, acSx, acSy, acSxx, acSyy)

        var cN = WINDOW.toFloat()
        var cSxy = 0f
        var cSx = 0f
        var cSy = 0f
        var cSxx = 0f
        var cSyy = 0f
        var hdMean = 0f
        var hdSq = 0f
        var hfMean = 0f
        var hfSq = 0f
        var fluxMean = 0f
        var centMean = 0f
        var centSq = 0f
        var harmMean = 0f
        for (i in 0 until WINDOW) {
            val x = wB0[i]
            val y = wB1[i]
            cSxy += x * y
            cSx += x
            cSy += y
            cSxx += x * x
            cSyy += y * y
            val d = wB3[i] - wB0[i]
            hdMean += d
            hdSq += d * d
            hfMean += wHf[i]
            hfSq += wHf[i] * wHf[i]
            fluxMean += wFlux[i]
            centMean += wCent[i]
            centSq += wCent[i] * wCent[i]
            harmMean += wHarm[i]
        }
        val c01 = corr(cN, cSxy, cSx, cSy, cSxx, cSyy)
        val hdMu = hdMean / cN
        val hfdiff = sqrt(max(0f, hdSq / cN - hdMu * hdMu))
        val hfMu = hfMean / cN
        val hfsd = sqrt(max(0f, hfSq / cN - hfMu * hfMu))
        val fluxMu = fluxMean / cN
        val centMu = centMean / cN
        val centsd = sqrt(max(0f, centSq / cN - centMu * centMu))
        val harm = harmMean / cN

        // c01a and hfdiffa over active frames
        val c01a = corr(nAct, sxy, sx, sy, sxx, syy)
        val hdMuA = sumHd / nAct
        val hfdiffa = sqrt(max(0f, sumHd2 / nAct - hdMuA * hdMuA))
        val hfa = sumHf / nAct
        val harma = sumHarm / nAct
        val centA = sumCent / nAct
        var centA2 = 0f
        for (i in 0 until WINDOW) if (actMask[i]) centA2 += wCent[i] * wCent[i]
        val centsda = sqrt(max(0f, centA2 / nAct - centA * centA))

        // flatness and HF share medians
        System.arraycopy(wFlat, 0, sortA, 0, WINDOW)
        java.util.Arrays.sort(sortA)
        val flat = pct(sortA, WINDOW, 0.50f)
        System.arraycopy(wB3, 0, sortA, 0, WINDOW)
        java.util.Arrays.sort(sortA)
        val b3 = pct(sortA, WINDOW, 0.50f)

        // envelope slopes
        for (i in 5 until WINDOW) d5[i - 5] = wDb[i] - wDb[i - 5]
        java.util.Arrays.sort(d5)
        val dec = pct(d5, WINDOW - 5, 0.15f)

        var slopeCount = 0
        for (i in 2 until WINDOW - 20) {
            if (wDb[i] > p75 && wDb[i] >= wDb[i - 1] && wDb[i] >= wDb[i - 2]) {
                var vMin = 1e30f
                var jBest = 0
                for (j in 0..20) {
                    val v = wDb[i + j]
                    if (v < vMin) {
                        vMin = v
                        jBest = j
                    }
                }
                val drop = wDb[i] - vMin
                if (drop > 6f && vMin > p10 + 3f && jBest > 0) {
                    slopes[slopeCount++] = -drop / (0.01f * jBest)
                }
            }
        }
        val decay = if (slopeCount == 0) 0f else median(slopes, slopeCount)

        // modulation spectrum of the envelope
        var modTot = 0f
        for (k in 0 until 17) {
            var cr = 0f
            var ci = 0f
            val base = k * WINDOW
            for (n in 0 until WINDOW) {
                val d = wDb[n] - meanDb
                cr += d * modCos[base + n]
                ci += d * modSin[base + n]
            }
            val p = cr * cr + ci * ci
            pw[k] = p
            modTot += p
        }
        val mod0 = pw[0] / (modTot + 1e-12f)

        feat[0] = p90 - p10
        feat[1] = activeFrac.toFloat() / WINDOW
        feat[2] = dec
        feat[3] = decay
        feat[4] = fluxMu
        feat[5] = hfsd
        feat[6] = harm
        feat[7] = flat
        feat[8] = mod0
        feat[9] = centsd
        feat[10] = b3
        feat[11] = ac1
        feat[12] = rough
        feat[13] = c01
        feat[14] = p95 - p50
        feat[15] = p50 - p10
        feat[16] = hfdiff
        feat[17] = c01a
        feat[18] = hfdiffa
        feat[19] = roughA
        feat[20] = harma
        feat[21] = flatA
        feat[22] = centsda
        feat[23] = hfa
        feat[24] = sdA
        feat[25] = skew
        feat[26] = rangeA
    }

    private fun corr(n: Float, sxy: Float, sx: Float, sy: Float, sxx: Float, syy: Float): Float {
        if (n < 2f) return 0f
        val mx = sx / n
        val my = sy / n
        val vx = sxx / n - mx * mx
        val vy = syy / n - my * my
        if (vx <= 1e-12f || vy <= 1e-12f) return 0f
        return (sxy / n - mx * my) / sqrt(vx * vy)
    }

    // ------------------------------------------------------------ decision
    private fun classify(): Scene {
        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        var secondScore = Float.NEGATIVE_INFINITY
        for (c in 0..3) {
            var s = bias[c]
            val w = weights[c]
            for (i in 0 until 27) s += w[i] * (feat[i] - mu[i]) / sd[i]
            if (s > bestScore) {
                secondScore = bestScore
                bestScore = s
                best = c
            } else if (s > secondScore) {
                secondScore = s
            }
        }
        margin = bestScore - secondScore
        return order[best]
    }

    private fun evaluate(t: Double) {
        computeFeatures()
        val scene = classify()
        if (t >= DEADLINE) {
            // no harmonic speech evidence in the window: the least-committal class
            decidedScene = if (feat[6] < 0.2f) Scene.Ambient else scene
            decidedSample = totalSamples
        } else if (t >= DECIDE_AFTER && margin >= MARGIN) {
            decidedScene = scene
            decidedSample = totalSamples
        }
    }
}
