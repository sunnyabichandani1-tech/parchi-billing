package com.parchi.billing

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/* ---------- products ---------- */

@Composable
fun Products(nav: Nav) {
    val products by Repo.dao.productsFlow().collectAsState(initial = emptyList())
    var q by remember { mutableStateOf("") }
    val s = q.trim().lowercase()
    val list = products.filter { p -> s.isEmpty() || p.name.lowercase().contains(s) || p.aliasList().any { it.contains(s) } }
    Column(Modifier.fillMaxSize()) {
        Header("Products", "${products.size} items · ${products.count { it.stock <= it.low }} running low", { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(q, { q = it }, hint = "Search product or a word customers use")
            Btn("+ Add product", { nav.go(Route("prodedit")) }, Modifier.fillMaxWidth())
            if (list.isEmpty()) T(if (products.isEmpty()) "No product yet. Add the products you sell, with the words customers use for them." else "No product matches.",
                14, color = C.muted, modifier = Modifier.fillMaxWidth().padding(16.dp), align = TextAlign.Center)
            else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                list.forEachIndexed { i, p ->
                    if (i > 0) Hr()
                    val low = p.stock <= p.low
                    Row(Modifier.fillMaxWidth().clickable { nav.go(Route("prodedit", p.id)) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T(p.name, 15, FontWeight.Bold)
                            T("Sale ${money(p.sale)} / ${p.unit}" + (if (p.purchase > 0) " · buy ${money(p.purchase)}" else "") + (if (p.tax > 0) " · GST ${p.tax}%" else ""), 12, color = C.muted)
                            if (p.aliasList().isNotEmpty()) T("AI words: " + p.aliasList().joinToString(", "), 12, color = C.violet, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            T("${qtyStr(p.stock)} ${unitShort(p.unit)}", 15, FontWeight.Bold, if (low) C.red else C.ink)
                            T(if (low) "low" else "in stock", 12, color = if (low) C.red else C.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnitPicker(value: String, onPick: (String) -> Unit, withNone: Boolean = false) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (if (withNone) listOf("") + UNITS else UNITS).forEach { u ->
            val on = u == value
            Text(if (u.isEmpty()) "none" else u, color = if (on) Color.White else C.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) C.ink else Color.White)
                    .border(1.dp, if (on) C.ink else C.line, RoundedCornerShape(50)).clickable { onPick(u) }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

@Composable
fun ProductEdit(nav: Nav, id: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("kg") }
    var unit2 by remember { mutableStateOf("") }
    var conv by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }
    var sale by remember { mutableStateOf("") }
    var purchase by remember { mutableStateOf("") }
    var tax by remember { mutableStateOf(0) }
    var stock by remember { mutableStateOf("") }
    var low by remember { mutableStateOf("") }
    val words = remember { mutableStateListOf<String>() }
    var newWord by remember { mutableStateOf("") }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id > 0) {
            val p = Repo.dao.product(id) ?: return@LaunchedEffect
            name = p.name; unit = p.unit; unit2 = p.unit2; conv = if (p.conv > 0) qtyStr(p.conv) else ""; cat = p.category
            sale = qtyStr(p.sale); purchase = if (p.purchase > 0) qtyStr(p.purchase) else ""; tax = p.tax
            stock = qtyStr(p.stock); low = qtyStr(p.low); words.clear(); words.addAll(p.aliasList())
        }
    }
    fun save() {
        val nm = name.trim()
        if (nm.isEmpty()) { toast(ctx, "Product name is needed"); return }
        val sp = sale.toDoubleOrNull() ?: 0.0
        if (sp <= 0) { toast(ctx, "Sale price is needed"); return }
        val al = (if (words.isEmpty()) listOf(nm.lowercase()) else words.toList()).joinToString(",")
        scope.launch {
            val old = if (id > 0) Repo.dao.product(id) else null
            Repo.dao.saveProduct(Product(id = if (id > 0) id else 0, name = nm, unit = unit, unit2 = unit2, conv = conv.toDoubleOrNull() ?: 0.0,
                category = cat.trim(), sale = sp, purchase = purchase.toDoubleOrNull() ?: 0.0, tax = tax,
                stock = stock.toDoubleOrNull() ?: (old?.stock ?: 0.0), low = low.toDoubleOrNull() ?: 0.0, aliases = al))
            Repo.note("Product saved: $nm")
            toast(ctx, "Saved")
            nav.back()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header(if (id > 0) "Edit product" else "New product", "Name, price, unit and the words customers use", { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            CardBox(gap = 11.dp) {
                Eyebrow("Basics")
                Field(name, { name = it }, label = "Product name", hint = "Paneer")
                T("Sold by", 13, FontWeight.SemiBold, C.muted)
                UnitPicker(unit, { unit = it })
                T("Also sold in", 13, FontWeight.SemiBold, C.muted)
                UnitPicker(unit2, { unit2 = it; if (it.isEmpty()) conv = "" else if (conv.isEmpty() && (it == "gram" || it == "ml")) conv = "1000" }, withNone = true)
                if (unit2.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        T("1 $unit =", 14, color = C.muted)
                        Cell(conv, { conv = it }, 90.dp)
                        T(unit2, 14, color = C.muted)
                    }
                    T("This lets the AI bill \"aadha kilo\" or \"250 gram\" correctly.", 12, color = C.muted)
                }
                Field(cat, { cat = it }, label = "Category (optional)", hint = "Milk, Paneer, Sweets")
            }
            CardBox(gap = 11.dp) {
                Eyebrow("Price")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(sale, { sale = it }, Modifier.weight(1f), label = "Sale price", hint = "340", number = true)
                    Field(purchase, { purchase = it }, Modifier.weight(1f), label = "Purchase price", hint = "285", number = true)
                }
                T("Purchase price gives you the profit later. Leave it blank if you want.", 12, color = C.muted)
                if (Repo.prefs.b("shopGst", false)) {
                    T("GST rate", 13, FontWeight.SemiBold, C.muted)
                    Seg(listOf("0" to "None", "5" to "5%", "12" to "12%", "18" to "18%"), tax.toString(), { tax = it.toInt() }, dark = true)
                }
            }
            CardBox(gap = 11.dp) {
                Eyebrow("Stock")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(stock, { stock = it }, Modifier.weight(1f), label = "Stock now", hint = "0", number = true)
                    Field(low, { low = it }, Modifier.weight(1f), label = "Warn me below", hint = "5", number = true)
                }
            }
            CardBox(gap = 10.dp) {
                Eyebrow("Words customers use")
                T("The AI matches these words from a call to this product. Add every word customers really say, like safed makhan or panir.", 12, color = C.muted)
                if (words.isEmpty()) T("No word added yet", 13, color = C.muted)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    words.toList().forEach { w ->
                        Row(Modifier.clip(RoundedCornerShape(50)).background(C.soft).padding(start = 11.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(w, 14, FontWeight.SemiBold, C.violetD)
                            T("  ×", 15, FontWeight.Bold, C.violetD, Modifier.clickable { words.remove(w) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(newWord, { newWord = it }, Modifier.weight(1f), hint = "safed makhan")
                    Btn("Add", {
                        val w = newWord.trim().lowercase()
                        if (w.isNotEmpty() && w !in words) words.add(w)
                        newWord = ""
                    }, kind = "dark", small = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (id > 0) Btn(if (armed) "Sure?" else "Delete", {
                    if (!armed) armed = true else scope.launch {
                        Repo.dao.product(id)?.let { Repo.dao.saveProduct(it.copy(active = false)) }
                        toast(ctx, "Product removed"); nav.back()
                    }
                }, Modifier.width(104.dp), "danger")
                Btn(if (id > 0) "Save changes" else "Save product", { save() }, Modifier.weight(1f))
            }
            Gap(8)
        }
    }
}

/* ---------- settings ---------- */

@Composable
fun Settings(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ch by Repo.prefs.changes.collectAsState()
    val pstate by Repo.printerState.collectAsState()
    var showPrinters by remember { mutableStateOf(false) }
    var org by remember { mutableStateOf(Repo.prefs.s("org", "Your Shop Name")) }
    val folderPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
            Repo.prefs.put("folder", uri.toString())
            if (Repo.prefs.l("scanFrom", 0L) == 0L) Repo.prefs.put("scanFrom", System.currentTimeMillis())
            Repo.note("Recording folder set")
        }
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Repo.startService(ctx) }
    val p = Repo.prefs
    Column(Modifier.fillMaxSize()) {
        Header("Settings", "", { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            CardBox(gap = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(50)).background(C.grad), contentAlignment = Alignment.Center) {
                        T(org.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase().ifBlank { "P" }, 18, FontWeight.Bold, Color.White)
                    }
                    Column(Modifier.weight(1f)) { Eyebrow("Organisation"); T(org.ifBlank { "Your organisation" }, 18, FontWeight.Bold, maxLines = 1) }
                }
                Field(org, { org = it; p.put("org", it) }, label = "Organisation name", hint = "Your organisation name")
                PrefField("Business phone", "phone", "98xxxxxx00")
                T("This is your profile name. It shows on Home and prints on every bill.", 12, color = C.muted)
            }

            CardBox(gap = 9.dp) {
                Eyebrow("Printer")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(p.s("printerName").ifBlank { "No printer chosen" }, 16, FontWeight.Bold)
                        T(if (p.s("printer").isBlank()) "Pair it in phone Bluetooth settings first, then choose it here" else pstate.ifBlank { "Bluetooth · ready" }, 12, color = C.muted)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Choose printer", {
                        if (!Printer.hasPermission(ctx) && Build.VERSION.SDK_INT >= 31) perms.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                        showPrinters = !showPrinters
                    }, Modifier.weight(1f), "sec", small = true)
                    Btn("Test print", { scope.launch { val e = Printer.test(ctx); toast(ctx, e ?: "Test sent") } }, Modifier.weight(1f), "sec", small = true)
                }
                if (showPrinters) {
                    val list = Printer.paired(ctx)
                    if (list.isEmpty()) T("No paired device found. Pair the printer in phone Bluetooth settings, then come back.", 13, color = C.warn)
                    list.forEach { (n, mac) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, if (mac == p.s("printer")) C.violet else C.line, RoundedCornerShape(12.dp))
                            .clickable { p.put("printer", mac); p.put("printerName", n); showPrinters = false; Repo.wake.tryEmit(Unit) }.padding(10.dp)) {
                            Column { T(n, 15, FontWeight.Bold); T(mac, 12, color = C.muted) }
                        }
                    }
                }
                Btn("Bill print format · ${p.s("size", "3")} inch  ›", { nav.go(Route("printcfg")) }, Modifier.fillMaxWidth(), "sec", small = true)
            }

            CardBox(gap = 9.dp) {
                Eyebrow("Calls and AI")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("Call recording folder", 15, FontWeight.Bold)
                        T(if (p.s("folder").isBlank()) "Not chosen yet" else Uri.parse(p.s("folder")).lastPathSegment?.substringAfter(":")?.ifBlank { "Chosen" } ?: "Chosen", 12, color = C.muted)
                    }
                    Btn(if (p.s("folder").isBlank()) "Choose" else "Change", { folderPick.launch(null) }, kind = "sec", small = true)
                }
                T("Read recordings made from", 13, FontWeight.SemiBold, C.muted)
                val from = p.l("scanFrom", 0L)
                val now = System.currentTimeMillis()
                Seg(listOf("now" to "Now on", "today" to "Today", "3d" to "Last 3 days"),
                    when { from == 0L || now - from < 3_600_000L -> "now"; now - from <= 86_400_000L * 1.5 -> "today"; else -> "3d" }, { k ->
                        val start = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        p.put("scanFrom", when (k) { "today" -> start; "3d" -> start - 2 * 86_400_000L; else -> System.currentTimeMillis() })
                    }, dark = true)
                T("Use Today or Last 3 days to test with calls you already have.", 12, color = C.muted)
                PrefField("Gemini API key", "geminiKey", "Paste your key", password = true)
                PrefField("Gemini model", "geminiModel", "gemini-2.5-flash", def = "gemini-2.5-flash")
                T("Language of the calls", 13, FontWeight.SemiBold, C.muted)
                var lang by remember { mutableStateOf(p.s("callLang", "auto")) }
                Seg(listOf("auto" to "Mixed", "hi" to "Hindi", "hinglish" to "Hinglish", "en" to "English"), lang, { lang = it; p.put("callLang", it) }, dark = true)
                Btn("Check for new calls now", { toast(ctx, "Checking"); scope.launch { Pipeline.scan(ctx) } }, Modifier.fillMaxWidth(), "sec", small = true)
            }

            CardBox(gap = 9.dp) {
                Eyebrow("Telegram approvals")
                PrefField("Bot token from @BotFather", "tgToken", "123456:ABC...", password = true)
                if (ch >= 0 && p.s("tgChat").isNotBlank()) {
                    T("Connected", 14, FontWeight.Bold, C.ok)
                    Btn("Disconnect", { p.put("tgChat", "") }, kind = "sec", small = true)
                } else T("After saving the token, open your bot in Telegram and send /start. It connects within a minute.", 13, color = C.muted)
                PrefCheck("Also message me for every bill made", "tgEvery", true)
            }

            CardBox(gap = 4.dp) {
                Eyebrow("Billing")
                PrefCheck("Use last price for each party", "lastPrice", true)
                T("On: a new bill fills the price this party paid last time. Off: his special price, or standard.", 12, color = C.muted)
                PrefCheck("My shop raises GST bills", "shopGst", false)
                T("Off hides tax fields. On: each party decides whether his bill is a tax invoice.", 12, color = C.muted)
            }

            CardBox(gap = 8.dp) {
                Eyebrow("Phone permissions")
                Btn("Allow call log, Bluetooth and notifications", {
                    perms.launch(buildList {
                        add(Manifest.permission.READ_CALL_LOG)
                        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray())
                }, Modifier.fillMaxWidth(), "sec", small = true)
                Btn("Keep running in the background", {
                    try {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
                    } catch (e: Exception) { toast(ctx, "Open phone Settings, Battery, and allow Parchi to run in background") }
                }, Modifier.fillMaxWidth(), "sec", small = true)
                T("On Xiaomi, Vivo, Oppo and Realme also turn on Autostart for Parchi in phone settings.", 12, color = C.muted)
            }
            Btn("What the app is doing  ›", { nav.go(Route("log")) }, Modifier.fillMaxWidth(), "sec", small = true)
            Btn("Products  ›", { nav.go(Route("products")) }, Modifier.fillMaxWidth(), "sec", small = true)
            Gap(10)
        }
    }
}

@Composable
fun PrintCfg(nav: Nav) {
    val ch by Repo.prefs.changes.collectAsState()
    val bills by Repo.dao.billsFlow().collectAsState(initial = emptyList())
    var lines by remember { mutableStateOf<List<PLine>>(emptyList()) }
    val sample = bills.firstOrNull { !it.isCancelled() }
    LaunchedEffect(ch, sample?.id) { lines = sample?.let { Printer.linesFor(it.id) } ?: emptyList() }
    val p = Repo.prefs
    var size by remember { mutableStateOf(p.s("size", "3")) }
    var fmt by remember { mutableStateOf(p.s("fmt", "auto")) }
    Column(Modifier.fillMaxSize()) {
        Header("Bill print format", "The preview changes as you edit", { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            CardBox(gap = 8.dp) {
                Eyebrow("Paper size")
                Seg(listOf("2" to "2 inch", "3" to "3 inch"), size, { size = it; p.put("size", it) })
                T("2 inch fits 32 letters a line, so each item takes two lines. 3 inch fits 48.", 12, color = C.muted)
            }
            CardBox(gap = 10.dp) {
                Eyebrow("Shop details")
                PrefField("Shop name", "org", "Your Shop Name", def = "Your Shop Name")
                PrefField("Address line", "addr", "Dairy products, Faridabad")
                PrefField("Shop phone", "phone", "98xxxxxx00")
                PrefCheck("Show shop phone on the bill", "showPhone", true)
                if (p.b("shopGst", false)) PrefField("Shop GSTIN", "gstin", "06XXXXX0000X1Z0")
            }
            CardBox(gap = 8.dp) {
                Eyebrow("Bill type")
                listOf(Triple("auto", "Bill, tax invoice for GST parties", "Each party's GST setting decides"),
                    Triple("simple", "Simple bill for everyone", "No tax lines"),
                    Triple("slip", "Delivery slip", "Items and quantity only, no rates")).forEach { (k, t, sub) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(if (fmt == k) 2.dp else 1.dp, if (fmt == k) C.violet else C.line, RoundedCornerShape(12.dp))
                        .clickable { fmt = k; p.put("fmt", k) }.padding(10.dp)) {
                        Column { T(t, 15, FontWeight.Bold); T(sub, 12, color = C.muted) }
                    }
                }
            }
            CardBox(gap = 6.dp) {
                Eyebrow("Also show")
                PrefCheck("Party phone number", "partyPhone", true)
                PrefCheck("Old due and balance", "oldDue", true)
                PrefField("Last line", "footer", "Thank you, visit again", def = "Thank you, visit again")
            }
            Eyebrow("Preview")
            if (lines.isEmpty()) T("Make one bill to see the preview here.", 13, color = C.muted) else Slip(lines)
            Gap(10)
        }
    }
}

@Composable
fun LogScreen(nav: Nav) {
    val log by Repo.log.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Header("What the app is doing", "Since the app was opened", { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (log.isEmpty()) T("Nothing yet.", 14, color = C.muted)
            log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = C.ink) }
        }
    }
}
