package app.spektralab.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spektralab.R

/** Optional coffee prompt. Nothing here blocks the darkroom; both codes sit side by side. */
@Composable
fun CoffeePromptDialog(onLater: () -> Unit, onDismissForever: () -> Unit) {
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }
    fun openPay(uri: String) {
        openFailed = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        }.isFailure
    }
    Box(
        Modifier.fillMaxSize().background(Color(0x88000000))
            .statusBarsPadding().navigationBarsPadding()
            .clickable(onClick = onLater).testTag("coffee_scrim"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .background(Pal.paper)
                .border(1.dp, Pal.ink, RoundedCornerShape(18.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp)
                .semantics { semanticsTestTag = "coffee_prompt" },
        ) {
            Text(
                stringResource(R.string.coffee_title), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = Pal.ink, fontFamily = Fonts.hand, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.coffee_body), fontSize = 14.sp, color = Pal.ink, lineHeight = 20.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PayCode(R.drawable.pay_wechat, R.string.coffee_wechat, "coffee_wechat", { openPay(WECHAT_PAY) }, Modifier.weight(1f))
                PayCode(R.drawable.pay_alipay, R.string.coffee_alipay, "coffee_alipay", { openPay(ALIPAY_PAY) }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(if (openFailed) R.string.coffee_open_failed else R.string.coffee_open_hint),
                fontSize = 12.sp,
                color = if (openFailed) Pal.red else Pal.grey,
                lineHeight = 17.sp,
                modifier = Modifier.testTag("coffee_open_status"),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.coffee_open),
                color = Pal.paper,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Pal.ink)
                    .clickable { openPay(ALIPAY_PAY) }
                    .padding(vertical = 12.dp).testTag("coffee_open"),
            )
            Text(
                stringResource(R.string.coffee_later),
                color = Pal.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Pal.ink, RoundedCornerShape(8.dp))
                    .clickable(onClick = onLater).padding(vertical = 12.dp).testTag("coffee_later"),
            )
            Text(
                stringResource(R.string.coffee_dismiss),
                color = Pal.grey,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDismissForever).padding(vertical = 10.dp)
                    .testTag("coffee_dismiss"),
            )
        }
    }
}

@Composable
private fun PayCode(image: Int, label: Int, tag: String, onOpen: () -> Unit, modifier: Modifier) {
    val name = stringResource(label)
    Column(
        modifier.testTag(tag).clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(image),
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White),
        )
        Spacer(Modifier.height(6.dp))
        Text(name, fontSize = 12.sp, color = Pal.grey)
    }
}

private const val WECHAT_PAY = "wxp://f2f1fet-2WiM3dHXoNfvUwH8Rc48iKwE0_bB9vt9hGmRH4hRXFjEM48TYNnzi0D50Tck"
private const val ALIPAY_PAY = "https://qr.alipay.com/fkx16502aqncntlqz5etm0c"
