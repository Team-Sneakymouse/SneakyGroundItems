package com.danidipp.sneakygrounditems

import org.bukkit.block.BlockFace
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlacementServiceTest {
    private val persistence = PersistenceService(testPlugin())
    private val service = PlacementService(persistence, LockService(persistence))

    @Test
    fun rescaleTransformationPreservesOrientationAndUpdatesScale() {
        val original = Transformation(
            Vector3f(1f, 2f, 3f),
            Quaternionf().rotateY(0.5f),
            Vector3f(0.5f, 0.5f, 0.5f),
            Quaternionf().rotateX(0.25f),
        )

        val rescaled = service.rescaleTransformation(original, 1.75f)

        assertEquals(Vector3f(1f, 2f, 3f), rescaled.translation)
        assertEquals(Quaternionf(original.leftRotation), rescaled.leftRotation)
        assertEquals(Quaternionf(original.rightRotation), rescaled.rightRotation)
        assertEquals(Vector3f(1.75f, 1.75f, 1.75f), rescaled.scale)
    }

    @Test
    fun deriveFaceMatchesDisplayRotation() {
        val southDisplay = fakeItemDisplay(
            Transformation(
                Vector3f(),
                Quaternionf(),
                Vector3f(1f, 1f, 1f),
                Quaternionf(),
            ),
        )
        val northDisplay = fakeItemDisplay(
            Transformation(
                Vector3f(),
                Quaternionf().rotateY(PI.toFloat()),
                Vector3f(1f, 1f, 1f),
                Quaternionf(),
            ),
        )

        assertTrue(service.deriveFace(southDisplay).modZ > 0)
        assertTrue(service.deriveFace(northDisplay).modZ < 0)
    }

    @Test
    fun surfaceForFaceClassifiesBlockFace() {
        assertEquals(PlacementSurface.GROUND, service.surfaceForFace(BlockFace.UP))
        assertEquals(PlacementSurface.CEILING, service.surfaceForFace(BlockFace.DOWN))
        assertEquals(PlacementSurface.WALL, service.surfaceForFace(BlockFace.EAST))
    }
}
