package app.spektralab.ui

import android.content.res.AssetManager
import android.graphics.Picture
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.engine.Catalog
import com.caverock.androidsvg.SVG

/** Only local vector drawing commands: no source photos, LUTs or GPU preview jobs. */
internal object FilmPackagePictures {
    private val pictures = mutableMapOf<String, Picture>()

    @Synchronized
    fun get(assets: AssetManager, stock: String): Picture = pictures.getOrPut(stock) {
        assets.open("film-packages/$stock.svg").use { SVG.getFromInputStream(it).renderToPicture() }
    }
}

@Composable
internal fun FilmPackageIcon(stock: String, modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    val picture = remember(assets, stock) { FilmPackagePictures.get(assets, stock) }
    Canvas(modifier.aspectRatio(160f / 168f)) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val save = native.save()
            native.scale(size.width / picture.width, size.height / picture.height)
            native.drawPicture(picture)
            native.restoreToCount(save)
        }
    }
}

@Composable
fun FilmPackageRow(selected: String, onPick: (String) -> Unit) {
    val initial = remember { (Catalog.films.indexOfFirst { it.stock == selected } - 1).coerceAtLeast(0) }
    LazyRow(
        state = rememberLazyListState(initial),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(Catalog.films, key = { it.stock }) { film ->
            val on = film.stock == selected
            val accent = Color(film.accent)
            val ring by animateColorAsState(if (on) accent else Pal.off.copy(alpha = 0.4f), tween(180), label = "packageRing")
            val lift by animateFloatAsState(if (on) 1f else 0f, Motion.settle, label = "packageLift")
            val shape = RoundedCornerShape(8.dp)
            Column(
                Modifier.width(112.dp)
                    .graphicsLayer { translationY = -3.dp.toPx() * lift }
                    .springClick(0.95f) { onPick(film.stock) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${film.name}，ISO ${film.iso}，${film.kind}"
                        this.selected = on
                        role = Role.RadioButton
                    }
            ) {
                Box(Modifier.fillMaxWidth().background(if (on) accent.copy(alpha = 0.08f) else Color.Transparent, shape)
                    .border(if (on) 1.5.dp else 1.dp, ring, shape).padding(3.dp), contentAlignment = Alignment.Center) {
                    FilmPackageIcon(film.stock, Modifier.height(88.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(film.name, color = if (on) accent else Pal.text, fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
            }
        }
    }
}
