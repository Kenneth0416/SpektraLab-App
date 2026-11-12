package app.spektralab.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.spektralab.R
import app.spektralab.camera.RawShooter
import app.spektralab.camera.FilmViewfinder
import app.spektralab.engine.Catalog
import app.spektralab.engine.LookMapper
import app.spektralab.render.Framing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class CameraSession(val camera: Camera, val capture: ImageCapture, val raw: Boolean,
                            /** For raw capture into memory; null when the camera would not tell. */
                            val chars: android.hardware.camera2.CameraCharacteristics? = null)

/**
 * The camera: a live viewfinder through the loaded film, and a shutter that saves the
 * untouched original (the sensor's raw data as DNG when the device offers it) to the
 * album. The shot goes on the lightbox with the camera's look, which stays editable.
 */
@Composable
fun CameraScreen(state: AppState) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val look by state.camLook.collectAsState()
    val activeSheet by state.sheet.collectAsState()
    val accent by animateColorAsState(Color(Catalog.film(look.film).accent), tween(500), label = "accent")
    var tool by rememberSaveable { mutableStateOf("film") }
    var front by rememberSaveable { mutableStateOf(false) }
    var flash by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var session by remember { mutableStateOf<CameraSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    val multiFrame by state.multiFrame.collectAsState()
    val merging by state.merging.collectAsState()
    // frames the next shot will take: follows the light (sensor gain) while multi-frame is on
    var burstFrames by remember { mutableIntStateOf(RawShooter.MAX_FRAMES) }
    LaunchedEffect(session, multiFrame) {
        val chars = session?.chars ?: return@LaunchedEffect
        while (multiFrame) { burstFrames = state.shooter.frameCount(chars); kotlinx.coroutines.delay(400) }
    }
    // exposure compensation for the film exposure, set by dragging on the finder after a tap
    var evBias by rememberSaveable { mutableFloatStateOf(0f) }
    // focus / metering point: 0 searching, 1 in focus, 2 could not focus; locked = held until tapped again
    var focusResult by remember { mutableIntStateOf(0) }
    var focusLocked by remember { mutableStateOf(false) }
    var focusTouchedAt by remember { mutableLongStateOf(0L) }

    // permission
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    // viewfinder pipeline
    val viewfinder = remember { FilmViewfinder(context) { msg -> ContextCompat.getMainExecutor(context).execute { state.say(msg) } } }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            // TextureView lets the shutter wait for a blurred buffer to be latched.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val lookState = rememberUpdatedState(look)
    LaunchedEffect(Unit) {
        snapshotFlow { lookState.value }.distinctUntilChanged().collectLatest { l ->
            val setup = withContext(Dispatchers.Default) { runCatching { state.app.looks.get(l) }.getOrNull() }
            if (setup != null) viewfinder.setLook(setup)
        }
    }

    // bind the camera while this screen is shown
    DisposableEffect(granted, front) {
        var provider: ProcessCameraProvider? = null
        var disposed = false
        if (granted) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (disposed) return@addListener
                val p = runCatching { future.get() }.getOrNull() ?: return@addListener
                provider = p
                var selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!runCatching { p.hasCamera(selector) }.getOrDefault(false)) {
                    selector = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    if (!runCatching { p.hasCamera(selector) }.getOrDefault(false)) { state.say(R.string.no_camera); return@addListener }
                }
                val fourThree = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                val rawSupported = runCatching {
                    ImageCapture.getImageCaptureCapabilities(p.getCameraInfo(selector)).supportedOutputFormats
                        .contains(ImageCapture.OUTPUT_FORMAT_RAW)
                }.getOrDefault(false)

                fun bind(raw: Boolean): CameraSession {
                    val preview = Preview.Builder().setResolutionSelector(fourThree).build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .apply {
                            // raw: the sensor's own data (Camera2 RAW_SENSOR), written as DNG
                            if (raw) setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                            else { setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG); setResolutionSelector(fourThree) }
                            if (raw) requestRawMetadata(this, state.shooter.results)
                        }.build()
                    val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture).addEffect(viewfinder.effect).build()
                    p.unbindAll()
                    val camera = p.bindToLifecycle(owner, selector, group)
                    return CameraSession(camera, capture, raw, if (raw) cameraCharacteristics(context, camera) else null)
                }
                session = runCatching { bind(rawSupported) }.recoverCatching {
                    // some devices list raw but cannot stream it next to the preview
                    if (rawSupported) bind(false) else throw it
                }.onFailure { state.say(R.string.camera_open_failed, it.message ?: context.getString(R.string.unknown_error)) }.getOrNull()
                session?.camera?.cameraControl?.setZoomRatio(zoom)
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            disposed = true
            session = null
            runCatching { provider?.unbindAll() }
        }
    }
    DisposableEffect(Unit) { onDispose { viewfinder.release() } }
    LaunchedEffect(evBias) { viewfinder.evBias = evBias }
    LaunchedEffect(front) { focusLocked = false }
    // zoom range of the bound lens (0.5x ultra-wide, tele steps)
    var zoomRange by remember { mutableStateOf(1f to 1f) }
    DisposableEffect(session) {
        val live = session?.camera?.cameraInfo?.zoomState
        val obs = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { zoomRange = it.minZoomRatio to it.maxZoomRatio }
        live?.observe(owner, obs)
        onDispose { live?.removeObserver(obs) }
    }
    // read the lens data once per bound session, not per recomposition
    val lens = remember(session) { session?.let { readLens(context, it.camera) } }
    // zoom stops shared by the chips and the pinch haptic; real lenses when they can be read
    val zoomStops = remember(zoomRange, lens) {
        val (min, max) = zoomRange
        val real = lens?.stops.orEmpty().filter { it in min..max }
        val all = (real + 1f).sorted().fold(listOf<Float>()) { acc, z ->
            if (acc.isNotEmpty() && z / acc.last() < 1.1f) acc else acc + z
        }
        if (real.size >= 2) all else buildList {
            if (min < 0.95f) add(min)
            add(1f)
            if (max >= 2f) add(2f)
            if (max >= 5f) add(5f)
        }
    }
    // 35mm-equivalent label for a zoom ratio; null when the focal length is unknown (falls back to ×)
    val baseMm = lens?.baseMm
    // only talk to the camera when the ratio really changed (pinch events often repeat a value)
    var appliedZoom by remember(session) { mutableFloatStateOf(Float.NaN) }
    fun applyZoom(z: Float) {
        zoom = z
        if (z == appliedZoom) return
        appliedZoom = z
        session?.camera?.cameraControl?.setZoomRatio(z)
    }
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    // chips and double tap glide to the target; a pinch or lens flip cancels the glide
    fun animateZoom(target: Float) {
        zoomJob?.cancel()
        zoomJob = scope.launch {
            Animatable(zoom).animateTo(target.coerceIn(zoomRange.first, zoomRange.second), Motion.glide) {
                // a spring may overshoot the lens range, which the camera rejects
                applyZoom(value.coerceIn(zoomRange.first, zoomRange.second))
            }
        }
    }


    // ------------------------------------------------------------------ film transport
    // Like a mechanical camera: releasing the shutter leaves the frame exposed and the
    // finder goes to ground glass until the film is advanced with the lever.
    val prefs = remember { context.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE) }
    var cocked by remember { mutableStateOf(prefs.getBoolean("cocked", true)) }
    var frame by remember { mutableIntStateOf(prefs.getInt("frame", 1)) }
    var autoWind by remember { mutableStateOf(prefs.getBoolean("autoWind", false)) }
    var nudge by remember { mutableIntStateOf(0) }
    var autoStroke by remember { mutableIntStateOf(0) }
    val blades = remember { Animatable(0f) }
    val clarity = remember { Animatable(if (prefs.getBoolean("cocked", true)) 1f else 0f) }
    LaunchedEffect(Unit) { snapshotFlow { clarity.value }.collect { viewfinder.clarity = it } }
    fun advanceFilm() {
        if (busy || cocked) return
        busy = true
        cocked = true
        frame = frame % 36 + 1
        prefs.edit().putBoolean("cocked", true).putInt("frame", frame).apply()
        // the finder snaps back into focus
        scope.launch {
            clarity.animateTo(1f, tween(520, easing = LinearOutSlowInEasing))
            // Also recover a curtain held closed because the preview stalled.
            blades.animateTo(0f, tween(260, easing = LinearOutSlowInEasing))
            busy = false
        }
    }

    // ------------------------------------------------------------------ orientation
    // The picture is tagged with the way the phone is actually held, also when screen
    // rotation is locked; controls then turn in place like on other phone cameras.
    var deviceDeg by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l = object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val snapped = when (o) { in 45 until 135 -> 90; in 135 until 225 -> 180; in 225 until 315 -> 270; else -> 0 }
                if (snapped != deviceDeg) deviceDeg = snapped
            }
        }
        if (l.canDetectOrientation()) l.enable()
        onDispose { l.disable() }
    }
    val displayDeg = when (androidx.compose.ui.platform.LocalView.current.display?.rotation) {
        android.view.Surface.ROTATION_90 -> 90; android.view.Surface.ROTATION_180 -> 180; android.view.Surface.ROTATION_270 -> 270; else -> 0
    }
    val turn = (((-(deviceDeg + displayDeg)) % 360 + 540) % 360 - 180).let { if (it == -180) 180 else it }
    val iconTurn by animateFloatAsState(turn.toFloat(), Motion.settle, label = "turn")

    // focal-plane shutter: two blades close over the frame and open again
    fun shoot() {
        val s = session ?: return state.say(if (granted) R.string.camera_not_ready else R.string.camera_permission_required)
        if (busy) return
        if (!state.shooter.available) return state.say(R.string.previous_shot_processing)
        if (!cocked) {
            // shutter is locked until the film is advanced
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            nudge++
            return
        }
        busy = true
        cocked = false
        prefs.edit().putBoolean("cocked", false).apply()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val captured = CompletableDeferred<Boolean>()
        val shutter = scope.launch {
            blades.animateTo(1f, tween(70, easing = FastOutLinearInEasing))
            val previousBlur = viewfinder.blurredTimestamp
            clarity.snapTo(0f)
            viewfinder.clarity = 0f
            if (previewView.awaitBlurredPreview(viewfinder, previousBlur)) {
                blades.animateTo(0f, tween(260, easing = LinearOutSlowInEasing))
            } else {
                state.say(R.string.viewfinder_stalled)
            }
        }
        scope.launch {
            if (captured.await()) {
                shutter.join()
                busy = false
                if (autoWind) autoStroke++
            } else {
                // Cancel before restoring clarity so the old shot cannot blur it again.
                shutter.cancelAndJoin()
                cocked = true
                prefs.edit().putBoolean("cocked", true).apply()
                clarity.animateTo(1f, tween(300))
                blades.animateTo(0f, tween(260, easing = LinearOutSlowInEasing))
                busy = false
            }
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        s.capture.targetRotation = when (deviceDeg) {
            90 -> android.view.Surface.ROTATION_270; 180 -> android.view.Surface.ROTATION_180
            270 -> android.view.Surface.ROTATION_90; else -> android.view.Surface.ROTATION_0
        }
        // the original through CameraX's own saving: JPEG, or raw where frames cannot be taken into memory
        fun saveThroughCameraX() {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "SPK_$stamp." + if (s.raw) "dng" else "jpg")
                put(MediaStore.Images.Media.MIME_TYPE, if (s.raw) "image/x-adobe-dng" else "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Silvercam")
            }
            s.capture.flashMode = flash
            val options = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
            s.capture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    captured.complete(true)
                    result.savedUri?.let { state.onCaptured(it, evBias) } ?: state.say(R.string.original_not_saved)
                }

                override fun onError(e: ImageCaptureException) {
                    captured.complete(false)
                    state.say(R.string.capture_failed, e.message ?: e.imageCaptureError.toString())
                }
            })
        }
        val chars = s.chars
        if (s.raw && chars != null && !state.shooter.unsupported) {
            // raw frames into memory: one, or a burst that is merged in the background
            val frames = if (multiFrame) state.shooter.frameCount(chars) else 1
            s.capture.flashMode = if (frames > 1) ImageCapture.FLASH_MODE_OFF else flash
            if (frames > 1) state.say(R.string.burst_hold_steady, frames)
            val accepted = state.shooter.shoot(s.capture, chars, frames, "SPK_$stamp", evBias) { ok, retry ->
                when {
                    ok -> captured.complete(true)
                    retry && session === s -> saveThroughCameraX()
                    else -> { captured.complete(false); state.say(R.string.capture_failed_short) }
                }
            }
            if (!accepted) { captured.complete(false); state.say(R.string.previous_shot_processing) }
        } else saveThroughCameraX()
    }
    val shootNow by rememberUpdatedState(::shoot)
    LaunchedEffect(Unit) { state.shutterKey.collect { shootNow() } }

    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fold = LocalFold.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val hinge = fold.hinge?.translate(-origin)
    val density = androidx.compose.ui.platform.LocalDensity.current
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    val items by state.library.items.collectAsState()
    val last = items.firstOrNull()
    val positive = state.app.engine.film(LookMapper.filmStock(look)).isPositive

    // ------------------------------------------------------------------ parts
    val topItems: @Composable (@Composable () -> Unit) -> Unit = { gap ->
        Text(stringResource(R.string.back_to_lightbox), color = Pal.text, fontSize = 15.sp, modifier = Modifier.springClick { state.closeCamera() }.padding(6.dp))
        // what the shutter will keep as the original
        AnimatedContent(session?.raw, label = "format", transitionSpec = { fadeIn() togetherWith fadeOut() }) { raw ->
            // tapping the badge switches multi-frame capture (raw only)
            Text(when (raw) { true -> if (multiFrame) "RAW×$burstFrames" else "RAW"; false -> "JPEG"; null -> "…" },
                fontFamily = Fonts.mono, fontSize = 10.sp,
                color = if (raw == true) accent else Pal.dim,
                modifier = Modifier.springClick {
                    if (raw == true) {
                        state.setMultiFrame(!multiFrame)
                        state.say(if (!multiFrame) context.getString(R.string.multi_frame_on, RawShooter.MAX_FRAMES) else context.getString(R.string.single_frame_raw))
                    }
                }.padding(6.dp).border(1.dp, if (raw == true) accent else Pal.off, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        merging?.let { Text(stringResource(R.string.merging_percent, (it * 100).toInt()), fontFamily = Fonts.mono, fontSize = 10.sp, color = Pal.dim, modifier = Modifier.padding(2.dp)) }
        gap()
        val flashLabel = stringResource(when (flash) {
            ImageCapture.FLASH_MODE_ON -> R.string.flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.string.flash_auto
            else -> R.string.flash_off
        })
        Text(flashLabel, color = if (flash == ImageCapture.FLASH_MODE_OFF) Pal.dim else Pal.text, fontSize = 13.sp, modifier = Modifier.springClick {
            flash = when (flash) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
        }.padding(8.dp))
        Text(stringResource(R.string.recipes), color = Pal.text, fontSize = 13.sp, modifier = Modifier.springClick { state.openRecipes() }.padding(8.dp))
        // the film settings stay out of the way until asked for
        val toolsInk by animateColorAsState(if (toolsOpen) Pal.dink else Pal.text, tween(180), label = "toolsInk")
        val toolsBg by animateColorAsState(if (toolsOpen) accent else Color.Transparent, tween(180), label = "toolsBg")
        Text(stringResource(R.string.adjust), color = toolsInk, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp)
            .springClick { toolsOpen = !toolsOpen }.clip(RoundedCornerShape(14.dp)).background(toolsBg)
            .border(1.dp, accent, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
    }

    val viewfinderPart: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            val aspect = if (landscape) 4f / 3f else 3f / 4f
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val boxAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
                var focusAt by remember { mutableStateOf<Offset?>(null) }
                val focusRing = remember { Animatable(0f) }
                Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < boxAspect)
                    .pointerInput(session) {
                        // tap: focus and meter the sensor there (released after a few seconds);
                        // long press: hold focus until the next tap (AE continues to meter)
                        fun focusOn(p: Offset, lock: Boolean) {
                            val s = session ?: return
                            val point = previewView.meteringPointFactory.createPoint(p.x, p.y)
                            val action = FocusMeteringAction.Builder(point).apply {
                                if (lock) disableAutoCancel() else setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS)
                            }.build()
                            focusAt = p
                            focusResult = 0
                            focusLocked = lock
                            focusTouchedAt = System.currentTimeMillis()
                            scope.launch { focusRing.snapTo(1f); focusRing.animateTo(if (lock) 0.6f else 0f, tween(if (lock) 300 else 1600)) }
                            runCatching {
                                val f = s.camera.cameraControl.startFocusAndMetering(action)
                                f.addListener({
                                    // the lens reports whether it actually found focus
                                    focusResult = if (runCatching { f.get().isFocusSuccessful }.getOrDefault(false)) 1 else 2
                                    if (focusResult == 1) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }, ContextCompat.getMainExecutor(context))
                            }
                        }
                        detectTapGestures(
                            onTap = { p ->
                                if (focusLocked) {
                                    // unlock: back to continuous autofocus and metering
                                    focusLocked = false
                                    runCatching { session?.camera?.cameraControl?.cancelFocusAndMetering() }
                                    scope.launch { focusRing.animateTo(0f, tween(250)) }
                                } else focusOn(p, lock = false)
                            },
                            onLongPress = { p -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); focusOn(p, lock = true) },
                            onDoubleTap = { evBias = 0f },
                        )
                    }
                    .pointerInput(session, zoomRange, zoomStops) {
                        // pinch to zoom: follows the fingers directly, no animation
                        detectTransformGestures { _, pan, change, _ ->
                            if (change != 1f) {
                                zoomJob?.cancel()
                                val old = zoom
                                val new = (old * change).coerceIn(zoomRange.first, zoomRange.second)
                                // tick when crossing (or landing on) a stop
                                if (new != old && zoomStops.any { (old - it) * (new - it) < 0f || (new == it && old != it) })
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                applyZoom(new)
                            } else if (focusLocked || System.currentTimeMillis() - focusTouchedAt < 5000) {
                                focusTouchedAt = System.currentTimeMillis()
                                evBias = (evBias - pan.y / 240.dp.toPx() * 2f).coerceIn(-2f, 2f)
                            }
                        }
                    }) {
                    if (granted) AndroidView({ previewView }, Modifier.fillMaxSize())
                    else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.camera_permission_explanation), color = Pal.dim, fontSize = 14.sp)
                        Text(stringResource(R.string.grant_permission), color = Pal.dink, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)
                            .springClick { ask.launch(Manifest.permission.CAMERA) }
                            .clip(RoundedCornerShape(16.dp)).background(accent).padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                    // the film format's frame lines: outside is masked, like a rangefinder
                    val crop = remember(look.format, landscape) {
                        Framing.compute(if (landscape) 4000 else 3000, if (landscape) 3000 else 4000, look, 4000).crop
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        val l = crop[0] * size.width; val r = crop[2] * size.width
                        val t = (1 - crop[3]) * size.height; val b = (1 - crop[1]) * size.height
                        val mask = Pal.dark.copy(alpha = 0.78f)
                        if (t > 0f) drawRect(mask, Offset.Zero, Size(size.width, t))
                        if (b < size.height) drawRect(mask, Offset(0f, b), Size(size.width, size.height - b))
                        if (l > 0f) drawRect(mask, Offset(0f, t), Size(l, b - t))
                        if (r < size.width) drawRect(mask, Offset(r, t), Size(size.width - r, b - t))
                        // frame not advanced: the finder is dim ground glass
                        if (clarity.value < 1f) drawRect(Pal.dark.copy(alpha = 0.5f * (1f - clarity.value)))
                        // shutter blades
                        if (blades.value > 0f) {
                            val hgt = size.height / 2 * blades.value
                            drawRect(Color.Black, Offset.Zero, Size(size.width, hgt))
                            drawRect(Color.Black, Offset(0f, size.height - hgt), Size(size.width, hgt))
                        }
                        // focus ring closing in on the tapped point
                        focusAt?.let { c ->
                            if (focusRing.value > 0f) {
                                // white while searching, the roll's colour once sharp, red if the lens gave up
                                val ringColor = when (focusResult) { 1 -> accent; 2 -> Pal.safelight; else -> Pal.text }
                                val rad = (22.dp.toPx()) * (1f + 0.6f * ((focusRing.value - 0.6f).coerceAtLeast(0f) / 0.4f))
                                drawCircle(ringColor.copy(alpha = focusRing.value.coerceAtMost(1f)), radius = rad, center = c,
                                    style = Stroke((if (focusLocked) 2.5f else 1.5f).dp.toPx()))
                                // exposure compensation marker riding next to the ring
                                if (evBias != 0f) {
                                    val x = c.x + rad + 12.dp.toPx()
                                    drawLine(ringColor.copy(alpha = 0.6f), Offset(x, c.y - 26.dp.toPx()), Offset(x, c.y + 26.dp.toPx()), 1.dp.toPx())
                                    drawCircle(ringColor, 4.dp.toPx(), Offset(x, c.y - evBias / 2f * 26.dp.toPx()))
                                }
                            }
                        }
                    }
                    CropCorners(Modifier.fillMaxSize(), busy)
                    // exposure compensation and lock read-outs
                    Column(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalAlignment = Alignment.End) {
                        androidx.compose.animation.AnimatedVisibility(evBias != 0f, enter = fadeIn(), exit = fadeOut()) {
                            Text(signed(((evBias * 10).toInt() / 10.0), 1, " EV"), fontFamily = Fonts.mono, fontSize = 11.sp, color = accent,
                                modifier = Modifier.graphicsLayer { rotationZ = iconTurn }.background(Color(0x99140807)).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        androidx.compose.animation.AnimatedVisibility(focusLocked, enter = fadeIn(), exit = fadeOut()) {
                            Text(stringResource(R.string.focus_locked), fontSize = 10.sp, color = Pal.dink,
                                modifier = Modifier.padding(top = 4.dp).graphicsLayer { rotationZ = iconTurn }.background(accent, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                    Text(summary(look).uppercase(Locale.ROOT), fontFamily = Fonts.mono, fontSize = 9.sp, color = Pal.text,
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Color(0x99140807)).padding(horizontal = 6.dp, vertical = 2.dp))
                    // zoom steps sit on the lower edge of the finder, where thumbs expect them
                    val stops = zoomStops
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                        .background(Color(0x80140807), CircleShape).padding(3.dp)) {
                        val nearest = stops.minByOrNull { kotlin.math.abs(it - zoom) }
                        stops.forEach { z ->
                            val on = z == nearest
                            val live = on && kotlin.math.abs(zoom - z) > 0.05f
                            val label = if (baseMm != null) "${Math.round(baseMm * (if (live) zoom else z))}" + if (on) "mm" else ""
                                else if (live) "%.1f×".format(zoom)
                                else if (z < 1f) "%.1f".format(z).trimStart('0') else "${z.toInt()}" + if (on) "×" else ""
                            Text(label, fontFamily = Fonts.mono, fontSize = 11.sp,
                                color = if (on) Pal.dink else Pal.text, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 2.dp).springClick {
                                    animateZoom(z)
                                }.graphicsLayer { rotationZ = iconTurn }.clip(CircleShape).background(if (on) accent else Color.Transparent)
                                    .padding(horizontal = 9.dp, vertical = 7.dp))
                        }
                    }
                }
            }
            Safelight(Modifier.align(Alignment.TopEnd).padding(2.dp))
        }
    }

    val thumbPart: @Composable () -> Unit = {
        // last frame on the lightbox: tap to open it in the darkroom
        val previousDescription = stringResource(R.string.previous_frame)
        Box(Modifier.size(52.dp).graphicsLayer { rotationZ = iconTurn }.clip(RoundedCornerShape(6.dp)).background(Pal.line)
            .semantics { contentDescription = previousDescription }
            .springClick(0.9f) { last?.let { state.openEdit(it.id) } ?: state.say(R.string.no_photos_taken) }) {
            if (last != null) {
                val thumb by produceState(state.app.thumbs.cached(last), last.id, last.look) { value = state.app.thumbs.get(last) }
                Crossfade(thumb, label = "last") { t ->
                    if (t != null) Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }
    val shutterPart: @Composable () -> Unit = {
        // shutter release: locked (hollow, dim) until the film is advanced
        val sink by animateFloatAsState(if (busy) 0.72f else 1f, Motion.press, label = "shutter")
        val ready by animateFloatAsState(if (cocked) 1f else 0f, tween(260), label = "ready")
        val shutterDescription = stringResource(if (cocked) R.string.shutter else R.string.shutter_not_advanced)
        Box(Modifier.size(76.dp).semantics { contentDescription = shutterDescription }.springClick(0.9f) { shoot() }
            .border(3.dp, Pal.text.copy(alpha = 0.45f + 0.55f * ready), CircleShape).padding(7.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = sink; scaleY = sink; alpha = 0.18f + 0.82f * ready }
                .clip(CircleShape).background(if (busy) accent else Pal.text))
        }
    }
    val flipPart: @Composable () -> Unit = {
        val spin = remember { Animatable(0f) }
        val switchDescription = stringResource(R.string.switch_camera)
        Box(Modifier.size(52.dp).semantics { contentDescription = switchDescription }
            .springClick(0.9f) {
                front = !front; zoomJob?.cancel(); zoom = 1f
                scope.launch { spin.snapTo(0f); spin.animateTo(180f, Motion.settle) }
            }.border(1.dp, Pal.off, CircleShape), contentAlignment = Alignment.Center) {
            // Keep the drawing and rotation anchored to the button's center.
            CameraFlipIcon(Modifier.size(28.dp).graphicsLayer { rotationZ = iconTurn + spin.value }, Pal.text)
        }
    }
    val transportPart: @Composable (Boolean) -> Unit = { vertical ->
        val counter: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { rotationZ = iconTurn }) {
                FrameCounter(frame)
                Text("/ 36", fontFamily = Fonts.mono, fontSize = 8.sp, color = Pal.dim)
            }
        }
        val motor: @Composable () -> Unit = {
            // motor drive for those who do not want to wind every frame
            Text(stringResource(if (autoWind) R.string.auto_wind_on else R.string.auto_wind_off),
                color = if (autoWind) accent else Pal.dim, fontSize = 11.sp,
                modifier = Modifier.springClick {
                    autoWind = !autoWind
                    prefs.edit().putBoolean("autoWind", autoWind).apply()
                    if (autoWind && !cocked && !busy) autoStroke++
                }.padding(6.dp))
        }
        val lever: @Composable () -> Unit = {
            FilmAdvanceLever(cocked, accent, nudge, autoStroke, guidanceEnabled = !busy && !autoWind && activeSheet == Sheet.NONE) { advanceFilm() }
        }
        if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally) { counter(); lever(); motor() }
        else Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            counter(); Spacer(Modifier.width(10.dp)); motor(); Spacer(Modifier.weight(1f)); lever()
        }
    }
    val toolsPart: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.clip(RoundedCornerShape(14.dp)).background(Pal.darkPanel.copy(alpha = 0.96f))) {
            ToolDeck(state, tool, { tool = it }, look, accent, positive, isVideo = false,
                onSet = { _, l -> state.setCamLook(l) }, onApply = { state.setCamLook(it) })
        }
    }

    // ------------------------------------------------------------------ layout
    Box(Modifier.fillMaxSize().background(Pal.dark).statusBarsPadding().navigationBarsPadding()
        .displayCutoutPadding().onGloballyPositioned { origin = it.positionInWindow() }) {
        val tabletop = fold.posture == Posture.TABLETOP && hinge != null && hinge.top > 0f
        val book = fold.posture == Posture.BOOK && hinge != null && hinge.left > 0f
        when {
            tabletop -> Column(Modifier.fillMaxSize()) {
                // half folded on a table: finder on the upright half, everything else on the flat half
                Column(Modifier.fillMaxWidth().height(with(density) { hinge!!.top.toDp() })) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { topItems { Spacer(Modifier.weight(1f)) } }
                    viewfinderPart(Modifier.fillMaxWidth().weight(1f))
                }
                Spacer(Modifier.height(with(density) { hinge!!.height.toDp() }))
                Row(Modifier.fillMaxSize()) {
                    toolsPart(Modifier.weight(1f).fillMaxHeight().padding(8.dp))
                    Column(Modifier.fillMaxHeight().padding(end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly) {
                        transportPart(true); shutterPart(); Row(verticalAlignment = Alignment.CenterVertically) { thumbPart(); Spacer(Modifier.width(14.dp)); flipPart() }
                    }
                }
            }
            book -> Row(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxHeight().width(with(density) { hinge!!.left.toDp() })) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { topItems { Spacer(Modifier.weight(1f)) } }
                    viewfinderPart(Modifier.fillMaxWidth().weight(1f))
                }
                Spacer(Modifier.width(with(density) { hinge!!.width.toDp() }))
                Column(Modifier.fillMaxSize()) {
                    toolsPart(Modifier.fillMaxWidth().weight(1f).padding(8.dp))
                    transportPart(false)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) { thumbPart(); shutterPart(); flipPart() }
                }
            }
            landscape -> Row(Modifier.fillMaxSize()) {
                // held sideways: settings on the left edge, shutter and lever under the right thumb
                Column(Modifier.fillMaxHeight().padding(vertical = 6.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween) { topItems { } }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    viewfinderPart(Modifier.fillMaxSize())
                    androidx.compose.animation.AnimatedVisibility(toolsOpen, Modifier.align(Alignment.CenterEnd),
                        enter = slideInHorizontally(Motion.glideOffset) { it } + fadeIn(), exit = slideOutHorizontally(tween(200)) { it } + fadeOut()) {
                        toolsPart(Modifier.width(340.dp).fillMaxHeight().padding(6.dp))
                    }
                }
                Column(Modifier.fillMaxHeight().padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween) {
                    flipPart(); transportPart(true); shutterPart(); thumbPart()
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().dealIn(0, fromY = -40f).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { topItems { Spacer(Modifier.weight(1f)) } }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    viewfinderPart(Modifier.fillMaxSize())
                    androidx.compose.animation.AnimatedVisibility(toolsOpen, Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically(Motion.glideOffset) { it } + fadeIn(), exit = slideOutVertically(tween(200)) { it } + fadeOut()) {
                        toolsPart(Modifier.fillMaxWidth().height(300.dp).padding(6.dp))
                    }
                }
                transportPart(false)
                Row(Modifier.fillMaxWidth().dealIn(1, fromY = 60f).padding(horizontal = 30.dp).padding(bottom = 14.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    thumbPart(); shutterPart(); flipPart()
                }
            }
        }
    }
}

/**
 * Ask the camera for what a raw developer needs and a JPEG pipeline does itself:
 * the lens shading map (DngCreator then embeds it as gain maps, so vignetting and colour
 * shading can be corrected) and hot pixel correction on the sensor data.
 */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
private fun requestRawMetadata(builder: ImageCapture.Builder, results: android.hardware.camera2.CameraCaptureSession.CaptureCallback) {
    runCatching {
        androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
            .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                android.hardware.camera2.CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.HOT_PIXEL_MODE,
                android.hardware.camera2.CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
            // the capture result of every frame: a raw frame becomes a DNG with its own
            .setSessionCaptureCallback(results)
    }
}

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
private fun cameraCharacteristics(context: android.content.Context, camera: Camera): android.hardware.camera2.CameraCharacteristics? = runCatching {
    val id = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo).cameraId
    context.getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics(id)
}.getOrNull()

/** Base 35mm-equivalent focal length (zoom 1.0) and the zoom ratios of the phone's physical lenses. */
private class LensInfo(val baseMm: Float, val stops: List<Float>)

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
private fun readLens(context: android.content.Context, camera: Camera): LensInfo? = runCatching {
    val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
    fun equiv(c: android.hardware.camera2.CameraCharacteristics): Float? {
        val focal = c.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val size = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diag = kotlin.math.hypot(size.width, size.height)
        return if (focal > 0f && diag > 0f) focal * 43.27f / diag else null
    }
    val logical = info.getCameraCharacteristicsMap()[info.cameraId]
        ?: (context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager).getCameraCharacteristics(info.cameraId)
    val base = equiv(logical)?.takeIf { it > 0f } ?: return@runCatching null
    val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    val stops = runCatching {
        logical.physicalCameraIds.mapNotNull { id -> equiv(manager.getCameraCharacteristics(id))?.let { it / base } }
    }.getOrDefault(emptyList())
    LensInfo(base, stops)
}.getOrNull()
