package app.spektralab.camera

import android.annotation.SuppressLint
import android.view.Surface
import android.opengl.Matrix
import androidx.camera.core.impl.utils.MatrixExt
import androidx.camera.core.CameraEffect
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.util.concurrent.Executor

/** Preview-only bridge: RAW capture cannot be bound to a CameraEffect. Keep the
 * camera's buffer geometry and forward its metadata so PreviewView still owns
 * crop, display rotation, front-camera mirroring and focus-point mapping.
 */
@SuppressLint("RestrictedApi")
internal class RawPreviewSurface(
    private val request: SurfaceRequest,
    private val executor: Executor,
    private val onClosed: (RawPreviewSurface) -> Unit = {},
) : SurfaceOutput {
    private val forwarded = SurfaceRequest(
        request.resolution, request.camera, request.dynamicRange,
        request.expectedFrameRate, { request.invalidate() },
    )
    private val additionalTransform = rawPreviewTransform(
        request.camera.cameraInfo.sensorRotationDegrees,
        request.camera.isFrontFacing,
        request.camera.hasTransform,
    )
    private var surface: Surface? = null
    private var closed = false
    private var useCountHeld = false

    fun connect(provider: Preview.SurfaceProvider, viewfinder: FilmViewfinder) {
        val output = forwarded.deferrableSurface
        if (closed) return
        output.incrementUseCount()
        useCountHeld = true
        request.setTransformationInfoListener(executor) {
            forwarded.updateTransformationInfo(SurfaceRequest.TransformationInfo.of(
                it.cropRect, it.rotationDegrees, it.targetRotation,
                false, it.sensorToBufferTransform, it.isMirroring,
            ))
        }
        request.addRequestCancellationListener(executor) { close() }
        val future = output.surface
        future.addListener({
            if (!closed) {
                runCatching { future.get() }.onSuccess {
                    surface = it
                    viewfinder.onOutputSurface(this)
                    if (!closed) {
                        viewfinder.onRawInputSurface(request) {
                            viewfinder.closeRawOutput(this)
                            close()
                        }
                    }
                }.onFailure {
                    request.willNotProvideSurface()
                    close()
                }
            }
        }, executor)
        provider.onSurfaceRequested(forwarded)
    }

    override fun getSurface(executor: Executor, listener: Consumer<SurfaceOutput.Event>): Surface =
        checkNotNull(surface)

    override fun getTargets() = CameraEffect.PREVIEW
    override fun getSize() = request.resolution
    override fun updateTransformMatrix(updated: FloatArray, original: FloatArray) {
        Matrix.multiplyMM(updated, 0, original, 0, additionalTransform, 0)
    }

    override fun close() {
        if (closed) return
        closed = true
        request.clearTransformationInfoListener()
        // Also settle the upstream request if PreviewView never supplied an output.
        // This is a no-op once an input surface has already been provided.
        request.willNotProvideSurface()
        forwarded.deferrableSurface.close()
        if (useCountHeld) {
            useCountHeld = false
            forwarded.deferrableSurface.decrementUseCount()
        }
        onClosed(this)
    }
}

/** Same buffer compensation as CameraX SurfaceOutput, with no output crop/rotation.
 * Camera buffers may already carry sensor rotation/mirroring in SurfaceTexture;
 * remove that before PreviewView applies the forwarded display transform.
 */
@SuppressLint("RestrictedApi")
internal fun rawPreviewTransform(sensorDegrees: Int, front: Boolean, hasCameraTransform: Boolean): FloatArray {
    val camera = FloatArray(16)
    Matrix.setIdentityM(camera, 0)
    MatrixExt.preVerticalFlip(camera, 0.5f)
    if (hasCameraTransform) {
        MatrixExt.preRotate(camera, sensorDegrees.toFloat(), 0.5f, 0.5f)
        if (front) {
            Matrix.translateM(camera, 0, 1f, 0f, 0f)
            Matrix.scaleM(camera, 0, -1f, 1f, 1f)
        }
    }
    val inverse = FloatArray(16)
    Matrix.invertM(inverse, 0, camera, 0)
    val output = FloatArray(16)
    Matrix.setIdentityM(output, 0)
    MatrixExt.preVerticalFlip(output, 0.5f)
    return FloatArray(16).also { Matrix.multiplyMM(it, 0, inverse, 0, output, 0) }
}
