package com.parchi.billing

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Repo.init(this)
        val perms = buildList {
            add(Manifest.permission.READ_CALL_LOG)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Repo.startService(this) }
        ask.launch(perms.toTypedArray())
        Repo.startService(this)
        setContent { App() }
    }
}

/* ---------- look ---------- */

object C {
    val violet = Color(0xFF5B2BD6)
    val violetD = Color(0xFF4520A8)
    val mid = Color(0xFF9B33C9)
    val rose = Color(0xFFE2437A)
    val ink = Color(0xFF17142B)
    val muted = Color(0xFF6B6785)
    val line = Color(0xFFECE8F6)
    val bg = Color(0xFFFAF9FE)
    val soft = Color(0xFFF0EBFE)
    val ok = Color(0xFF11795A)
    val okSoft = Color(0xFFE6F5EF)
    val warn = Color(0xFFA85A07)
    val warnSoft = Color(0xFFFDF1E1)
    val red = Color(0xFFC8303F)
    val grad = Brush.linearGradient(listOf(violet, mid, rose))
}

/* ---------- navigation ---------- */

data class Route(val name: String, val id: Long = 0, val extra: String = "", val stamp: Long = System.nanoTime())

class Nav {
    val stack = mutableStateListOf(Route("home"))
    val top: Route get() = stack.last()
    fun go(r: Route) { stack.add(r) }
    fun back(): Boolean { if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }; return false }
    fun tab(name: String) { stack.clear(); stack.add(Route("home")); if (name != "home") stack.add(Route(name)) }
}

@Composable
fun App() {
    val nav = remember { Nav() }
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    val queue by Repo.queue.collectAsState()
    val pstate by Repo.printerState.collectAsState()
    val r = nav.top
    Column(Modifier.fillMaxSize().background(C.bg).systemBarsPadding().imePadding()) {
        if (queue > 0) {
            Row(Modifier.fillMaxWidth().background(C.warnSoft).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(C.warn))
                Spacer(Modifier.width(8.dp))
                T(if (pstate.isNotBlank() && pstate != "Printer not in range") pstate else "Searching for printer…", 13, FontWeight.SemiBold, C.warn, Modifier.weight(1f), maxLines = 1)
                T("$queue waiting", 13, FontWeight.SemiBold, C.warn)
            }
        }
        Box(Modifier.weight(1f)) {
            key(r.stamp) {
                when (r.name) {
                    "home" -> Home(nav)
                    "newbill" -> NewBill(nav, r.id)
                    "todaybills" -> TodayBills(nav)
                    "bill" -> BillView(nav, r.id)
                    "orders" -> AutoBills(nav)
                    "approve" -> Approve(nav, r.id)
                    "parties" -> Parties(nav)
                    "partydetail" -> PartyDetail(nav, r.id)
                    "partyedit" -> PartyEdit(nav, r.id, r.extra)
                    "pending" -> Pending(nav)
                    "pendparty" -> PendingParty(nav, r.id)
                    "products" -> Products(nav)
                    "prodedit" -> ProductEdit(nav, r.id)
                    "settings" -> Settings(nav)
                    "printcfg" -> PrintCfg(nav)
                    "log" -> LogScreen(nav)
                    else -> Home(nav)
                }
            }
        }
        if (r.name !in setOf("newbill", "approve", "partyedit", "prodedit")) BottomBar(nav)
    }
}

@Composable
private fun BottomBar(nav: Nav) {
    val cur = nav.stack.getOrNull(1)?.name ?: "home"
    val tabs = listOf(
        Triple("home", "Home", Icons.Filled.Home), Triple("orders", "Auto bills", Icons.AutoMirrored.Filled.List),
        Triple("newbill", "New bill", Icons.Filled.Add), Triple("parties", "Party", Icons.Filled.Person),
        Triple("settings", "More", Icons.Filled.Settings)
    )
    Column {
        Hr()
        Row(Modifier.fillMaxWidth().background(Color.White).padding(vertical = 6.dp)) {
            tabs.forEach { (k, label, icon) ->
                val on = cur == k
                Column(
                    Modifier.weight(1f).clickable { nav.tab(k) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(icon, label, tint = if (on) C.violet else C.muted)
                    T(label, 11, if (on) FontWeight.Bold else FontWeight.Medium, if (on) C.violet else C.muted)
                }
            }
        }
    }
}

/* ---------- building blocks ---------- */

fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()

fun pickDate(c: Context, iso: String, onPick: (String) -> Unit) {
    val d = try { LocalDate.parse(iso) } catch (e: Exception) { LocalDate.now() }
    DatePickerDialog(c, { _, y, m, day -> onPick(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
}

fun cleanNum(v: String): String {
    val f = v.filter { it.isDigit() || it == '.' }
    val i = f.indexOf('.')
    return if (i < 0) f else f.substring(0, i + 1) + f.substring(i + 1).replace(".", "")
}

@Composable
fun T(
    text: String, size: Int = 15, weight: FontWeight = FontWeight.Normal, color: Color = C.ink,
    modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null
) = Text(text, modifier, color = color, fontSize = size.sp, fontWeight = weight, maxLines = maxLines,
    overflow = TextOverflow.Ellipsis, textAlign = align)

@Composable
fun Eyebrow(text: String, color: Color = C.violet) =
    Text(text.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)

@Composable
fun Hr() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))

@Composable
fun Header(title: String, sub: String = "", onBack: (() -> Unit)? = null, right: @Composable () -> Unit = {}) {
    Column {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(C.soft).clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.violetD)
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                T(title, 19, FontWeight.Bold, maxLines = 1)
                if (sub.isNotEmpty()) T(sub, 13, color = C.muted, maxLines = 1)
            }
            right()
        }
        Hr()
    }
}

@Composable
fun CardBox(modifier: Modifier = Modifier, gap: Dp = 10.dp, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(gap), content = content)

@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: String = "main", small: Boolean = false) {
    val shape = RoundedCornerShape(if (small) 11.dp else 14.dp)
    val base = modifier.heightIn(min = if (small) 38.dp else 46.dp).clip(shape)
    val m = when (kind) {
        "main" -> base.background(C.grad)
        "dark" -> base.background(C.ink)
        else -> base.background(Color.White).border(1.dp, C.line, shape)
    }
    Box(m.clickable { onClick() }.padding(horizontal = if (small) 11.dp else 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = when (kind) { "main", "dark" -> Color.White; "danger" -> C.red; else -> C.ink },
            fontWeight = FontWeight.Bold, fontSize = if (small) 14.sp else 15.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun Pill(text: String, kind: String = "off") {
    val (bg, fg) = when (kind) {
        "done" -> C.okSoft to C.ok
        "wait" -> C.warnSoft to C.warn
        "violet" -> C.soft to C.violetD
        else -> C.line to C.muted
    }
    Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
fun Av(name: String, size: Int = 36) {
    val ini = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase().ifBlank { "?" }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(50)).background(C.soft), contentAlignment = Alignment.Center) {
        T(ini, 13, FontWeight.Bold, C.violetD)
    }
}

/** Filter chips. Each item is key, label, count (count -1 hides it). */
@Composable
fun Chips(items: List<Triple<String, String, Int>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEach { (k, label, n) ->
            val on = k == selected
            val text = buildAnnotatedString {
                append(label)
                if (n >= 0) withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)) { append(" ($n)") }
            }
            Text(text, color = if (on) Color.White else C.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) C.ink else Color.White)
                    .border(1.dp, if (on) C.ink else C.line, RoundedCornerShape(50)).clickable { onSelect(k) }
                    .padding(horizontal = 13.dp, vertical = 7.dp))
        }
    }
}

@Composable
fun Seg(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, dark: Boolean = false) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(C.soft).padding(3.dp)) {
        options.forEach { (k, label) ->
            val on = k == selected
            val m = Modifier.clip(RoundedCornerShape(50)).let { if (on) (if (dark) it.background(C.ink) else it.background(C.grad)) else it }
            Box(m.clickable { onSelect(k) }.padding(horizontal = 13.dp, vertical = 6.dp)) {
                Text(label, color = if (on) Color.White else C.violetD, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onChange(it) }, colors = CheckboxDefaults.colors(checkedColor = C.violet))
        T(text, 15, FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String = "", hint: String = "",
    number: Boolean = false, password: Boolean = false, small: Boolean = false, end: Boolean = false,
    error: Boolean = false, fieldModifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(if (small) 10.dp else 12.dp)
    Column(modifier) {
        if (label.isNotEmpty()) T(label, 13, FontWeight.SemiBold, C.muted, Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = { onChange(if (number) cleanNum(it) else it) },
            singleLine = true,
            textStyle = TextStyle(fontSize = if (small) 15.sp else 16.sp, color = C.ink, fontWeight = FontWeight.SemiBold,
                textAlign = if (end) TextAlign.End else TextAlign.Start),
            keyboardOptions = KeyboardOptions(keyboardType = when { number -> KeyboardType.Decimal; password -> KeyboardType.Password; else -> KeyboardType.Text }),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = fieldModifier.fillMaxWidth().height(if (small) 34.dp else 46.dp).clip(shape)
                .background(if (small) C.bg else Color.White).border(1.dp, if (error) C.rose else C.line, shape),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().padding(horizontal = if (small) 7.dp else 12.dp),
                    contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, color = C.muted.copy(alpha = 0.7f), fontSize = if (small) 14.sp else 15.sp, maxLines = 1)
                    inner()
                }
            }
        )
    }
}

@Composable
fun Acc(title: String, summary: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White)) {
        Row(Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 15.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(title, 16, FontWeight.Bold)
                T(summary, 13, color = C.muted, maxLines = 2)
            }
            T(if (open) "Close ▴" else "Open ▾", 14, FontWeight.Bold, C.violet)
        }
        if (open) {
            Hr()
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp), content = content)
        }
    }
}

@Composable
fun DeliverBtn(b: Bill, modifier: Modifier = Modifier, big: Boolean = false, onClick: () -> Unit) {
    if (b.isCancelled()) return
    val shape = RoundedCornerShape(if (big) 12.dp else 50.dp)
    val (bg, fg) = if (b.delivered) C.okSoft to C.ok else C.warnSoft to C.warn
    Box(modifier.heightIn(min = if (big) 46.dp else 30.dp).clip(shape).background(bg).border(1.dp, fg, shape).clickable { onClick() }
        .padding(horizontal = if (big) 6.dp else 11.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
        Text(if (b.delivered) "✓ Delivered" else "Mark delivered", color = fg, fontSize = if (big) 14.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun PrintTag(b: Bill) = when {
    b.isCancelled() -> Pill("Cancelled")
    b.printPending() -> Pill("Print pending", "wait")
    b.printed -> Pill("Printed", "done")
    else -> Pill("Not printed")
}

@Composable
fun Slip(lines: List<PLine>) {
    val two = Repo.prefs.s("size", "3") == "2"
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.line).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.width(if (two) 230.dp else 316.dp).background(Color.White).padding(10.dp)) {
            lines.forEach { l ->
                Text(l.text, fontFamily = FontFamily.Monospace, fontSize = ((if (two) 11.0 else 10.0) * (if (l.big) 1.3 else 1.0)).sp,
                    fontWeight = if (l.bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false,
                    textAlign = if (l.center) TextAlign.Center else TextAlign.Start, color = C.ink, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun PrefField(label: String, key: String, hint: String = "", password: Boolean = false, def: String = "") {
    var v by remember { mutableStateOf(Repo.prefs.s(key, def)) }
    Field(v, { v = it; Repo.prefs.put(key, it) }, label = label, hint = hint, password = password)
}

@Composable
fun PrefCheck(text: String, key: String, def: Boolean) {
    var v by remember { mutableStateOf(Repo.prefs.b(key, def)) }
    CheckRow(text, v) { v = it; Repo.prefs.put(key, it) }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun ImageVectorIcon(v: ImageVector, tint: Color = C.muted) = Icon(v, null, tint = tint)
