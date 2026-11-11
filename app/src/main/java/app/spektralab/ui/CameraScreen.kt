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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.spektralab.camera.FilmViewfinder
import app.spektralab.engine.Catalog
import app.spektralab.engine.LookMapper
import app.spektralab.render.Framing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class CameraSession(val camera: Camera, val capture: ImageCapture, val raw: Boolean)

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
    val accent by animateColorAsState(Color(Catalog.film(look.film).accent), tween(500), label = "accent")
    var tool by rememberSaveable { mutableStateOf("film") }
    var front by rememberSaveable { mutableStateOf(false) }
    var flash by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var session by remember { mutableStateOf<CameraSession?>(null) }
    var busy by remember { mutableStateOf(false) }

    // permission
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    // viewfinder pipeline
    val viewfinder = remember { FilmViewfinder { msg -> ContextCompat.getMainExecutor(context).execute { state.say(msg) } } }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
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
        if (granted) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val p = runCatching { future.get() }.getOrNull() ?: return@addListener
                provider = p
                var selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!runCatching { p.hasCamera(selector) }.getOrDefault(false)) {
                    selector = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    if (!runCatching { p.hasCamera(selector) }.getOrDefault(false)) { state.say("没有可用的相机"); return@addListener }
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
                        }.build()
                    val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture).addEffect(viewfinder.effect).build()
                    p.unbindAll()
                    return CameraSession(p.bindToLifecycle(owner, selector, group), capture, raw)
                }
                session = runCatching { bind(rawSupported) }.recoverCatching {
                    // some devices list raw but cannot stream it next to the preview
                    if (rawSupported) bind(false) else throw it
                }.onFailure { state.say("相机打不开：${it.message ?: "未知错误"}") }.getOrNull()
                session?.camera?.cameraControl?.setZoomRatio(zoom)
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            session = null
            runCatching { provider?.unbindAll() }
        }
    }
    DisposableEffect(Unit) { onDispose { viewfinder.release() } }
    // zoom range of the bound lens (0.5x ultra-wide, tele steps)
    var zoomRange by remember { mutableStateOf(1f to 1f) }
    DisposableEffect(session) {
        val live = session?.camera?.cameraInfo?.zoomState
        val obs = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { zoomRange = it.minZoomRatio to it.maxZoomRatio }
        live?.observe(owner, obs)
        onDispose { live?.removeObserver(obs) }
    }

    // focal-plane shutter: two blades close over the frame and open again
    val blades = remember { Animatable(0f) }
    fun shoot() {
        val s = session ?: return state.say(if (granted) "相机还没准备好" else "需要相机权限")
        if (busy) return
        busy = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            blades.animateTo(1f, tween(70, easing = FastOutLinearInEasing))
            blades.animateTo(0f, tween(260, easing = LinearOutSlowInEasing))
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SPK_$stamp." + if (s.raw) "dng" else "jpg")
            put(MediaStore.Images.Media.MIME_TYPE, if (s.raw) "image/x-adobe-dng" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/SpektraLab")
        }
        s.capture.flashMode = flash
        val options = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        s.capture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                busy = false
                result.savedUri?.let { state.onCaptured(it) } ?: state.say("原片没有保存下来")
            }

            override fun onError(e: ImageCaptureException) {
                busy = false
                state.say("拍摄失败：${e.message ?: e.imageCaptureError}")
            }
        })
    }

    // layout: the same four parts as the darkroom, so foldable postures work the same way
    val fold = LocalFold.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val mode = when {
        fold.posture == Posture.TABLETOP -> EditorMode.TABLETOP
        fold.posture == Posture.BOOK -> EditorMode.BOOK
        widthClass() != WidthClass.COMPACT -> EditorMode.SIDE
        else -> EditorMode.COMPACT
    }
    val items by state.library.items.collectAsState()
    val last = items.firstOrNull()

    EditorLayout(mode, fold.hinge?.translate(-origin), Modifier.fillMaxSize().background(Pal.dark).statusBarsPadding().navigationBarsPadding()
        .displayCutoutPadding().onGloballyPositioned { origin = it.positionInWindow() }) {
        // 0. top bar
        Row(Modifier.fillMaxWidth().dealIn(0, fromY = -40f).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 灯箱", color = Pal.text, fontSize = 15.sp, modifier = Modifier.springClick { state.closeCamera() }.padding(6.dp))
            Spacer(Modifier.width(8.dp))
            // what the shutter will keep as the original
            AnimatedContent(session?.raw, label = "format", transitionSpec = { fadeIn() togetherWith fadeOut() }) { raw ->
                Text(when (raw) { true -> "RAW · DNG"; false -> "JPEG"; null -> "…" }, fontFamily = Fonts.mono, fontSize = 10.sp,
                    color = if (raw == true) accent else Pal.dim,
                    modifier = Modifier.border(1.dp, if (raw == true) accent else Pal.off, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(Modifier.weight(1f))
            val flashLabel = when (flash) { ImageCapture.FLASH_MODE_ON -> "闪光 开"; ImageCapture.FLASH_MODE_AUTO -> "闪光 自动"; else -> "闪光 关" }
            Text(flashLabel, color = if (flash == ImageCapture.FLASH_MODE_OFF) Pal.dim else Pal.text, fontSize = 13.sp, modifier = Modifier.springClick {
                flash = when (flash) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            }.padding(8.dp))
            Text("翻转", color = Pal.text, fontSize = 13.sp, modifier = Modifier.springClick { front = !front; zoom = 1f }.padding(8.dp))
            Text("配方", color = Pal.text, fontSize = 15.sp, modifier = Modifier.springClick { state.openRecipes() }.padding(8.dp))
        }
        // 1. viewfinder
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val aspect = if (landscape) 4f / 3f else 3f / 4f
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val boxAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
                var focusAt by remember { mutableStateOf<Offset?>(null) }
                val focusRing = remember { Animatable(0f) }
                Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < boxAspect)
                    .pointerInput(session) {
                        detectTapGestures { p ->
                            val s = session ?: return@detectTapGestures
                            val point = previewView.meteringPointFactory.createPoint(p.x, p.y)
                            runCatching { s.camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build()) }
                            focusAt = p
                            scope.launch { focusRing.snapTo(1f); focusRing.animateTo(0f, tween(900)) }
                        }
                    }) {
                    if (granted) AndroidView({ previewView }, Modifier.fillMaxSize())
                    else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("需要相机权限才能取景", color = Pal.dim, fontSize = 14.sp)
                        Text("授权", color = Pal.dink, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)
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
                        // shutter blades
                        if (blades.value > 0f) {
                            val hgt = size.height / 2 * blades.value
                            drawRect(Color.Black, Offset.Zero, Size(size.width, hgt))
                            drawRect(Color.Black, Offset(0f, size.height - hgt), Size(size.width, hgt))
                        }
                        // focus ring closing in on the tapped point
                        focusAt?.let { c ->
                            if (focusRing.value > 0f) drawCircle(Pal.safelight.copy(alpha = focusRing.value),
                                radius = (22.dp.toPx()) * (1f + 0.6f * focusRing.value * focusRing.value), center = c,
                                style = Stroke(1.5.dp.toPx()))
                        }
                    }
                    CropCorners(Modifier.fillMaxSize(), busy)
                    Text(summary(look).uppercase(Locale.ROOT), fontFamily = Fonts.mono, fontSize = 9.sp, color = Pal.text,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(Color(0x99140807)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Safelight(Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        // 2. shutter row
        Row(Modifier.fillMaxWidth().dealIn(1, fromY = 0f).padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // last frame on the lightbox: tap to open it in the darkroom
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(Pal.line)
                .semantics { contentDescription = "上一张" }
                .springClick(0.9f) { last?.let { state.openEdit(it.id) } ?: state.say("还没有拍过") }) {
                if (last != null) {
                    val thumb by produceState(state.app.thumbs.cached(last), last.id, last.look) { value = state.app.thumbs.get(last) }
                    thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            }
            Spacer(Modifier.weight(1f))
            // shutter release: the inner disc sinks while the exposure is being saved
            val sink by animateFloatAsState(if (busy) 0.72f else 1f, Motion.press, label = "shutter")
            Box(Modifier.size(72.dp).semantics { contentDescription = "快门" }.springClick(0.9f) { shoot() }
                .border(3.dp, Pal.text, CircleShape).padding(7.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().graphicsLayer { scaleX = sink; scaleY = sink }.clip(CircleShape).background(if (busy) accent else Pal.text))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                val stops = buildList {
                    val (min, max) = zoomRange
                    if (min < 0.95f) add(min)
                    add(1f)
                    if (max >= 2f) add(2f)
                    if (max >= 5f) add(5f)
                }
                Row {
                    stops.forEach { z ->
                        val on = kotlin.math.abs(zoom - z) < 0.05f
                        Text(if (z < 1f) "%.1f".format(z) else "${z.toInt()}×", fontFamily = Fonts.mono, fontSize = 11.sp,
                            color = if (on) Pal.dink else Pal.text, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(start = 6.dp).springClick {
                                zoom = z
                                session?.camera?.cameraControl?.setZoomRatio(z)
                            }.clip(CircleShape).background(if (on) accent else Color.Transparent)
                                .border(1.dp, if (on) accent else Pal.off, CircleShape).padding(horizontal = 8.dp, vertical = 6.dp))
                    }
                }
            }
        }
        // 3. the same tools as the darkroom, bound to the look loaded in the camera
        ToolDeck(state, tool, { tool = it }, look, accent, state.app.engine.film(LookMapper.filmStock(look)).isPositive, isVideo = false,
            onSet = { _, l -> state.setCamLook(l) }, onApply = { state.setCamLook(it) })
    }
}
