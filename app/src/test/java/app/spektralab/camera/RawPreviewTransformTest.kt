package app.spektralab.camera

import android.opengl.Matrix
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RawPreviewTransformTest {
    @Test
    fun cameraOrientationIsRemovedBeforePreviewViewAppliesDisplayRotation() {
        // SurfaceTexture includes the camera's native orientation. The bridge must
        // leave an ordinary GL vertical flip for PreviewView, for every lens pose.
        for (degrees in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) {
            val camera = identity()
            Matrix.translateM(camera, 0, 0f, 1f, 0f)
            Matrix.scaleM(camera, 0, 1f, -1f, 1f)
            Matrix.translateM(camera, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(camera, 0, degrees.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(camera, 0, -0.5f, -0.5f, 0f)
            if (front) {
                Matrix.translateM(camera, 0, 1f, 0f, 0f)
                Matrix.scaleM(camera, 0, -1f, 1f, 1f)
            }
            val actual = FloatArray(16)
            Matrix.multiplyMM(actual, 0, camera, 0, rawPreviewTransform(degrees, front, true), 0)
            val expected = identity()
            Matrix.translateM(expected, 0, 0f, 1f, 0f)
            Matrix.scaleM(expected, 0, 1f, -1f, 1f)
            assertArrayEquals("sensor=$degrees front=$front", expected, actual, 1e-5f)
        }
    }

    @Test
    fun buffersWithoutCameraTransformNeedNoAdditionalCompensation() {
        assertArrayEquals(identity(), rawPreviewTransform(90, true, false), 1e-5f)
    }

    private fun identity() = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
}
