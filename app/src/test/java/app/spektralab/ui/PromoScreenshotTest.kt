package app.spektralab.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import app.spektralab.MainActivity
import app.spektralab.SpektraApp
import app.spektralab.data.LibraryItem
import app.spektralab.data.LookJson
import app.spektralab.engine.Look
import app.spektralab.export.ExportOptions
import app.spektralab.export.PrintSignature
import app.spektralab.render.FilmPreviewView
import app.spektralab.render.Framing
import app.spektralab.render.OverlayPainter
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screens of the real app for promo material, rendered off-device: the Compose UI is the
 * app's own (MainActivity under Robolectric native graphics). GL and camera surfaces do not
 * draw there, so the preview / viewfinder area shows a picture rendered beforehand by the
 * app's film shaders (tools/promo/render.js), set as the background of the view that hosts
 * the surface; library thumbnails are the same renders placed in the app's thumbnail cache.
 *
 * Runs only with -Dpromo.dir=<dir> holding jobs.tsv and png/<job>.png; writes <dir>/shots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PromoScreenshotTest.PHONE, sdk = [34])
class PromoScreenshotTest {
    companion object {
        const val PHONE = "w412dp-h915dp-420dpi"
        const val UNFOLDED = "w841dp-h701dp-420dpi"
        const val DAY = 86_400_000L
    }

    @get:Rule val rule = createEmptyComposeRule()
    @get:Rule val name = org.junit.rules.TestName()
    private val dir = File(System.getProperty("promo.dir") ?: "")
    private val png get() = File(dir, "png")
    private val out get() = File(dir, "shots").apply { mkdirs() }
    private val log = ArrayList<String>()
    private lateinit var app: SpektraApp
    private lateinit var state: AppState
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun gate() { assumeTrue(System.getProperty("promo.dir").orEmpty().isNotEmpty()) }

    // ------------------------------------------------------------------ looks and items
    private val specs: Map<String, String> by lazy {
        File(dir, "jobs.tsv").readLines().filter { it.isNotBlank() }.associate { it.split("\t").let { p -> p[0] to p[2] } }
    }

    private fun look(job: String): Look {
        var l = Look()
        for (kv in specs.getValue(job).split(";").filter { it.isNotBlank() }) {
            val (k, v) = kv.split("=")
            l = when (k) {
                "film" -> l.copy(film = v); "paper" -> l.copy(paper = v)
                "printEv" -> l.copy(printEv = v.toDouble()); "glare" -> l.copy(glare = v.toDouble())
                "filmEv" -> l.copy(filmEv = v.toDouble()); "push" -> l.copy(push = v.toInt())
                "halation" -> l.copy(halation = v.toDouble()); "halationSize" -> l.copy(halationSize = v.toDouble())
                "grain" -> l.copy(grain = v.toDouble()); "grainSize" -> l.copy(grainSize = v.toDouble())
                "diffusion" -> l.copy(diffusion = v); "diffusionAmount" -> l.copy(diffusionAmount = v.toDouble())
                "yFilter" -> l.copy(yFilter = v.toDouble()); "mFilter" -> l.copy(mFilter = v.toDouble())
                "format" -> l.copy(format = v); "border" -> l.copy(border = v); "dateStamp" -> l.copy(dateStamp = v.toBoolean())
                else -> error(k)
            }
        }
        return l
    }

    private class Seed(val id: Long, val photo: String, val job: String, val daysAgo: Int, val name: String, val raw: Boolean = false,
                       val printed: Boolean = false, val portrait: Boolean = false)

    // newest first; ids chosen so frame numbers (id % 36 + 1) read like a roll
    private val seeds = listOf(
        Seed(14, "scooter", "scooter__portra400__filmedge_date", 0, "IMG_20261116_0914.dng", raw = true, printed = true),
        Seed(13, "bulbs", "bulbs__halation_on__portra800", 0, "IMG_20261116_0851.dng", raw = true, printed = true),
        Seed(12, "cafe", "cafe__c200", 0, "IMG_20261116_0832.dng", raw = true, printed = true),
        Seed(11, "portrait", "portrait__portra400", 1, "DSC_4127.jpg", portrait = true),
        Seed(10, "canyon", "canyon__velvia100", 1, "DSC_4098.jpg", printed = true),
        Seed(9, "valley", "valley__gold200", 1, "DSC_4071.jpg"),
        Seed(8, "bridge", "bridge__vision500t_2383", 3, "IMG_20261113_2140.dng", raw = true, printed = true),
        Seed(7, "lantern", "lantern__vision500t_2383", 3, "IMG_20261113_2102.dng", raw = true),
        Seed(6, "pier", "pier__ektar100", 3, "DSC_3990.jpg", printed = true),
        Seed(5, "beach", "beach__pro400h", 9, "DSC_3712.jpg"),
        Seed(4, "hikers", "hikers__kodachrome64", 9, "DSC_3688.jpg", printed = true),
        Seed(3, "sign", "sign__gold200", 9, "DSC_3641.jpg"),
        Seed(2, "mandarins", "mandarins__ektar100", 12, "DSC_3502.jpg"),
        Seed(1, "waterfall", "waterfall__pro400h", 12, "DSC_3477.jpg", printed = true),
    )
    private val now = System.currentTimeMillis()
    private fun seed(photo: String) = seeds.first { it.photo == photo }
    private fun date(s: Seed) = now - s.daysAgo * DAY - (15 - s.id) * 600_000L

    private fun item(s: Seed): LibraryItem {
        val src = File(dir, "src/${s.photo}.jpg")
        val (w, h) = if (s.portrait) 1600 to 2400 else 2400 to 1600
        var it = LibraryItem(s.id, android.net.Uri.fromFile(src).toString(), false, s.raw, w, h, 0, 0, date(s), 0f, look(s.job), s.name)
        if (s.printed) it = it.copy(printUri = "content://media/external/images/media/${9000 + s.id}", printSig = PrintSignature.of(it, ExportOptions()))
        return it
    }

    private fun bitmap(job: String): Bitmap = BitmapFactory.decodeFile(File(png, "$job.png").path) ?: error("no render $job")

    /** The render with the app's film-edge print / date back painted on, as the preview and exports do. */
    private fun withOverlay(job: String, frame: Int, date: Long): Bitmap {
        val l = look(job)
        val b = bitmap(job).copy(Bitmap.Config.ARGB_8888, true)
        if (!OverlayPainter.needsOverlay(l)) return b
        val portrait = b.height > b.width
        val f = Framing.compute(if (portrait) 1600 else 2400, if (portrait) 2400 else 1600, l, 2400)
        check(f.outWidth == b.width && f.outHeight == b.height) { "$job: ${f.outWidth}x${f.outHeight} vs ${b.width}x${b.height}" }
        OverlayPainter.draw(Canvas(b), f, l, frame, date)
        return b
    }

    // ------------------------------------------------------------------ driving the app
    private fun launch(prepare: () -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        app = RuntimeEnvironment.getApplication() as SpektraApp
        shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA, android.Manifest.permission.POST_NOTIFICATIONS)
        val items = seeds.map { item(it) }
        // thumbnails: the film render of each frame, where the app caches its own
        val thumbs = File(app.cacheDir, "thumbs").apply { mkdirs() }
        for ((s, it) in seeds.zip(items)) {
            val key = "${it.id}_${Integer.toHexString(it.uri.hashCode())}_${Integer.toHexString(LookJson.toJson(it.look).toString().hashCode())}"
            val l = it.look.copy(border = "none", dateStamp = false)
            val plain = specs.entries.firstOrNull { e -> e.key.startsWith(s.photo + "__") && look(e.key) == l && File(png, "${e.key}.png").exists() }?.key ?: s.job
            val full = bitmap(plain)
            val sc = 480f / maxOf(full.width, full.height)
            val small = Bitmap.createScaledBitmap(full, (full.width * sc).toInt(), (full.height * sc).toInt(), true)
            File(thumbs, "$key.jpg").outputStream().use { o -> small.compress(Bitmap.CompressFormat.JPEG, 92, o) }
        }
        app.library.add(items)
        prepare()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
        run(3000)
    }

    /** Let animations and background loads play for [ms] of animation time. */
    private fun run(ms: Int) {
        var left = ms
        while (left > 0) {
            Thread.sleep(12)
            rule.mainClock.advanceTimeBy(50)
            left -= 50
        }
    }

    private fun ui(block: () -> Unit) { scenario.onActivity { block() }; run(100) }

    private fun <T : View> find(root: View, cls: Class<T>): T? {
        if (cls.isInstance(root)) return cls.cast(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), cls)?.let { return it }
        return null
    }

    /**
     * Show [picture] where the GL / camera surface of class [cls] would draw: as the
     * background of the view hosting it. Returns the surface's rect in the window.
     */
    private fun <T : View> fillSurface(cls: Class<T>, picture: Bitmap, onView: (T) -> Unit = {}): Rect {
        var rect = Rect()
        scenario.onActivity { a ->
            val v = find(a.window.decorView, cls) ?: error("no ${cls.simpleName}")
            val host = if (v is androidx.camera.view.PreviewView) v else v.parent as View
            val loc = IntArray(2); v.getLocationInWindow(loc)
            rect = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            host.background = BitmapDrawable(a.resources, Bitmap.createScaledBitmap(picture, v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), true))
            onView(v)
        }
        return rect
    }

    private var lastFill: Pair<Rect, Bitmap>? = null

    private fun shot(file: String, screen: String, what: String, job: String? = null, rect: Rect? = null): Bitmap {
        var bmp: Bitmap? = null
        scenario.onActivity { a ->
            val view = a.window.decorView
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            // a surface that punched a hole in the software draw: the picture goes underneath
            lastFill?.let { (r, pic) ->
                val under = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER) }
                Canvas(b).drawBitmap(pic, null, r, under)
            }
            bmp = b
        }
        File(out, "$file.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val r = rect?.let { "[${it.left},${it.top},${it.width()},${it.height()}]" } ?: "null"
        log += """{"file":"$file.png","screen":"$screen","shows":"$what","width":${bmp!!.width},"height":${bmp!!.height},"surfaceRect":$r,"render":${job?.let { "\"$it\"" } ?: "null"}}"""
        File(out, "index_${name.methodName}.json").writeText(log.joinToString(",\n", "[", "]"))
        return bmp!!
    }

    private fun tap(text: String) { rule.onAllNodesWithText(text).onFirst().performClick(); run(900) }

    /** Open [photo] in the darkroom with the look of [job] and its render in the preview. */
    private fun edit(photo: String, job: String): Rect {
        val s = seed(photo)
        lastFill = null
        ui { state.back() }
        run(1200)
        app.library.setLook(s.id, look(job))
        ui { state.openEdit(s.id) }
        run(2500)
        val pic = withOverlay(job, (s.id % 36 + 1).toInt(), date(s))
        val rect = fillSurface(FilmPreviewView::class.java, pic) { it.onFirstFrame?.invoke() }
        lastFill = rect to pic
        run(1200)
        // the layout may have changed with the frame's aspect
        return fillSurface(FilmPreviewView::class.java, pic).also { lastFill = it to pic; run(200) }
    }

    // ------------------------------------------------------------------ shots
    @Test fun library() {
        launch()
        run(6000)   // thumbnails develop from the paper back one after another
        shot("library", "library", "lightbox with 14 frames in 5 day groups; thumbnails are film renders from the app's thumbnail cache")
        ui { state.toggleSelecting() }
        run(600)
        for (p in listOf("bulbs", "portrait", "canyon", "bridge")) { ui { state.togglePick(seed(p).id) }; run(500) }
        run(1500)
        shot("library_select", "library", "selection mode: 4 frames circled with the crayon, handwritten order numbers")
        ui { state.openExport(state.picked.value) }
        run(1500)
        shot("library_export_sheet", "library", "export sheet for the 4 circled frames")
        ui { state.sheet.value = Sheet.NONE }
        run(800)
        ui { state.openRecipes() }
        run(1500)
        shot("library_recipes_sheet", "library", "recipe cards for the circled frames")
    }

    private val tabs = mapOf("film" to "胶片", "paper" to "相纸", "exp" to "曝光", "hal" to "光晕", "grain" to "颗粒",
        "diff" to "柔焦", "color" to "滤色", "fmt" to "画幅")

    private fun editorShot(name: String, photo: String, job: String, tool: String, what: String) {
        var rect = edit(photo, job)
        if (tool != "film") { tap(tabs.getValue(tool)); rect = fillSurface(FilmPreviewView::class.java, lastFill!!.second); lastFill = rect to lastFill!!.second }
        run(600)
        shot(name, "editor", what, job, rect)
    }

    @Test fun editor() {
        launch()
        editorShot("editor_film_portra400", "portrait", "portrait__portra400", "film", "darkroom, film tab, Portra 400 selected")
        editorShot("editor_film_gold200", "valley", "valley__gold200", "film", "darkroom, film tab, Gold 200 (accent follows the roll)")
        editorShot("editor_film_velvia100", "canyon", "canyon__velvia100", "film", "darkroom, film tab, Velvia 100 slide film")
        editorShot("editor_film_vision500t", "bridge", "bridge__vision500t_2383", "film", "darkroom, film tab, Vision3 500T")
        editorShot("editor_paper_2383", "bridge", "bridge__vision500t_2383", "paper", "paper tab, 2383 print film selected")
        editorShot("editor_exposure_plus2", "portrait", "portrait__portra400__filmEv+2", "exp", "exposure tab, film exposure +2 EV")
        editorShot("editor_halation", "bulbs", "bulbs__halation_on__portra800", "hal", "halation tab, strength x3.0, size x1.6, Portra 800")
        editorShot("editor_grain", "portrait", "portrait__grain_on__portra800_crop", "grain", "grain tab, strength x1.6, size x1.4, half-frame format")
        editorShot("editor_diffusion_cinebloom", "bulbs", "bulbs__cinebloom__portra400", "diff", "diffusion tab, CineBloom 70%")
        editorShot("editor_filter_yellow", "canyon", "canyon__portra400__y+20", "color", "enlarger filter tab, yellow +20 CC")
        editorShot("editor_frame_filmedge_date", "scooter", "scooter__portra400__filmedge_date", "fmt", "format tab, film-edge border and date back")
        editorShot("editor_frame_xpan", "valley", "valley__ektar100__xpan", "fmt", "format tab, XPan format")
        editorShot("editor_frame_whiteborder", "cafe", "cafe__c200__whiteborder_date", "fmt", "format tab, paper white border and date back")
        // sheets over the darkroom
        val rect = edit("canyon", "canyon__velvia100")
        ui { state.openExport(listOf(seed("canyon").id)) }
        run(1500)
        shot("editor_export_sheet", "editor", "export sheet over the darkroom", "canyon__velvia100", rect)
        ui { state.sheet.value = Sheet.NONE }
        run(800)
        ui { state.openRecipes() }
        run(1500)
        shot("editor_recipes_sheet", "editor", "recipe cards over the darkroom", "canyon__velvia100", rect)
    }

    @Test @Config(qualifiers = UNFOLDED, sdk = [34]) fun unfolded() {
        launch()
        run(6000)
        shot("unfolded_library", "library", "lightbox on an unfolded inner screen / tablet (841x701 dp)")
        var rect = edit("valley", "valley__gold200")
        shot("unfolded_editor_film", "editor", "side-by-side darkroom on an unfolded screen, film tab", "valley__gold200", rect)
        rect = edit("bulbs", "bulbs__halation_on__portra800")
        tap("光晕"); rect = fillSurface(FilmPreviewView::class.java, lastFill!!.second); lastFill = rect to lastFill!!.second; run(400)
        shot("unfolded_editor_halation", "editor", "side-by-side darkroom on an unfolded screen, halation tab", "bulbs__halation_on__portra800", rect)
    }

    /** A back camera for CameraX to bind (Robolectric's camera service has none): 12 MP, raw capable. */
    private fun addCamera() {
        val chars = org.robolectric.shadows.ShadowCameraCharacteristics.newCameraCharacteristics()
        val sc = shadowOf(chars)
        fun <T> set(k: android.hardware.camera2.CameraCharacteristics.Key<T>, v: T) = sc.set(k, v)
        set(android.hardware.camera2.CameraCharacteristics.LENS_FACING, android.hardware.camera2.CameraMetadata.LENS_FACING_BACK)
        set(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION, 90)
        set(android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL, android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL)
        set(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, intArrayOf(
            android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
            android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))
        set(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, Rect(0, 0, 4000, 3000))
        set(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, android.util.Size(4000, 3000))
        set(android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 8f)
        set(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES, intArrayOf(0, 1, 2, 3))
        set(android.hardware.camera2.CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, intArrayOf(0, 1, 4))
        set(android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES, intArrayOf(0, 1))
        set(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        val sizes = listOf(android.util.Size(4000, 3000), android.util.Size(1920, 1440), android.util.Size(1440, 1080), android.util.Size(1280, 960), android.util.Size(640, 480))
        val map = org.robolectric.shadows.StreamConfigurationMapBuilder.newBuilder().apply {
            for (f in listOf(android.graphics.ImageFormat.YUV_420_888, android.graphics.ImageFormat.PRIVATE)) sizes.forEach { runCatching { addOutputSize(f, it) } }
            runCatching { addOutputSize(android.graphics.ImageFormat.RAW_SENSOR, android.util.Size(4000, 3000)) }
        }.build()
        set(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP, map)
        shadowOf(app.getSystemService(android.hardware.camera2.CameraManager::class.java)).addCamera("0", chars)
    }

    private fun camera(cocked: Boolean, lookJob: String, frame: Int) = launch {
        addCamera()
        app.getSharedPreferences("camera", android.content.Context.MODE_PRIVATE).edit()
            .putString("look", LookJson.toJson(look(lookJob)).toString()).putBoolean("cocked", cocked).putInt("frame", frame).commit()
    }

    /** No camera hardware here, so CameraX reports none; drop that hint (it would time out after 2.4 s). */
    private fun clearHint() = ui {
        val f = AppState::class.java.getDeclaredField("_hint").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") (f.get(state) as kotlinx.coroutines.flow.MutableStateFlow<String>).value = ""
    }

    private fun viewfinder(job: String): Rect {
        clearHint(); run(600)
        val pic = bitmap(job)
        val rect = fillSurface(androidx.camera.view.PreviewView::class.java, pic)
        lastFill = rect to pic
        run(300)
        return rect
    }

    @Test fun cameraReady() {
        camera(true, "vf_scooter__portra400", 14)
        ui { state.openCamera() }
        run(4000)
        var rect = viewfinder("vf_scooter__portra400")
        shot("camera_ready", "camera", "camera, frame advanced: live film viewfinder (Portra 400), 135 frame lines", "vf_scooter__portra400", rect)
        tap("调整")
        rect = viewfinder("vf_scooter__portra400")
        shot("camera_tools", "camera", "camera with the film settings opened over the viewfinder", "vf_scooter__portra400", rect)
    }

    /** The same scene as [cameraGroundGlass], before the shutter: the pair shows the film transport. */
    @Test fun cameraBeforeShot() {
        camera(true, "vf_cafe__c200", 22)
        ui { state.openCamera() }
        run(4000)
        val rect = viewfinder("vf_cafe__c200")
        shot("camera_c200_ready", "camera", "camera, live film viewfinder (C200), ready to shoot", "vf_cafe__c200", rect)
    }

    @Test fun cameraGroundGlass() {
        camera(false, "vf_cafe__c200", 23)
        ui { state.openCamera() }
        run(4000)
        val rect = viewfinder("vf_cafe__c200__groundglass")
        shot("camera_wind", "camera", "camera after a shot: ground-glass viewfinder, shutter locked until the film is advanced", "vf_cafe__c200__groundglass", rect)
    }

    @Test fun cameraNight() {
        camera(true, "vf_bulbs__vision500t_2383", 7)
        ui { state.openCamera() }
        run(4000)
        val rect = viewfinder("vf_bulbs__vision500t_2383")
        shot("camera_night", "camera", "camera, Vision3 500T x 2383 viewfinder", "vf_bulbs__vision500t_2383", rect)
    }

    /** The final prints with film-edge markings / date back painted by the app's OverlayPainter. */
    @Test fun overlays() {
        app = RuntimeEnvironment.getApplication() as SpektraApp
        val dst = File(dir, "png_overlay").apply { mkdirs() }
        val frames = mapOf("scooter__portra400__filmedge_date" to seed("scooter"), "cafe__c200__whiteborder_date" to seed("cafe"),
            "portrait__portra400__filmedge" to seed("portrait"))
        for ((job, s) in frames) {
            val b = withOverlay(job, (s.id % 36 + 1).toInt(), date(s))
            File(dst, "$job.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
