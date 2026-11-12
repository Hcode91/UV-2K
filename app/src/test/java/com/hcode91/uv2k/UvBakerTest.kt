package com.hcode91.uv2k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UvBakerTest {
    private val topo: FaceTopology = run {
        val f = listOf("src/main/assets/face_topology.txt", "app/src/main/assets/face_topology.txt").map(::File).first { it.exists() }
        val canon = FloatArray(FaceTopology.N * 3); val uv = FloatArray(FaceTopology.N * 2); val tris = ArrayList<Int>()
        var mode = ' '; var i = 0
        for (line in f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val p = line.split(' ')
            when {
                p[0] == "V" -> { mode = 'V'; i = 0 }
                p[0] == "T" -> mode = 'T'
                mode == 'V' -> { for (j in 0 until 3) canon[i * 3 + j] = p[j].toFloat(); uv[i * 2] = p[3].toFloat(); uv[i * 2 + 1] = p[4].toFloat(); i++ }
                else -> p.forEach { tris.add(it.toInt()) }
            }
        }
        FaceTopology(canon, uv, tris.toIntArray())
    }
    private val n = FaceTopology.N
    private val size = 1024

    /** A front "photo" of the template face: 20 px per unit, centred at (500, 500). */
    private val frontLm = FloatArray(n * 3) { k -> val i = k / 3; when (k % 3) { 0 -> 500f + topo.canon[i * 3] * 20f; 1 -> 500f - topo.canon[i * 3 + 1] * 20f; else -> -topo.canon[i * 3 + 2] * 20f } }
    /** A side "photo" with the face pointing to the left of the image. */
    private val sideLm = FloatArray(n * 3) { k -> val i = k / 3; when (k % 3) { 0 -> 500f - topo.canon[i * 3 + 2] * 20f; 1 -> 500f - topo.canon[i * 3 + 1] * 20f; else -> -topo.canon[i * 3] * 20f } }

    private fun gradient() = Image(1000, 1000, IntArray(1000 * 1000) { i -> (255 shl 24) or (((i % 1000) / 4) shl 16) or (((i / 1000) / 4) shl 8) or 40 })
    private fun solid(c: Int) = Image(1000, 1000, IntArray(1000 * 1000) { c })
    private val dst = FloatArray(n * 2) { k -> val i = k / 2; if (k % 2 == 0) topo.uv[i * 2] * size else (1f - topo.uv[i * 2 + 1]) * size }
    private fun xy(lm: FloatArray) = FloatArray(n * 2) { lm[(it / 2) * 3 + it % 2] }
    private fun at(out: IntArray, v: Int) = out[dst[v * 2 + 1].toInt().coerceIn(0, size - 1) * size + dst[v * 2].toInt().coerceIn(0, size - 1)]
    private fun r(c: Int) = (c shr 16) and 255
    private fun g(c: Int) = (c shr 8) and 255
    private fun b(c: Int) = c and 255

    @Test
    fun mirrorMapPairsLeftAndRight() {
        val m = UvBaker.mirrorMap(topo.canon)
        assertEquals(263, m[33]); assertEquals(33, m[263]); assertEquals(454, m[234]); assertEquals(1, m[1])
        assertTrue((0 until n).count { m[m[it]] == it } > n * 0.95)
    }

    @Test
    fun frontPhotoLandsWhereTheLayoutSays() {
        val bg = IntArray(size * size) { 0xFF000000.toInt() }
        val out = UvBaker.bake(UvBaker.Input(topo.tris, dst, gradient(), xy(frontLm), null, null, FloatArray(n), floatArrayOf(1f, 1f, 1f), FloatArray(n) { 1f }, size, size, bg))
        for (v in intArrayOf(1, 4, 168, 50, 280, 13, 199)) {   // nose, bridge, cheeks, lips, chin
            val c = at(out, v)
            assertEquals("r at $v", frontLm[v * 3] / 4f, r(c).toFloat(), 3f)
            assertEquals("g at $v", frontLm[v * 3 + 1] / 4f, g(c).toFloat(), 3f)
            assertEquals(40, b(c))
        }
        // The whole face region got filled: no black holes between triangles.
        var holes = 0; var filled = 0
        for (y in 200 until 800) for (x in 300 until 700) { if (out[y * size + x] == 0xFF000000.toInt()) holes++ else filled++ }
        assertTrue("holes $holes filled $filled", filled > 200_000 && holes < filled / 50)
    }

    @Test
    fun sidePhotoTakesOverWhereTheFaceTurnsAway() {
        val pos = UvBaker.toFace3d(frontLm, topo.canonEyeDistance)
        for (i in 0 until n * 3) assertEquals(topo.canon[i] - (0 until n).map { topo.canon[it * 3 + i % 3] }.average().toFloat(), pos[i], 0.05f)
        val w = UvBaker.sideWeights(UvBaker.normals(pos, topo.tris))
        println("side weight: nose %.2f, cheek 50 %.2f, edge 234 %.2f, edge 454 %.2f".format(w[1], w[50], w[234], w[454]))
        assertEquals(0f, w[1], 0.01f); assertEquals(0f, w[168], 0.01f)
        assertTrue(w[234] > 0.8f && w[454] > 0.8f)

        assertTrue(UvBaker.facesImageLeft(sideLm))
        val sp = UvBaker.sidePixels(sideLm, topo.canon, UvBaker.mirrorMap(topo.canon))
        // The person's left cheek edge (454) is seen directly; the right one (234) borrows its twin's pixel.
        assertEquals(sideLm[454 * 3], sp[454 * 2], 0f); assertEquals(sideLm[454 * 3], sp[234 * 2], 0f)

        val side = solid(0xFF0AC81E.toInt())
        val bg = IntArray(size * size) { 0xFF000000.toInt() }
        val out = UvBaker.bake(UvBaker.Input(topo.tris, dst, gradient(), xy(frontLm), side, sp, w, floatArrayOf(1f, 0.5f, 1f), FloatArray(n) { 1f }, size, size, bg))
        // Nose is still the front photo; a point just inside the cheek edge is the (gain-corrected) side photo.
        assertEquals(frontLm[1 * 3] / 4f, r(at(out, 1)).toFloat(), 3f)
        val inner = 127 // well inside the left of the mesh, strongly side-facing
        println("weight at $inner = ${w[inner]}")
        val edge = (0 until n).filter { w[it] > 0.99f }.first { v -> val x = dst[v * 2].toInt(); val y = dst[v * 2 + 1].toInt(); x in 2 until size - 2 && y in 2 until size - 2 && topo.outline.none { it == v } }
        val c = at(out, edge)
        assertEquals(10f, r(c).toFloat(), 4f); assertEquals(100f, g(c).toFloat(), 4f); assertEquals(30f, b(c).toFloat(), 4f)
    }

    @Test
    fun softEdgeAndTransparentBackground() {
        val alpha = FloatArray(n) { 1f }; for (v in topo.outline) alpha[v] = 0f
        val bg = IntArray(size * size) { 0xFF112233.toInt() }
        val out = UvBaker.bake(UvBaker.Input(topo.tris, dst, solid(0xFFFFFFFF.toInt()), xy(frontLm), null, null, FloatArray(n), floatArrayOf(1f, 1f, 1f), alpha, size, size, bg))
        assertEquals(0xFFFFFFFF.toInt(), at(out, 1))
        val o = topo.outline.first { v -> dst[v * 2] in 3f..size - 3f && dst[v * 2 + 1] in 3f..size - 3f }
        assertTrue("edge should be nearly background", r(at(out, o)) < 0x11 + 60)

        val clear = UvBaker.bake(UvBaker.Input(topo.tris, dst, solid(0xFFFFFFFF.toInt()), xy(frontLm), null, null, FloatArray(n), floatArrayOf(1f, 1f, 1f), alpha, size, size, null))
        assertEquals(0, clear[0])
        assertEquals(255, at(clear, 1) ushr 24)
        assertTrue((at(clear, o) ushr 24) < 80)
    }

    @Test
    fun layoutTextRoundTrips() {
        val t = UvBaker.layoutToText(2048, 1024, dst)
        val back = UvBaker.layoutFromText(t)!!
        assertEquals(2048, back.first); assertEquals(1024, back.second)
        assertEquals(dst.size, back.third.size)
        for (i in dst.indices) assertEquals(dst[i], back.third[i], 1e-3f)
        assertEquals(null, UvBaker.layoutFromText("nonsense"))
        assertEquals(floatArrayOf(1f, 1f, 1f).toList(), UvBaker.gain(null, floatArrayOf(1f, 2f, 3f)).toList())
    }
}
