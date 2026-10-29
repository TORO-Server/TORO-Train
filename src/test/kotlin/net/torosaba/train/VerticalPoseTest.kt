package net.torosaba.train

import net.torosaba.train.model.BlockCartModel
import net.torosaba.train.track.DirectedSegment
import net.torosaba.train.track.TrackPath
import net.torosaba.train.track.TrackSegment
import net.torosaba.train.train.Cart
import net.torosaba.train.train.Train
import net.torosaba.train.util.BlockKey
import org.bukkit.World
import org.joml.Vector3d
import org.joml.Vector3f
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** ハシゴのように真上・真下に進むとき、車体の上が線路の「上」の向き (壁から離れる向き) になるか */
class VerticalPoseTest {
    private val world = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(World::class.java)) { _, _, _ -> null } as World

    /** x = 0.5, z = 0 の壁に沿って y = 0 から 10 まで上る線路。上は +z (南) */
    private val ladder = object : TrackSegment {
        override val world: World get() = this@VerticalPoseTest.world
        override val length = 10.0
        override val id = "ladder"
        override fun point(s: Double, out: Vector3d): Vector3d = out.set(0.5, s, 0.0)
        override fun tangent(s: Double, out: Vector3d): Vector3d = out.set(0.0, 1.0, 0.0)
        override fun up(s: Double) = Vector3d(0.0, 0.0, 1.0)
        override fun triggerBlocks(from: Double, to: Double, out: MutableCollection<BlockKey>) {}
    }

    private fun poseOn(reversed: Boolean): Cart {
        val path = TrackPath(world)
        path.segments += DirectedSegment(ladder, reversed)
        path.headOffset = 6.0
        val train = Train(UUID.randomUUID(), "t", path)
        val cart = Cart(UUID.randomUUID(), BlockCartModel("test"))
        train.add(cart) { 1.25 }
        train.computePoses()
        return cart
    }

    private fun assertVec(expected: Vector3f, actual: Vector3f) {
        assertEquals(expected.x, actual.x, 1e-4f)
        assertEquals(expected.y, actual.y, 1e-4f)
        assertEquals(expected.z, actual.z, 1e-4f)
    }

    @Test
    fun climbing() {
        val cart = poseOn(false)
        assertVec(Vector3f(0f, 1f, 0f), cart.rotation.transform(Vector3f(0f, 0f, 1f)))
        assertVec(Vector3f(0f, 0f, 1f), cart.rotation.transform(Vector3f(0f, 1f, 0f)))
    }

    /** 逆さまのレールのように 180 度傾けた線路では、車体の上が下を向く (ぶら下がる) */
    @Test
    fun hanging() {
        val rail = object : TrackSegment {
            override val world: World get() = this@VerticalPoseTest.world
            override val length = 10.0
            override val id = "upside-down"
            override fun point(s: Double, out: Vector3d): Vector3d = out.set(s, 0.0, 0.5)
            override fun tangent(s: Double, out: Vector3d): Vector3d = out.set(1.0, 0.0, 0.0)
            override fun roll(s: Double) = 180.0
            override fun triggerBlocks(from: Double, to: Double, out: MutableCollection<BlockKey>) {}
        }
        val path = TrackPath(world)
        path.segments += DirectedSegment(rail, true)
        path.headOffset = 6.0
        val train = Train(UUID.randomUUID(), "t", path)
        val cart = Cart(UUID.randomUUID(), BlockCartModel("test"))
        train.add(cart) { 1.25 }
        train.computePoses()
        assertVec(Vector3f(-1f, 0f, 0f), cart.rotation.transform(Vector3f(0f, 0f, 1f)))
        assertVec(Vector3f(0f, -1f, 0f), cart.rotation.transform(Vector3f(0f, 1f, 0f)))
    }

    @Test
    fun descending() {
        val cart = poseOn(true)
        assertVec(Vector3f(0f, -1f, 0f), cart.rotation.transform(Vector3f(0f, 0f, 1f)))
        assertVec(Vector3f(0f, 0f, 1f), cart.rotation.transform(Vector3f(0f, 1f, 0f)))
    }
}
