package com.hcode91.uv2k

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/** A plain ARGB image (no Android classes, so the baking maths can be unit-tested). */
class Image(val w: Int, val h: Int, val px: IntArray) {
    /** Bilinear sample at (x, y); edges clamp. Returns r, g, b in [out]. */
    fun sample(x: Float, y: Float, out: FloatArray) {
        val fx = (x - 0.5f).coerceIn(0f, w - 1f); val fy = (y - 0.5f).coerceIn(0f, h - 1f)
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = minOf(x0 + 1, w - 1); val y1 = minOf(y0 + 1, h - 1)
        val tx = fx - x0; val ty = fy - y0
        val a = px[y0 * w + x0]; val b = px[y0 * w + x1]; val c = px[y1 * w + x0]; val d = px[y1 * w + x1]
        for (ch in 0 until 3) {
            val s = 16 - ch * 8
            val top = ((a shr s) and 255) * (1 - tx) + ((b shr s) and 255) * tx
            val bot = ((c shr s) and 255) * (1 - tx) + ((d shr s) and 255) * tx
            out[ch] = top * (1 - ty) + bot * ty
        }
    }
}

/**
 * Makes a face texture in a target UV layout from a front photo and (optionally) a side photo.
 *
 * Everything hangs off the 468-point face mesh: each mesh triangle is copied from where it sits
 * in the photo to where it sits in the layout. The front photo is used where the face looks at
 * the camera, the side photo where it turns away, with a smooth blend between the two.
 */
object UvBaker {

    class Input(
        val tris: IntArray,
        /** Where each mesh point sits in the output image (x, y pixels). */
        val dst: FloatArray,
        val front: Image,
        /** Where each mesh point sits in the front photo. */
        val frontPx: FloatArray,
        val side: Image?,
        /** Where each mesh point sits in the side photo (already mirrored for the far side of the face). */
        val sidePx: FloatArray?,
        /** Per point: 0 = use the front photo, 1 = use the side photo. */
        val sideWeight: FloatArray,
        /** Multiplies the side photo's r, g, b so its colours match the front photo. */
        val sideGain: FloatArray,
        /** Per point: how solid the face is over the background (0 at the outer edge for a soft join). */
        val alpha: FloatArray,
        val outW: Int, val outH: Int,
        /** Opaque background to draw the face over, or null for a transparent background. */
        val background: IntArray?,
    )

    fun bake(inp: Input): IntArray {
        val out = inp.background?.copyOf() ?: IntArray(inp.outW * inp.outH)
        val f = FloatArray(3); val s = FloatArray(3)
        val d = inp.dst
        for (t in 0 until inp.tris.size / 3) {
            val i0 = inp.tris[t * 3]; val i1 = inp.tris[t * 3 + 1]; val i2 = inp.tris[t * 3 + 2]
            val x0 = d[i0 * 2]; val y0 = d[i0 * 2 + 1]; val x1 = d[i1 * 2]; val y1 = d[i1 * 2 + 1]; val x2 = d[i2 * 2]; val y2 = d[i2 * 2 + 1]
            val det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if (abs(det) < 1e-6f) continue
            val bx0 = maxOf(0, floor(minOf(x0, x1, x2)).toInt()); val bx1 = minOf(inp.outW - 1, ceil(maxOf(x0, x1, x2)).toInt())
            val by0 = maxOf(0, floor(minOf(y0, y1, y2)).toInt()); val by1 = minOf(inp.outH - 1, ceil(maxOf(y0, y1, y2)).toInt())
            val usesSide = inp.side != null && inp.sidePx != null &&
                (inp.sideWeight[i0] > 0f || inp.sideWeight[i1] > 0f || inp.sideWeight[i2] > 0f)
            for (y in by0..by1) for (x in bx0..bx1) {
                val px = x + 0.5f; val py = y + 0.5f
                val w0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / det
                val w1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / det
                val w2 = 1 - w0 - w1
                if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue
                inp.front.sample(
                    w0 * inp.frontPx[i0 * 2] + w1 * inp.frontPx[i1 * 2] + w2 * inp.frontPx[i2 * 2],
                    w0 * inp.frontPx[i0 * 2 + 1] + w1 * inp.frontPx[i1 * 2 + 1] + w2 * inp.frontPx[i2 * 2 + 1], f)
                if (usesSide) {
                    val k = (w0 * inp.sideWeight[i0] + w1 * inp.sideWeight[i1] + w2 * inp.sideWeight[i2]).coerceIn(0f, 1f)
                    if (k > 0f) {
                        val sp = inp.sidePx!!
                        inp.side!!.sample(w0 * sp[i0 * 2] + w1 * sp[i1 * 2] + w2 * sp[i2 * 2], w0 * sp[i0 * 2 + 1] + w1 * sp[i1 * 2 + 1] + w2 * sp[i2 * 2 + 1], s)
                        for (c in 0 until 3) f[c] = f[c] * (1 - k) + s[c] * inp.sideGain[c] * k
                    }
                }
                val a = (w0 * inp.alpha[i0] + w1 * inp.alpha[i1] + w2 * inp.alpha[i2]).coerceIn(0f, 1f)
                val o = y * inp.outW + x
                if (inp.background == null) {
                    // Transparent background: keep the colour solid and put the softness in the alpha channel.
                    out[o] = ((a * 255f + 0.5f).toInt() shl 24) or rgb(f[0], f[1], f[2])
                } else {
                    val b = inp.background[o]
                    out[o] = (255 shl 24) or rgb(
                        ((b shr 16) and 255) * (1 - a) + f[0] * a, ((b shr 8) and 255) * (1 - a) + f[1] * a, (b and 255) * (1 - a) + f[2] * a)
                }
            }
        }
        return out
    }

    private fun rgb(r: Float, g: Float, b: Float) =
        ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or (b + 0.5f).toInt().coerceIn(0, 255)

    // ---- helpers for building the input ------------------------------------------------------------

    /** For each mesh point, the matching point on the other side of the face (itself, on the centre line). */
    fun mirrorMap(canon: FloatArray): IntArray {
        val n = canon.size / 3
        return IntArray(n) { i ->
            var best = i; var bestD = Float.MAX_VALUE
            for (j in 0 until n) {
                val dx = canon[j * 3] + canon[i * 3]; val dy = canon[j * 3 + 1] - canon[i * 3 + 1]; val dz = canon[j * 3 + 2] - canon[i * 3 + 2]
                val dd = dx * dx + dy * dy + dz * dz
                if (dd < bestD) { bestD = dd; best = j }
            }
            best
        }
    }

    /**
     * Turns photo landmarks (x, y, z in pixels; y down, smaller z = nearer the camera) into a small
     * 3D face: x right, y up, z towards the camera, centred, in units where the eyes are [eyeDistance] apart.
     */
    fun toFace3d(lm: FloatArray, eyeDistance: Float): FloatArray {
        val n = lm.size / 3
        val s = (hypot(lm[33 * 3] - lm[263 * 3], lm[33 * 3 + 1] - lm[263 * 3 + 1]) / eyeDistance).coerceAtLeast(1e-3f)
        var cx = 0f; var cy = 0f; var cz = 0f
        for (i in 0 until n) { cx += lm[i * 3]; cy += lm[i * 3 + 1]; cz += lm[i * 3 + 2] }
        cx /= n; cy /= n; cz /= n
        return FloatArray(n * 3) { k ->
            val i = k / 3
            when (k % 3) { 0 -> (lm[i * 3] - cx) / s; 1 -> -(lm[i * 3 + 1] - cy) / s; else -> -(lm[i * 3 + 2] - cz) / s }
        }
    }

    fun normals(pos: FloatArray, tris: IntArray): FloatArray {
        val nrm = FloatArray(pos.size)
        for (t in 0 until tris.size / 3) {
            val a = tris[t * 3] * 3; val b = tris[t * 3 + 1] * 3; val c = tris[t * 3 + 2] * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (k in intArrayOf(a, b, c)) { nrm[k] += nx; nrm[k + 1] += ny; nrm[k + 2] += nz }
        }
        for (i in 0 until nrm.size / 3) {
            val l = sqrt(nrm[i * 3] * nrm[i * 3] + nrm[i * 3 + 1] * nrm[i * 3 + 1] + nrm[i * 3 + 2] * nrm[i * 3 + 2])
            if (l > 1e-9f) { nrm[i * 3] /= l; nrm[i * 3 + 1] /= l; nrm[i * 3 + 2] /= l } else nrm[i * 3 + 2] = 1f
        }
        // The mesh may be wound either way; make "forward" mean towards the camera.
        var fwd = 0f
        for (i in 0 until nrm.size / 3) fwd += nrm[i * 3 + 2]
        if (fwd < 0f) for (i in nrm.indices) nrm[i] = -nrm[i]
        return nrm
    }

    /** 0 where the surface faces the camera, rising smoothly to 1 where it has turned well to the side. */
    fun sideWeights(normals: FloatArray): FloatArray = FloatArray(normals.size / 3) { i ->
        val t = ((abs(normals[i * 3]) - 0.55f) / 0.3f).coerceIn(0f, 1f)
        t * t * (3 - 2 * t)
    }

    /**
     * True if the face in a side photo points towards the left of the image, judged from detected
     * landmarks (nose tip against the two cheek edges).
     */
    fun facesImageLeft(sideLm: FloatArray) = sideLm[1 * 3] < (sideLm[234 * 3] + sideLm[454 * 3]) / 2

    /**
     * Side-photo pixel for every mesh point when the side photo's landmarks were detected.
     * The camera sees one side of the face; points on the far side borrow their mirror twin's pixel.
     */
    fun sidePixels(sideLm: FloatArray, canon: FloatArray, mirror: IntArray): FloatArray {
        // Facing image-left means the camera sees the person's left side, which is +x in the mesh.
        val visibleSign = if (facesImageLeft(sideLm)) 1f else -1f
        val n = canon.size / 3
        return FloatArray(n * 2) { k ->
            val i = k / 2
            val src = if (canon[i * 3] * visibleSign >= 0f) i else mirror[i]
            sideLm[src * 3 + k % 2]
        }
    }

    /** Average colour of a square patch of [img] around (x, y). */
    fun patch(img: Image, x: Float, y: Float, r: Int): FloatArray? {
        val sum = FloatArray(3); var n = 0
        for (yy in y.toInt() - r..y.toInt() + r) for (xx in x.toInt() - r..x.toInt() + r) {
            if (xx !in 0 until img.w || yy !in 0 until img.h) continue
            val c = img.px[yy * img.w + xx]
            sum[0] += ((c shr 16) and 255).toFloat(); sum[1] += ((c shr 8) and 255).toFloat(); sum[2] += (c and 255).toFloat(); n++
        }
        if (n == 0) return null
        return FloatArray(3) { sum[it] / n }
    }

    /** Per-channel gain turning colour [from] into [to], kept within sensible limits. */
    fun gain(from: FloatArray?, to: FloatArray?): FloatArray =
        if (from == null || to == null) floatArrayOf(1f, 1f, 1f) else FloatArray(3) { ((to[it] + 1f) / (from[it] + 1f)).coerceIn(0.6f, 1.6f) }

    /** Saved layouts: "w h" on the first line, then one "x y" line per mesh point (pixels in the reference image). */
    fun layoutToText(w: Int, h: Int, pts: FloatArray): String = buildString {
        append(w).append(' ').append(h).append('\n')
        for (i in 0 until pts.size / 2) append(pts[i * 2]).append(' ').append(pts[i * 2 + 1]).append('\n')
    }

    /** Returns (w, h, points) or null if the text isn't a layout. */
    fun layoutFromText(text: String): Triple<Int, Int, FloatArray>? = try {
        val lines = text.trim().lines()
        val head = lines[0].split(' ')
        val pts = FloatArray((lines.size - 1) * 2)
        for (i in 1 until lines.size) { val p = lines[i].split(' '); pts[(i - 1) * 2] = p[0].toFloat(); pts[(i - 1) * 2 + 1] = p[1].toFloat() }
        Triple(head[0].toInt(), head[1].toInt(), pts)
    } catch (e: Exception) { null }
}
