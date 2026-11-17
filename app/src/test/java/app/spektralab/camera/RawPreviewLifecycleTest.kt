package app.spektralab.camera

import android.app.Application
import android.util.Range
import android.util.Size
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.CameraInfoInternal
import androidx.camera.core.impl.CameraInternal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Surface negotiation tests only: these do not create EGL or capture camera frames. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class RawPreviewLifecycleTest {
    private val direct = Executor { it.run() }

    private fun request(): SurfaceRequest {
        val info = Proxy.newProxyInstance(CameraInfoInternal::class.java.classLoader,
            arrayOf(CameraInfoInternal::class.java)) { _, method, _ ->
            when (method.name) {
                "getSensorRotationDegrees", "getLensFacing" -> 0
                else -> null
            }
        } as CameraInfoInternal
        val camera = Proxy.newProxyInstance(CameraInternal::class.java.classLoader,
            arrayOf(CameraInternal::class.java)) { _, method, _ ->
            when (method.name) {
                "getCameraInfo", "getCameraInfoInternal" -> info
                "getHasTransform" -> true
                "isFrontFacing" -> false
                else -> null
            }
        } as CameraInternal
        return SurfaceRequest(Size(640, 480), camera, DynamicRange.SDR, Range(30, 30)) {}
    }

    @Test fun closingPendingBridgeSettlesBothRequestsExactlyOnce() {
        val upstream = request()
        var forwarded: SurfaceRequest? = null
        var closed = 0
        val bridge = RawPreviewSurface(upstream, direct) { closed++ }
        val viewfinder = FilmViewfinder(RuntimeEnvironment.getApplication()) { fail(it) }
        try {
            bridge.connect(Preview.SurfaceProvider { forwarded = it }, viewfinder)
            assertFalse(forwarded!!.deferrableSurface.terminationFuture.isDone)
            bridge.close()
            bridge.close()
            assertTrue(upstream.deferrableSurface.surface.isDone)
            assertTrue(forwarded!!.deferrableSurface.terminationFuture.isDone)
            assertEquals(1, closed)
        } finally { viewfinder.release() }
    }

    @Test fun upstreamCancellationReleasesPendingDownstream() {
        val upstream = request()
        var forwarded: SurfaceRequest? = null
        val bridge = RawPreviewSurface(upstream, direct)
        val viewfinder = FilmViewfinder(RuntimeEnvironment.getApplication()) { fail(it) }
        try {
            bridge.connect(Preview.SurfaceProvider { forwarded = it }, viewfinder)
            upstream.deferrableSurface.close()
            assertTrue(forwarded!!.deferrableSurface.terminationFuture.isDone)
        } finally { bridge.close(); viewfinder.release() }
    }

    @Test fun downstreamRefusalDeclinesCameraInputWithoutStartingGl() {
        val upstream = request()
        val viewfinder = FilmViewfinder(RuntimeEnvironment.getApplication()) { fail(it) }
        var closed = false
        try {
            RawPreviewSurface(upstream, direct) { closed = true }.connect(
                Preview.SurfaceProvider { it.willNotProvideSurface() }, viewfinder)
            assertTrue(closed)
            assertTrue(upstream.deferrableSurface.surface.isDone)
        } finally { viewfinder.release() }
    }

    @Test fun ownerReleaseSettlesPendingBridgeAndRejectsLateRequests() {
        val viewfinder = FilmViewfinder(RuntimeEnvironment.getApplication()) { fail(it) }
        val upstream = request()
        val provider = viewfinder.rawSurfaceProvider(Preview.SurfaceProvider { /* no surface yet */ })
        provider.onSurfaceRequested(upstream)
        val queued = CountDownLatch(1)
        viewfinder.executor.execute { queued.countDown() }
        assertTrue(queued.await(3, TimeUnit.SECONDS))
        viewfinder.release()
        // willNotProvideSurface completes with an exception: completion, not success,
        // is what proves the camera cannot hang waiting for a dead GL worker.
        runCatching { upstream.deferrableSurface.surface.get(3, TimeUnit.SECONDS) }
        assertTrue(upstream.deferrableSurface.surface.isDone)
        val late = request()
        provider.onSurfaceRequested(late)
        assertTrue(late.deferrableSurface.surface.isDone)
    }
}
