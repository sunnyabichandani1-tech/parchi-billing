package com.parchi.billing

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/* ---------- home ---------- */

@Composable
fun Home(nav: Nav) {
    val bills by Repo.dao.billsFlow().collectAsState(initial = emptyList())
    val parties by Repo.dao.partiesFlow().collectAsState(initial = emptyList())
    val orders by Repo.dao.ordersFlow().collectAsState(initial = emptyList())
    val products by Repo.dao.productsFlow().collectAsState(initial = emptyList())
    val ch by Repo.prefs.changes.collectAsState()
    val t = today()
    val todays = bills.filter { it.iso == t && !it.isCancelled() }
    val due = duesOf(parties, bills).values.sumOf { it.due }
    val check = orders.count { it.status == "check" || it.status == "noparty" || it.status == "error" }
    val autoB = bills.filter { it.auto && !it.isCancelled() }
    val toDel = autoB.count { !it.delivered }
    val low = products.count { it.stock <= it.low }
    val missing = listOfNotNull(
        if (ch >= 0 && Repo.prefs.s("geminiKey").isBlank()) "Gemini key" else null,
        if (Repo.prefs.s("folder").isBlank()) "call recording folder" else null,
        if (Repo.prefs.s("printer").isBlank()) "printer" else null,
        if (products.isEmpty()) "products" else null
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().background(C.grad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                T(Repo.prefs.s("org", "Your Shop Name"), 19, FontWeight.Bold, Color.White, maxLines = 1)
                T(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)), 13, color = Color.White.copy(alpha = 0.85f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroTile("Today sale", money(todays.sumOf { it.total }), "${todays.size} bill${if (todays.size == 1) "" else "s"} ›", Modifier.weight(1f)) { nav.go(Route("todaybills")) }
                HeroTile("To collect", money(due), "See who owes ›", Modifier.weight(1f)) { nav.go(Route("pending")) }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (missing.isNotEmpty()) {
                CardBox(Modifier.clickable { nav.tab("settings") }, gap = 4.dp) {
                    T("Finish setup", 16, FontWeight.Bold)
                    T("Still needed: " + missing.joinToString(", "), 13, color = C.warn)
                }
            }
            HomeRow("Auto bills", if (orders.isEmpty() && autoB.isEmpty()) "Nothing yet" else "${autoB.size} billed · $toDel to deliver" + if (check > 0) " · $check need you" else "", check) { nav.tab("orders") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CardBox(Modifier.weight(1f).clickable { nav.go(Route("products")) }, gap = 2.dp) {
                    T("Products", 13, color = C.muted)
                    T(if (low > 0) "$low low" else "${products.size}", 20, FontWeight.Bold, if (low > 0) C.red else C.ink)
                }
                CardBox(Modifier.weight(1f).clickable { nav.tab("parties") }, gap = 2.dp) {
                    T("Parties", 13, color = C.muted)
                    T("${parties.size}", 20, FontWeight.Bold)
                }
            }
            Btn("+ New bill", { nav.go(Route("newbill")) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HeroTile(label: String, value: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(Color.White.copy(alpha = 0.16f)).border(1.dp, Color.White.copy(alpha = 0.3f), shape)
        .clickable { onClick() }.padding(12.dp)) {
        T(label, 12, FontWeight.SemiBold, Color.White.copy(alpha = 0.85f))
        T(value, 20, FontWeight.Bold, Color.White, maxLines = 1)
        T(sub, 12, FontWeight.Bold, Color.White)
    }
}

@Composable
private fun HomeRow(title: String, sub: String, badge: Int, onClick: () -> Unit) {
    CardBox(Modifier.clickable { onClick() }, gap = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(title, 16, FontWeight.Bold)
                T(sub, 13, color = C.muted)
            }
            if (badge > 0) T("$badge", 13, FontWeight.Bold, Color.White, Modifier.clip(RoundedCornerShape(50)).background(C.warn).padding(horizontal = 9.dp, vertical = 2.dp))
        }
    }
}

/* ---------- new bill and edit bill ---------- */

@Composable
fun NewBill(nav: Nav, editId: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val parties by Repo.dao.partiesFlow().collectAsState(initial = emptyList())
    val bills by Repo.dao.billsFlow().collectAsState(initial = emptyList())
    val products by Repo.dao.productsFlow().collectAsState(initial = emptyList())
    var partyId by remember { mutableStateOf(0L) }
    var iso by remember { mutableStateOf(today()) }
    var mode by remember { mutableStateOf("credit") }
    val lines = remember { mutableStateListOf<Line>() }
    var billDisc by remember { mutableStateOf("") }
    var openDisc by remember { mutableStateOf(false) }
    var received by remember { mutableStateOf("") }
    var openRcv by remember { mutableStateOf(false) }
    var psearch by remember { mutableStateOf("") }
    var isearch by remember { mutableStateOf("") }
    var sugg by remember { mutableStateOf<List<Pair<Product, Double>>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var editNo by remember { mutableStateOf(0) }
    var focusPid by remember { mutableStateOf(-1L) }

    LaunchedEffect(editId) {
        if (editId > 0) {
            val b = Repo.dao.bill(editId) ?: return@LaunchedEffect
            partyId = b.partyId; iso = b.iso; editNo = b.no
            billDisc = if (b.billDiscPct > 0) qtyStr(b.billDiscPct) else ""; openDisc = b.billDiscPct > 0
            received = if (b.paid > 0) qtyStr(b.paid) else ""; openRcv = b.paid > 0
            lines.clear()
            Repo.dao.items(b.id).forEach {
                lines.add(Line(it.productId, it.name, it.unit, qtyStr(it.qty), qtyStr(it.rate), if (it.disc > 0) qtyStr(it.disc) else "", it.tax, "on bill", it.disc > 0))
            }
        }
    }
    LaunchedEffect(partyId) { if (partyId > 0) sugg = Repo.suggest(partyId) }
    val party = parties.find { it.id == partyId }
    val totals = calc(lines, billDisc.toDoubleOrNull() ?: 0.0, party?.gst == true)
    LaunchedEffect(totals.total, mode) { if (mode == "cash") received = qtyStr(totals.total) }
    val oldDue = r2(if (party == null) 0.0 else bills.filter { it.partyId == party.id && it.isOpen() && it.id != editId }.sumOf { it.leftToPay() })

    fun addProduct(p: Product, qty: String) {
        val pt = party ?: return
        if (lines.any { it.productId == p.id }) { toast(ctx, "${p.name} is already on the bill"); return }
        scope.launch {
            val pr = Repo.priceFor(pt, p)
            lines.add(Line(p.id, p.name, p.unit, qty, qtyStr(pr.first), "", p.tax, pr.second))
            isearch = ""
            if (qty.isEmpty()) focusPid = p.id
        }
    }

    fun save(print: Boolean) {
        if (saving) return
        val bad = lines.firstOrNull { (it.qty.toDoubleOrNull() ?: 0.0) <= 0 }
        if (bad != null) { toast(ctx, "Enter quantity for ${bad.name}"); return }
        saving = true
        scope.launch {
            val (id, err) = Repo.saveBill(BillInput(partyId, lines.toList(), billDisc.toDoubleOrNull() ?: 0.0,
                received.toDoubleOrNull() ?: 0.0, iso, print, editId = if (editId > 0) editId else null))
            saving = false
            if (id == null) { toast(ctx, err ?: "Could not save"); return@launch }
            toast(ctx, if (print) "Saved. Sending to the printer" else "Saved")
            if (editId > 0) nav.back() else nav.tab("home")
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(if (editId > 0) "Edit bill" else "New bill",
            "Bill no. " + (if (editId > 0) editNo else ((bills.maxOfOrNull { it.no } ?: 1000) + 1)), onBack = { nav.back() }) {
            Seg(listOf("credit" to "Credit", "cash" to "Cash"), mode, { mode = it; if (it == "credit") { received = ""; openRcv = false } })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DateRow(iso) { iso = it }
            if (party == null) {
                val billedToday = Repo.billedToday(bills)
                val freq = bills.groupingBy { it.partyId }.eachCount()
                val q = psearch.trim().lowercase()
                val list = if (q.isEmpty()) parties.filter { it.id !in billedToday }.sortedByDescending { freq[it.id] ?: 0 }
                else parties.filter { p -> p.name.lowercase().contains(q) || p.phoneList().any { it.first.contains(q) } }
                Field(psearch, { psearch = it }, hint = "Search party name or number")
                Eyebrow(if (q.isEmpty()) "Pick a party" else "Matching parties")
                if (list.isEmpty()) T(if (parties.isEmpty()) "No party yet. Add your first party." else "No party found.", 14, color = C.muted)
                else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                    list.take(60).forEachIndexed { i, p ->
                        if (i > 0) Hr()
                        Row(Modifier.fillMaxWidth().clickable { partyId = p.id; psearch = "" }.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Av(p.name)
                            Column(Modifier.weight(1f)) { T(p.name, 15, FontWeight.Bold, maxLines = 1); T(p.firstPhone(), 13, color = C.muted) }
                            if (p.id in billedToday) Pill("billed today")
                        }
                    }
                }
                Btn("+ New party", { nav.go(Route("partyedit")) }, Modifier.fillMaxWidth(), "sec")
                val hidden = if (q.isEmpty()) parties.count { it.id in billedToday } else 0
                if (hidden > 0) T("$hidden ${if (hidden == 1) "party is" else "parties are"} already billed today and hidden. Search to find them.", 13, color = C.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Av(party.name)
                    Column(Modifier.weight(1f)) {
                        T(party.name, 15, FontWeight.Bold, maxLines = 1)
                        T(party.firstPhone() + if (party.gst) " · tax invoice" else " · simple bill", 13, color = C.muted)
                    }
                    if (editId == 0L) T("Change", 14, FontWeight.Bold, C.violet, Modifier.clickable { partyId = 0; lines.clear() }.padding(6.dp))
                }
                val sug = sugg.filter { s -> lines.none { it.productId == s.first.id } }
                if (sug.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Eyebrow("Usual")
                    sug.forEach { (p, q) ->
                        val txt = buildAnnotatedString { append(p.name + " "); withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(qtyStr(q)) } }
                        Text(txt, color = C.violetD, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(C.soft).clickable { addProduct(p, qtyStr(q)) }.padding(horizontal = 11.dp, vertical = 6.dp))
                    }
                }
                Field(isearch, { isearch = it }, hint = "Search product to add")
                val q = isearch.trim().lowercase()
                if (q.isNotEmpty()) {
                    val hits = products.filter { p -> p.name.lowercase().contains(q) || p.aliasList().any { it.contains(q) } }.take(8)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, C.line, RoundedCornerShape(14.dp))) {
                        if (hits.isEmpty()) T("Nothing matches. Add it in Products.", 14, color = C.muted, modifier = Modifier.padding(12.dp))
                        hits.forEachIndexed { i, p ->
                            if (i > 0) Hr()
                            Row(Modifier.fillMaxWidth().clickable { addProduct(p, "") }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                Column(Modifier.weight(1f)) {
                                    T(p.name, 15, FontWeight.Bold)
                                    T("${money(p.sale)} per ${p.unit} · stock ${qtyStr(p.stock)}", 12, color = C.muted)
                                }
                                T("+", 18, FontWeight.Bold, C.violet)
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Eyebrow("Product", C.muted); Spacer(Modifier.weight(1f))
                        T("QTY", 10, FontWeight.Bold, C.muted, Modifier.width(74.dp), align = TextAlign.End)
                        T("PRICE", 10, FontWeight.Bold, C.muted, Modifier.width(60.dp), align = TextAlign.End)
                        T("AMOUNT", 10, FontWeight.Bold, C.muted, Modifier.width(72.dp), align = TextAlign.End)
                        Spacer(Modifier.width(14.dp))
                    }
                    Hr()
                    if (lines.isEmpty()) T("Search a product above, or tap one from Usual. Tap a product name to give it a discount.", 13, color = C.muted,
                        modifier = Modifier.padding(14.dp), align = TextAlign.Center)
                    lines.forEachIndexed { i, l -> key(l.productId) {
                        val fr = remember { FocusRequester() }
                        LaunchedEffect(focusPid) { if (focusPid == l.productId) { try { fr.requestFocus() } catch (e: Exception) { }; focusPid = -1 } }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            val name = buildAnnotatedString {
                                append(l.name)
                                if ((l.disc.toDoubleOrNull() ?: 0.0) > 0) withStyle(SpanStyle(color = C.violet, fontSize = 11.sp)) { append(" -${l.disc}%") }
                            }
                            Text(name, Modifier.weight(1f).clickable { lines[i] = l.copy(showDisc = !l.showDisc) }, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Cell(l.qty, { lines[i] = l.copy(qty = it) }, 74.dp, unitShort(l.unit), error = l.qty.isEmpty(), fr = fr)
                            Cell(l.rate, { lines[i] = l.copy(rate = it, src = "edited") }, 60.dp)
                            T(if ((totals.amts.getOrNull(i) ?: 0.0) > 0) money(totals.amts[i]) else "·", 14, FontWeight.Bold, modifier = Modifier.width(72.dp), align = TextAlign.End, maxLines = 1)
                            T("×", 18, FontWeight.Bold, C.muted, Modifier.width(14.dp).clickable { lines.removeAt(i) })
                        }
                        if (l.showDisc) Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            T("Item discount", 13, color = C.muted)
                            Cell(l.disc, { lines[i] = l.copy(disc = it) }, 56.dp)
                            T("%", 13, color = C.muted)
                            Spacer(Modifier.weight(1f))
                            if (party.gst && l.tax > 0) T("GST ${l.tax}%", 12, color = C.muted)
                        }
                        Hr()
                    } }
                }
                Gap(4)
            }
        }
        if (party != null && lines.isNotEmpty()) {
            val rcv = received.toDoubleOrNull() ?: 0.0
            val totalDue = r2(oldDue + totals.total)
            val left = (totalDue - rcv).coerceAtLeast(0.0)
            val showR = oldDue > 0 || openRcv || mode == "cash" || received.isNotEmpty()
            Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (openDisc) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T("Discount", 14, color = C.muted)
                    Cell(billDisc, { billDisc = it }, 56.dp)
                    T("%", 14, color = C.muted)
                    Spacer(Modifier.weight(1f))
                    T(if (totals.billDisc > 0) "- " + money(totals.billDisc) else money(0.0), 14, color = C.muted)
                }
                if (showR) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val txt = buildAnnotatedString {
                        withStyle(SpanStyle(color = C.muted)) { append("Due ") }
                        withStyle(SpanStyle(color = C.red, fontWeight = FontWeight.SemiBold)) { append(money(totalDue)) }
                        if (oldDue > 0) withStyle(SpanStyle(color = C.muted, fontSize = 12.sp)) { append("  (old ${money(oldDue)})") }
                    }
                    Text(txt, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Cell(received, { received = it }, 108.dp, hint = "Received ₹")
                }
                if (rcv > 0) T(if (left > 0) "${money(left)} still due after this" else "All clear after this", 12, FontWeight.Bold,
                    if (left > 0) C.red else C.ok, Modifier.fillMaxWidth(), align = TextAlign.End)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniChip(if (openDisc) "Discount" else "+ Discount", openDisc) { openDisc = !openDisc; if (!openDisc) billDisc = "" }
                    if (oldDue <= 0) MiniChip(if (showR) "Received" else "+ Received", showR) {
                        openRcv = !showR; if (showR) { received = ""; mode = "credit" }
                    }
                    Spacer(Modifier.weight(1f))
                    T(money(totals.total), 22, FontWeight.Bold)
                }
                T("${lines.size} item${if (lines.size == 1) "" else "s"}" + (if (party.gst && totals.tax > 0) " · incl. GST ${money(totals.tax)}" else "") +
                    (if (totals.billDisc > 0) " · after discount" else ""), 12, color = C.muted, modifier = Modifier.fillMaxWidth(), align = TextAlign.End)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Save only", { save(false) }, Modifier.width(118.dp), "sec")
                    Btn(if (saving) "Saving…" else if (editId > 0) "Save and reprint" else "Save and print", { save(true) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MiniChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(text, color = C.violet, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) C.soft else Color.White)
            .border(1.dp, if (on) C.violet else C.line, RoundedCornerShape(50)).clickable { onClick() }.padding(horizontal = 11.dp, vertical = 4.dp))
}

@Composable
fun Cell(value: String, onChange: (String) -> Unit, width: Dp, suffix: String = "", hint: String = "", error: Boolean = false, fr: FocusRequester? = null) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.width(width).height(32.dp).clip(shape).background(C.bg).border(1.dp, if (error) C.rose else C.line, shape).padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = C.muted, fontSize = 13.sp, maxLines = 1)
            BasicTextField(value, { onChange(cleanNum(it)) }, singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.ink, textAlign = TextAlign.End),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = (if (fr != null) Modifier.focusRequester(fr) else Modifier).fillMaxWidth())
        }
        if (suffix.isNotEmpty()) { Spacer(Modifier.width(3.dp)); Text(suffix, color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun DateRow(iso: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val t = today()
    val yd = shiftDay(t, -1)
    val tm = shiftDay(t, 1)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("📅  " + fmtD(iso, true), color = C.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).border(1.dp, C.line, RoundedCornerShape(10.dp))
                .clickable { pickDate(ctx, iso, onPick) }.padding(horizontal = 10.dp, vertical = 6.dp))
        DayChip("Yesterday", iso == yd) { onPick(if (iso == yd) t else yd) }
        DayChip("Tomorrow", iso == tm) { onPick(if (iso == tm) t else tm) }
        if (iso != t && iso != yd && iso != tm) Pill(if (iso > t) "future" else "past", if (iso > t) "done" else "wait")
    }
}

@Composable
private fun DayChip(text: String, on: Boolean, onClick: () -> Unit) {
    val m = Modifier.clip(RoundedCornerShape(50)).let { if (on) it.background(C.grad) else it.background(Color.White).border(1.dp, C.line, RoundedCornerShape(50)) }
    Text(text, color = if (on) Color.White else C.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = m.clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp))
}

/* ---------- bill lists ---------- */

@Composable
fun BillRow(b: Bill, partyName: String, nav: Nav) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).clickable { nav.go(Route("bill", b.id)) }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            T(partyName, 15, FontWeight.Bold, maxLines = 1)
            T("No. ${b.no} · ${fmtD(b.iso)} · ${if (b.auto) "from call" else "by hand"}", 12, color = C.muted, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PrintTag(b)
                if (!b.isCancelled()) { if (b.leftToPay() > 0) Pill("Due ${money(b.leftToPay())}", "wait") else Pill("Paid", "done") }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            T(money(b.total), 15, FontWeight.Bold)
            DeliverBtn(b) { scope.launch { Repo.toggleDelivered(b.id) } }
        }
    }
}

@Composable
fun TodayBills(nav: Nav) {
    val bills by Repo.dao.billsFlow().collectAsState(initial = emptyList())
    val parties by Repo.dao.partiesFlow().collectAsState(initial = emptyList())
    var f by remember { mutableStateOf("all") }
    val t = today()
    val all = bills.filter { it.iso == t }
    val preds: Map<String, (Bill) -> Boolean> = mapOf(
        "all" to { b -> !b.isCancelled() }, "auto" to { b -> b.auto && !b.isCancelled() }, "manual" to { b -> !b.auto && !b.isCancelled() },
        "print" to { b -> b.printPending() }, "deliver" to { b -> !b.isCancelled() && !b.delivered }, "cancelled" to { b -> b.isCancelled() }
    )
    val names = parties.associate { it.id to it.name }
    val live = all.filter { !it.isCancelled() }
    Column(Modifier.fillMaxSize()) {
        Header("Today sale", "${live.size} bill${if (live.size == 1) "" else "s"} · ${money(live.sumOf { it.total })}", { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Chips(listOf("all" to "All", "auto" to "Auto", "manual" to "Manual", "print" to "Print pending", "deliver" to "To deliver", "cancelled" to "Cancelled")
                .map { (k, l) -> Triple(k, l, all.count(preds.getValue(k))) }, f) { f = it }
            val list = all.filter(preds.getValue(f))
            if (list.isEmpty()) T("No bill here yet today.", 14, color = C.muted, modifier = Modifier.padding(20.dp).fillMaxWidth(), align = TextAlign.Center)
            else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                list.forEachIndexed { i, b -> if (i > 0) Hr(); BillRow(b, names[b.partyId] ?: "?", nav) }
            }
        }
    }
}

/* ---------- one bill ---------- */

@Composable
fun BillView(nav: Nav, id: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val b by Repo.dao.billFlow(id).collectAsState(initial = null)
    val items by Repo.dao.itemsFlow(id).collectAsState(initial = emptyList())
    val parties by Repo.dao.partiesFlow().collectAsState(initial = emptyList())
    val ch by Repo.prefs.changes.collectAsState()
    var lines by remember { mutableStateOf<List<PLine>>(emptyList()) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(b, items, ch) { lines = Printer.linesFor(id) ?: emptyList() }
    val bill = b ?: run { Header("Bill", "", { nav.back() }); return }
    val party = parties.find { it.id == bill.partyId }
    Column(Modifier.fillMaxSize()) {
        Header("Bill no. ${bill.no}", "${party?.name ?: ""} · ${fmtD(bill.iso, true)}", { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (bill.auto) "From call" else "Made by hand")
                PrintTag(bill)
                if (!bill.isCancelled()) {
                    Pill(if (bill.delivered) "Delivered" else "To deliver", if (bill.delivered) "done" else "wait")
                    if (bill.leftToPay() > 0) Pill("Due ${money(bill.leftToPay())}", "wait") else Pill("Paid", "done")
                }
            }
            if (bill.isCancelled()) {
                CardBox { T("This bill is cancelled. Stock went back, and it no longer counts in his due.", 14, color = C.muted) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Btn("Edit", { nav.go(Route("newbill", bill.id)) }, Modifier.weight(1f), "sec")
                    DeliverBtn(bill, Modifier.weight(1f), big = true) { scope.launch { Repo.toggleDelivered(bill.id) } }
                    Btn(if (bill.printed) "Reprint" else "Print now", { scope.launch { Repo.printAgain(bill.id); toast(ctx, "Sent to the printer") } }, Modifier.weight(1f), "sec")
                }
            }
            Slip(lines)
            if (!bill.isCancelled()) {
                T(if (armed) "Tap again to cancel this bill" else "Cancel bill", 14, if (armed) FontWeight.ExtraBold else FontWeight.SemiBold, C.red,
                    Modifier.fillMaxWidth().clickable {
                        if (armed) { scope.launch { Repo.cancelBill(bill.id); toast(ctx, "Bill cancelled") }; armed = false } else armed = true
                    }.padding(10.dp), align = TextAlign.Center)
            }
        }
    }
}

/* ---------- auto bills from calls ---------- */

@Composable
fun AutoBills(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val orders by Repo.dao.ordersFlow().collectAsState(initial = emptyList())
    val bills by Repo.dao.billsFlow().collectAsState(initial = emptyList())
    val parties by Repo.dao.partiesFlow().collectAsState(initial = emptyList())
    val products by Repo.dao.productsFlow().collectAsState(initial = emptyList())
    var f by remember { mutableStateOf("all") }
    var armed by remember { mutableStateOf(-1L) }
    val byBill = bills.associateBy { it.id }
    val names = products.associateBy { it.id }
    fun billOf(o: CallOrder) = o.billId?.let { byBill[it] }
    val preds: Map<String, (CallOrder) -> Boolean> = mapOf(
        "all" to { _ -> true },
        "check" to { o -> o.status == "check" || o.status == "noparty" || o.status == "error" },
        "deliver" to { o -> billOf(o)?.let { !it.isCancelled() && !it.delivered } ?: false },
        "delivered" to { o -> billOf(o)?.let { !it.isCancelled() && it.delivered } ?: false },
        "cancelled" to { o -> o.status == "rejected" || (billOf(o)?.isCancelled() ?: false) }
    )
    Column(Modifier.fillMaxSize()) {
        Header("Auto bills", "Bills made from phone calls", { nav.back() }) {
            T("Check now", 13, FontWeight.Bold, C.violet, Modifier.clickable {
                toast(ctx, "Checking the recording folder"); scope.launch { Pipeline.scan(ctx) }
            }.padding(6.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Chips(listOf("all" to "All", "check" to "Need check", "deliver" to "To deliver", "delivered" to "Delivered", "cancelled" to "Cancelled")
                .map { (k, l) -> Triple(k, l, orders.count(preds.getValue(k))) }, f) { f = it }
            val list = orders.filter(preds.getValue(f))
            if (list.isEmpty()) T(if (orders.isEmpty()) "No calls read yet. New call recordings show up here on their own." else "Nothing in this filter.",
                14, color = C.muted, modifier = Modifier.padding(20.dp).fillMaxWidth(), align = TextAlign.Center)
            list.forEach { o ->
                val party = parties.find { it.id == o.partyId }
                val items = itemsFrom(o.itemsJson)
                val b = billOf(o)
                CardBox(gap = 8.dp) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            T(party?.name ?: if (o.phone.isBlank()) "Unknown number" else "New number ${o.phone}", 16, FontWeight.Bold, maxLines = 1)
                            T((if (o.phone.isNotBlank()) o.phone + " · " else "") + "call at ${clock(o.callAt)} · ${o.secs} sec", 12, color = C.muted)
                        }
                        when {
                            o.status == "reading" -> Pill("Reading…", "violet")
                            o.status == "error" -> Pill("Problem", "wait")
                            o.status == "check" -> Pill("Need check", "wait")
                            o.status == "noparty" -> Pill("No party", "wait")
                            o.status == "noorder" -> Pill("No order")
                            o.status == "rejected" -> Pill("Rejected")
                            b != null && b.isCancelled() -> Pill("Cancelled")
                            b != null -> Pill(if (b.delivered) "Delivered" else "To deliver", if (b.delivered) "done" else "wait")
                        }
                    }
                    if (items.isNotEmpty()) {
                        val txt = buildAnnotatedString {
                            items.forEachIndexed { i, it ->
                                if (i > 0) append(" · ")
                                if (it.sure) append("${names[it.pid]?.name ?: "?"} ${qtyStr(it.qty)}")
                                else withStyle(SpanStyle(color = C.red, fontWeight = FontWeight.Bold)) { append("\"${it.spoken}\" not matched") }
                            }
                        }
                        Text(txt, fontSize = 14.sp, color = C.ink)
                    }
                    when (o.status) {
                        "error" -> {
                            T(o.error, 13, color = C.red)
                            Btn("Try again", { scope.launch { Pipeline.retry(ctx, o.id) } }, Modifier.fillMaxWidth(), "sec")
                        }
                        "check" -> {
                            if (o.error.isNotBlank()) T(o.error, 13, color = C.red)
                            Btn("Open and approve", { nav.go(Route("approve", o.id)) }, Modifier.fillMaxWidth())
                        }
                        "noparty" -> Btn("Add as new party", { nav.go(Route("partyedit", 0, "order:${o.id}:${o.phone}")) }, Modifier.fillMaxWidth(), "dark")
                        "billed" -> if (b != null) {
                            Hr()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    T("No. ${b.no} · ${money(b.total)}", 15, FontWeight.Bold)
                                    T("Delivery ${fmtD(b.iso)}" + if (o.dateNote.startsWith("Customer")) ", said on call" else "", 12, color = C.muted)
                                }
                                PrintTag(b)
                            }
                            if (!b.isCancelled()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Btn("View", { nav.go(Route("bill", b.id)) }, Modifier.weight(1f), "sec", small = true)
                                    Btn("Edit", { nav.go(Route("newbill", b.id)) }, Modifier.weight(1f), "sec", small = true)
                                    DeliverBtn(b, Modifier.weight(1f), big = true) { scope.launch { Repo.toggleDelivered(b.id) } }
                                }
                                T(if (armed == b.id) "Tap again to cancel this bill" else "Cancel bill", 14, if (armed == b.id) FontWeight.ExtraBold else FontWeight.SemiBold, C.red,
                                    Modifier.fillMaxWidth().clickable {
                                        if (armed == b.id) { scope.launch { Repo.cancelBill(b.id) }; armed = -1 } else armed = b.id
                                    }.padding(4.dp), align = TextAlign.Center)
                            } else Btn("View", { nav.go(Route("bill", b.id)) }, Modifier.fillMaxWidth(), "sec", small = true)
                        }
                    }
                }
            }
        }
    }
}

/* ---------- approve a held order ---------- */

@Composable
fun Approve(nav: Nav, id: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val o by Repo.dao.orderFlow(id).collectAsState(initial = null)
    val products by Repo.dao.productsFlow().collectAsState(initial = emptyList())
    val parties by Repo.dao.partiesFlow().collectAsState(initial = emptyList())
    val items = remember { mutableStateListOf<OItem>() }
    val qtys = remember { mutableStateListOf<String>() }
    var iso by remember { mutableStateOf(today()) }
    var learn by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var menuFor by remember { mutableStateOf(-1) }
    var showText by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { try { player?.release() } catch (e: Exception) { } } }
    LaunchedEffect(o) {
        val ord = o
        if (ord != null && !loaded) {
            items.clear(); items.addAll(itemsFrom(ord.itemsJson))
            qtys.clear(); qtys.addAll(items.map { qtyStr(it.qty) })
            iso = ord.deliverIso.ifBlank { today() }
            loaded = true
        }
    }
    val ord = o ?: run { Header("Order", "", { nav.back() }); return }
    val party = parties.find { it.id == ord.partyId }
    val byId = products.associateBy { it.id }
    Column(Modifier.fillMaxSize()) {
        Header(party?.name ?: "Order", (if (ord.phone.isNotBlank()) ord.phone + " · " else "") + "call at ${clock(ord.callAt)}", { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (player != null) "■  Stop" else "▶  Hear the call", {
                    val p = player
                    if (p != null) { try { p.stop(); p.release() } catch (e: Exception) { }; player = null }
                    else try {
                        val mp = MediaPlayer(); mp.setDataSource(ctx, Uri.parse(ord.fileKey)); mp.prepare(); mp.start()
                        mp.setOnCompletionListener { it.release(); player = null }
                        player = mp
                    } catch (e: Exception) { toast(ctx, "Could not play this recording") }
                }, Modifier.weight(1f), "sec")
                Btn(if (showText) "Hide words" else "Read words", { showText = !showText }, Modifier.weight(1f), "sec")
            }
            if (showText) CardBox { T(ord.transcript.ifBlank { "No transcript" }, 14, color = C.muted) }
            CardBox(gap = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Delivery date")
                        T(ord.dateNote, 12, color = C.muted)
                    }
                    T(fmtD(iso, true), 14, FontWeight.Bold, C.violet, Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp))
                        .clickable { pickDate(ctx, iso) { iso = it } }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            CardBox(gap = 8.dp) {
                Eyebrow("Items read from the call")
                items.forEachIndexed { i, it -> key(i) {
                    if (i > 0) Hr()
                    val p = byId[it.pid]
                    if (it.sure && p != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                T(p.name, 15, FontWeight.Bold)
                                T("said \"${it.spoken}\"", 12, color = C.muted, maxLines = 1)
                            }
                            Cell(qtys.getOrElse(i) { "" }, { v -> if (i < qtys.size) qtys[i] = v }, 80.dp, unitShort(p.unit))
                            T("change", 12, FontWeight.Bold, C.violet, Modifier.clickable { menuFor = i })
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.warnSoft).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            T("Not matched. Customer said \"${it.spoken}\", ${qtyStr(it.qty)}", 14, FontWeight.Bold, C.warn)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                it.cands.mapNotNull { c -> byId[c] }.forEach { c ->
                                    Btn(c.name, { items[i] = it.copy(pid = c.id, sure = true, picked = true, unit = c.unit) }, kind = "sec", small = true)
                                }
                                Btn("Other…", { menuFor = i }, kind = "sec", small = true)
                            }
                        }
                    }
                    Box {
                        DropdownMenu(expanded = menuFor == i, onDismissRequest = { menuFor = -1 }) {
                            products.forEach { p ->
                                DropdownMenuItem(text = { Text(p.name) }, onClick = {
                                    items[i] = it.copy(pid = p.id, sure = true, picked = true, unit = p.unit); menuFor = -1
                                })
                            }
                        }
                    }
                } }
                if (items.any { it.picked }) CheckRow("Remember these words for next time", learn) { learn = it }
            }
            if (party == null) CardBox { T("This number is not in your party list. Add the party first from Auto bills.", 14, color = C.warn) }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Reject", { scope.launch { Pipeline.reject(id); nav.back() } }, Modifier.width(104.dp), "danger")
            Btn(if (busy) "Working…" else "Approve and print", {
                if (busy) return@Btn
                val final = items.mapIndexed { i, it -> it.copy(qty = qtys.getOrNull(i)?.toDoubleOrNull() ?: it.qty) }
                busy = true
                scope.launch {
                    val err = Pipeline.approve(id, final, learn, iso)
                    busy = false
                    if (err == null) { toast(ctx, "Bill made"); nav.back() } else toast(ctx, err)
                }
            }, Modifier.weight(1f))
        }
    }
}
