package net.torosaba.train

import net.torosaba.train.model.SignParts
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * MarumaSign の看板をアイテムのディスプレイで描いたときに、ワールドに置いた看板と同じ位置に絵が来るかを確かめる。
 * ワールドの看板の頂点は MarumaSign の SignImage (rotate / buildMesh)、アイテムの頂点は itemBatches と同じ計算をする。
 */
class MarumaSignTransformTest {
    private class Vertex(val pos: Vector3f, val u: Float, val v: Float)

    // SignImage.rotate() と同じ
    private fun rotate(spec: SignParts.MarumaSpec, x0: Float, y0: Float, z0: Float): Vector3f {
        var x = x0
        var y = y0
        var z = z0
        var a = Math.toRadians(spec.pitch.toDouble())
        var ty = (y * cos(a) - z * sin(a)).toFloat()
        var tz = (y * sin(a) + z * cos(a)).toFloat()
        y = ty; z = tz
        a = Math.toRadians(-spec.yaw.toDouble())
        val tx = (x * cos(a) + z * sin(a)).toFloat()
        tz = (-x * sin(a) + z * cos(a)).toFloat()
        x = tx; z = tz
        a = Math.toRadians(-spec.roll.toDouble())
        val tx2 = (x * cos(a) - y * sin(a)).toFloat()
        ty = (x * sin(a) + y * cos(a)).toFloat()
        x = tx2; y = ty
        return Vector3f(x + spec.x * 2, y + spec.y * 2, z + spec.z * 2)
    }

    // SignImage.buildMesh() の表の 4 頂点 (ブロックの角が原点)
    private fun worldVertices(spec: SignParts.MarumaSpec, w: Int, h: Int, signYaw: Float, wall: Boolean): List<Vertex> {
        val m = max(w, h).toFloat()
        val vx = w / m * spec.scaleX
        val vy = h / m * spec.scaleY
        val base = listOf(rotate(spec, -vx, -vy, 0f), rotate(spec, -vx, vy, 0f), rotate(spec, vx, vy, 0f), rotate(spec, vx, -vy, 0f))
        val us = floatArrayOf(1f, 1f, 0f, 0f)
        val vs = floatArrayOf(1f, 0f, 0f, 1f)
        val moveZ = if (wall) 0.4375f * 2 else 0f
        val angle = Math.toRadians((-signYaw - 180).toDouble())
        val s = sin(angle).toFloat()
        val c = cos(angle).toFloat()
        return base.mapIndexed { i, b ->
            val z = b.z + moveZ
            Vertex(Vector3f((b.x * c + z * s) * 0.5f + 0.5f, b.y * 0.5f + 0.5f, (-b.x * s + z * c) * 0.5f + 0.5f), us[i], vs[i])
        }
    }

    // SignImage.itemBatches() の表の 4 頂点 (0 - 1 の立方体)
    private fun itemVertices(w: Int, h: Int): List<Vertex> {
        val m = max(w, h).toFloat()
        val x = w / m * 0.5f
        val y = h / m * 0.5f
        return listOf(
            Vertex(Vector3f(0.5f - x, 0.5f - y, 0.5f), 0f, 1f),
            Vertex(Vector3f(0.5f + x, 0.5f - y, 0.5f), 1f, 1f),
            Vertex(Vector3f(0.5f + x, 0.5f + y, 0.5f), 1f, 0f),
            Vertex(Vector3f(0.5f - x, 0.5f + y, 0.5f), 0f, 0f),
        )
    }

    private fun check(spec: SignParts.MarumaSpec, signYaw: Float, wall: Boolean, w: Int, h: Int) {
        // ブロックの中心を原点にする
        val t = SignParts.marumaTransform(spec, Vector3f(0f), signYaw, wall, flipItem = true)
        val world = worldVertices(spec, w, h, signYaw, wall)
        for (item in itemVertices(w, h)) {
            // アイテムのディスプレイの描画: 変換 → Y 軸 180 度 → アイテム (中心を原点に)
            val p = Vector3f(item.pos).sub(0.5f, 0.5f, 0.5f)
            Quaternionf().rotateY(Math.PI.toFloat()).transform(p)
            t.right.transform(p)
            p.mul(t.scale)
            t.left.transform(p)
            p.add(t.translation)
            val expected = world.first { it.u == item.u && it.v == item.v }.pos.sub(0.5f, 0.5f, 0.5f)
            val tol = 1e-3f * (1 + spec.scaleX * spec.scaleX + spec.scaleY * spec.scaleY + spec.x * spec.x + spec.y * spec.y + spec.z * spec.z)
            assertEquals(expected.x, p.x, tol, "x (yaw=$signYaw wall=$wall)")
            assertEquals(expected.y, p.y, tol, "y (yaw=$signYaw wall=$wall)")
            assertEquals(expected.z, p.z, tol, "z (yaw=$signYaw wall=$wall)")
        }
    }

    @Test
    fun itemDisplayMatchesWorldSign() {
        val random = Random(42)
        repeat(300) {
            val spec = SignParts.MarumaSpec(
                "https://example.com/a.png",
                random.nextFloat() * 6 - 3, random.nextFloat() * 6 - 3, random.nextFloat() * 6 - 3,
                random.nextFloat() * 4 + 0.1f, random.nextFloat() * 4 + 0.1f,
                random.nextFloat() * 360 - 180, random.nextFloat() * 360 - 180, random.nextFloat() * 360 - 180,
            )
            val wall = random.nextBoolean()
            val yaw = if (wall) random.nextInt(4) * 90f else random.nextInt(16) * 22.5f
            check(spec, yaw, wall, random.nextInt(1, 2000), random.nextInt(1, 2000))
        }
    }

    @Test
    fun parseFollowsMarumaSignRules() {
        val spec = assertNotNull(SignParts.parseMaruma("https://a.b/c.png|1|2|3|4|5|6|7|8"))
        assertEquals(4f, spec.scaleX)
        assertEquals(8f, spec.pitch)
        assertEquals(1000f, SignParts.parseMaruma("u|5000|0|0|1|1|0|0|0")!!.x)
        assertNull(SignParts.parseMaruma("u|NaN|0|0|1|1|0|0|0"))
        assertNull(SignParts.parseMaruma("u|1|2|3"))
        assertNull(SignParts.parseMaruma("ただの看板"))
    }
}
