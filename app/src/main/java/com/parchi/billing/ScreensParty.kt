package com.parchi.billing

import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/* ---------- party list ---------- */

@Composable
fun Parties(nav: Nav) {
    val parties by Repo.parties.collectAsState()
    val bills by Repo.bills.collectAsState()
    var q by remember { mutableStateOf("") }
    var f by remember { mutableStateOf("all") }
    val dues = duesOf(parties, bills)
    val toDel = bills.filter { !it.isCancelled() && !it.delivered }.groupingBy { it.partyId }.eachCount()
    val toPrint = bills.filter { it.printPending() }.groupingBy { it.partyId }.eachCount()
    val preds: Map<String, (Party) -> Boolean> = mapOf(
        "all" to { _ -> true }, "due" to { p -> (dues[p.id]?.due ?: 0.0) > 0 },
        "deliver" to { p -> (toDel[p.id] ?: 0) > 0 }, "print" to { p -> (toPrint[p.id] ?: 0) > 0 }
    )
    val s = q.trim().lowercase()
    var list = parties.filter { p -> s.isEmpty() || p.name.lowercase().contains(s) || p.phoneList().any { it.first.contains(s) } }.filter(preds.getValue(f))
    if (f == "due") list = list.sortedByDescending { dues[it.id]?.due ?: 0.0 }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header("Party", "${parties.size} parties", { nav.back() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(q, { q = it }, hint = "Search party name or number")
                Chips(listOf("all" to "All", "due" to "Due", "deliver" to "Pending delivery", "print" to "Pending print")
                    .map { (k, l) -> Triple(k, l, parties.count(preds.getValue(k))) }, f) { f = it }
                if (list.isEmpty()) T(if (parties.isEmpty()) "No party yet. Tap Create party to add your first one." else "No party matches.",
                    14, color = C.muted, modifier = Modifier.fillMaxWidth().padding(20.dp), align = TextAlign.Center)
                else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                    list.forEachIndexed { i, p ->
                        if (i > 0) Hr()
                        val due = dues[p.id]?.due ?: 0.0
                        val dl = toDel[p.id] ?: 0
                        val pr = toPrint[p.id] ?: 0
                        Row(Modifier.fillMaxWidth().clickable { nav.go(Route("partydetail", p.id)) }.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Av(p.name)
                            Column(Modifier.weight(1f)) {
                                T(p.name, 15, FontWeight.Bold, maxLines = 1)
                                val sub = buildAnnotatedString {
                                    append(p.firstPhone())
                                    if (dl > 0) withStyle(SpanStyle(color = C.warn, fontWeight = FontWeight.Bold)) { append(" · $dl to deliver") }
                                    if (pr > 0) withStyle(SpanStyle(color = C.warn, fontWeight = FontWeight.Bold)) { append(" · $pr to print") }
                                }
                                Text(sub, fontSize = 13.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                T(money(due), 15, FontWeight.Bold, if (due > 0) C.red else C.ok)
                                T(if (due > 0) "due" else "clear", 12, color = C.muted)
                            }
                        }
                    }
                }
                Spacer(Modifier.padding(36.dp))
            }
        }
        Text("+ Create party", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).clip(RoundedCornerShape(50)).background(C.grad)
                .clickable { nav.go(Route("partyedit")) }.padding(horizontal = 20.dp, vertical = 14.dp))
    }
}

/* ---------- one party ---------- */

@Composable
fun PartyDetail(nav: Nav, id: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val parties by Repo.parties.collectAsState()
    val bills by Repo.bills.collectAsState()
    val specials by Repo.specials.collectAsState()
    val products by Repo.products.collectAsState()
    var amount by remember { mutableStateOf("") }
    val p = parties.find { it.id == id } ?: run { Header("Party", "", { nav.back() }); return }
    val mine = bills.filter { it.partyId == id }
    val d = duesOf(listOf(p), bills)[p.id]
    val sp = specials.filter { it.partyId == id }
    val names = products.associateBy { it.id }
    Column(Modifier.fillMaxSize()) {
        Header(p.name, p.phoneList().joinToString(", ") { it.first }, { nav.back() }) {
            T("Edit", 15, FontWeight.Bold, C.violet, Modifier.clickable { nav.go(Route("partyedit", p.id)) }.padding(8.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            CardBox(gap = 8.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("To collect")
                        T(money(d?.due ?: 0.0), 24, FontWeight.Bold, if ((d?.due ?: 0.0) > 0) C.red else C.ok)
                    }
                    if ((d?.overdue ?: 0.0) > 0) Column(horizontalAlignment = Alignment.End) {
                        Eyebrow("Overdue", C.warn); T(money(d?.overdue ?: 0.0), 16, FontWeight.Bold, C.warn)
                    }
                }
                if ((d?.due ?: 0.0) > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T("Money received", 14, color = C.muted, modifier = Modifier.weight(1f))
                    Cell(amount, { amount = it }, 110.dp, hint = "₹")
                    Btn("Save", {
                        val a = amount.toDoubleOrNull() ?: 0.0
                        if (a <= 0) toast(ctx, "Enter the amount") else scope.launch { Repo.receive(p.id, a); amount = ""; toast(ctx, "Saved against his oldest bills") }
                    }, kind = "dark", small = true)
                }
            }
            CardBox(Modifier.clickable { nav.go(Route("partyedit", p.id)) }, gap = 6.dp) {
                Eyebrow("Special price")
                if (p.flat <= 0 && sp.isEmpty()) T("Standard price on all products", 14, color = C.muted)
                if (p.flat > 0) T("All products: ${qtyStr(p.flat)}% off", 14, FontWeight.SemiBold)
                sp.forEach { s ->
                    val pr = names[s.productId] ?: return@forEach
                    Row {
                        T(pr.name, 14, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        T(Repo.rateNote(p.flat, s) + "  " + money(Repo.rateFor(pr, p.flat, s)), 14, color = C.violetD)
                    }
                }
                T((if (p.gst) "GST tax invoice" else "Simple bill") + " · credit ${p.credit} days", 12, color = C.muted)
            }
            if (mine.isNotEmpty()) {
                Eyebrow("Bills")
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                    mine.take(100).forEachIndexed { i, b -> if (i > 0) Hr(); BillRow(b, p.name, nav) }
                }
            }
        }
    }
}

/* ---------- create and edit party ---------- */

@Composable
fun PartyEdit(nav: Nav, id: Long, extra: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val products by Repo.products.collectAsState()
    var name by remember { mutableStateOf("") }
    val phones = remember { mutableStateListOf<Pair<String, String>>() }
    var gst by remember { mutableStateOf(false) }
    var gstin by remember { mutableStateOf("") }
    var credit by remember { mutableStateOf("15") }
    var flat by remember { mutableStateOf("") }
    val specials = remember { mutableStateListOf<Special>() }
    var showSp by remember { mutableStateOf(false) }
    var showOther by remember { mutableStateOf(false) }
    var picking by remember { mutableLongStateOf(-2L) }
    var pickType by remember { mutableStateOf("rate") }
    var pickVal by remember { mutableStateOf("") }
    var spSearch by remember { mutableStateOf("") }
    var contactRow by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val orderId = extra.split(":").getOrNull(1)?.toLongOrNull()

    LaunchedEffect(id) {
        if (id > 0) {
            val p = Repo.dao.party(id) ?: return@LaunchedEffect
            name = p.name; gst = p.gst; gstin = p.gstin; credit = p.credit.toString(); flat = if (p.flat > 0) qtyStr(p.flat) else ""
            phones.clear(); phones.addAll(p.phoneList())
            specials.clear(); specials.addAll(Repo.dao.specials(id))
        } else {
            phones.clear(); phones.add((extra.split(":").getOrNull(2) ?: "") to "Owner")
        }
        if (phones.isEmpty()) phones.add("" to "Owner")
    }

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val num = c.getString(0) ?: ""
                    val nm = c.getString(1) ?: ""
                    if (contactRow < phones.size) phones[contactRow] = num.filter { it.isDigit() || it == '+' } to phones[contactRow].second
                    if (name.isBlank()) name = nm
                }
            }
        } catch (e: Exception) { toast(ctx, "Could not read that contact") }
    }

    val flatD = flat.toDoubleOrNull() ?: 0.0
    val spSummary = listOfNotNull(
        if (flatD > 0) "${qtyStr(flatD)}% off everything" else null,
        if (specials.isNotEmpty()) "${specials.size} product${if (specials.size == 1) "" else "s"}" else null
    ).joinToString(" · ").ifBlank { "Standard price on all products" }

    fun save() {
        if (saving) return
        val nm = name.trim()
        if (nm.isEmpty()) { toast(ctx, "Party name is needed"); return }
        val ph = phones.filter { it.first.isNotBlank() }.map { it.first.trim() to it.second.trim().ifBlank { "Other" } }
        if (ph.isEmpty()) { toast(ctx, "At least one mobile number is needed. That is how calls are matched."); return }
        saving = true
        scope.launch {
            val others = Repo.dao.parties().filter { it.id != id }
            val clash = others.firstOrNull { o -> o.phoneList().any { a -> ph.any { last10(it.first) == last10(a.first) && last10(a.first).length == 10 } } }
            if (clash != null) { saving = false; toast(ctx, "This number is already saved under ${clash.name}"); return@launch }
            val newId = Repo.dao.saveParty(Party(id = if (id > 0) id else 0, name = nm, phones = ph.joinToString(";") { "${it.first}|${it.second}" },
                gst = gst, gstin = if (gst) gstin.trim() else "", credit = credit.toIntOrNull() ?: 0, flat = flatD))
            Repo.dao.clearSpecials(newId)
            Repo.dao.saveSpecials(specials.map { it.copy(partyId = newId) })
            Repo.note("Party ${if (id > 0) "updated" else "added"}: $nm")
            if (orderId != null) Pipeline.partyAdded(orderId, newId)
            saving = false
            toast(ctx, "Saved")
            nav.back()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(if (id > 0) "Edit party" else "Create party", "", { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            CardBox(gap = 12.dp) {
                Field(name, { name = it }, label = "Party name", hint = "Sharma Restaurant")
                T("Mobile numbers", 13, FontWeight.SemiBold, C.muted)
                phones.forEachIndexed { i, ph ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Field(ph.first, { phones[i] = it to ph.second }, Modifier.weight(1f), hint = "Mobile number", number = true)
                        Btn("☎", { contactRow = i; pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }, kind = "sec", small = true)
                        Field(ph.second, { phones[i] = ph.first to it }, Modifier.width(88.dp), hint = "Owner")
                        if (phones.size > 1) T("×", 20, FontWeight.Bold, C.muted, Modifier.clickable { phones.removeAt(i) }.padding(4.dp))
                    }
                }
                Btn("+ Add another number", { phones.add("" to "") }, kind = "sec", small = true)
                T("A restaurant calls from the owner's phone one night and the kitchen the next. Save both, or the second call comes in as an unknown number.", 12, color = C.muted)
            }

            Acc("Special price", spSummary, showSp, { showSp = !showSp; picking = -2; spSearch = "" }) {
                val byId = products.associateBy { it.id }
                if (flatD > 0) SpecialRow("All products", "${qtyStr(flatD)}% off the standard price", "",
                    { picking = -1; pickType = "disc"; pickVal = flat }, { flat = "" })
                specials.forEach { s ->
                    val p = byId[s.productId] ?: return@forEach
                    SpecialRow(p.name, Repo.rateNote(flatD, s) + " · standard ${money(p.sale)}", money(Repo.rateFor(p, flatD, s)),
                        { picking = p.id; pickType = s.type; pickVal = qtyStr(s.value) }, { specials.removeAll { it.productId == p.id } })
                }
                if (picking != -2L) {
                    val all = picking == -1L
                    val p = byId[picking]
                    val v = pickVal.toDoubleOrNull() ?: 0.0
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(2.dp, C.violet, RoundedCornerShape(14.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        T(if (all) "All products" else (p?.name ?: "") + "  ·  standard ${money(p?.sale ?: 0.0)} per ${p?.unit ?: ""}", 15, FontWeight.Bold)
                        if (!all) Seg(listOf("rate" to "Own rate", "disc" to "% off"), pickType, { pickType = it; pickVal = if (it == "rate") qtyStr(p?.sale ?: 0.0) else "5" }, dark = true)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(pickVal, { pickVal = it }, Modifier.width(120.dp), number = true)
                            T(if (all || pickType == "disc") "% off" else "per ${p?.unit ?: ""}", 14, color = C.muted)
                        }
                        T(if (all) "He pays ${qtyStr(v)}% less on every product, unless a product has its own price."
                        else "He will pay ${money(if (pickType == "rate") v else r2((p?.sale ?: 0.0) * (1 - v / 100)))} per ${p?.unit ?: ""}", 13, FontWeight.SemiBold, C.violetD)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Btn("Cancel", { picking = -2 }, Modifier.width(100.dp), "sec")
                            Btn("Add", {
                                if (v <= 0) { toast(ctx, "Enter a value above zero"); return@Btn }
                                if ((all || pickType == "disc") && v >= 100) { toast(ctx, "Discount must be below 100%"); return@Btn }
                                if (all) flat = qtyStr(v)
                                else { specials.removeAll { it.productId == picking }; specials.add(Special(id, picking, pickType, v)) }
                                picking = -2; spSearch = ""
                            }, Modifier.weight(1f))
                        }
                    }
                } else {
                    Eyebrow("Add special price for")
                    Field(spSearch, { spSearch = it }, hint = "Search product")
                    val q = spSearch.trim().lowercase()
                    val opts = products.filter { p -> specials.none { it.productId == p.id } && (q.isEmpty() || p.name.lowercase().contains(q) || p.aliasList().any { it.contains(q) }) }
                        .take(if (q.isEmpty()) 5 else 10)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp))) {
                        if (flatD <= 0 && q.isEmpty()) {
                            PickRow("All products", "One flat % off on everything") { picking = -1; pickType = "disc"; pickVal = "5" }
                            Hr()
                        }
                        opts.forEachIndexed { i, p ->
                            if (i > 0) Hr()
                            PickRow(p.name, "${money(p.sale)} per ${p.unit}") { picking = p.id; pickType = "rate"; pickVal = qtyStr(p.sale) }
                        }
                        if (opts.isEmpty()) T(if (products.isEmpty()) "Add products first." else "No product matches.", 13, color = C.muted, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            Acc("Other settings", (if (gst) "GST tax invoice" else "Simple bill") + " · credit ${credit.ifBlank { "0" }} days", showOther, { showOther = !showOther }) {
                CheckRow("Raise GST tax invoice for this party", gst) { gst = it }
                if (gst) Field(gstin, { gstin = it }, label = "GSTIN", hint = "06ABCDE1234F1Z5")
                Field(credit, { credit = it.filter { c -> c.isDigit() } }, label = "Credit days", hint = "15", number = true)
                T("Credit days decide when his bill shows as overdue.", 12, color = C.muted)
            }
            Btn(if (saving) "Saving…" else if (id > 0) "Save changes" else "Create party", { save() }, Modifier.fillMaxWidth())
            Gap(10)
        }
    }
}

@Composable
private fun SpecialRow(title: String, sub: String, pay: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) { T(title, 15, FontWeight.Bold); T(sub, 12, color = C.muted) }
        if (pay.isNotEmpty()) T(pay, 15, FontWeight.Bold, C.violetD)
        T("Edit", 14, FontWeight.Bold, C.violet, Modifier.clickable { onEdit() }.padding(4.dp))
        T("×", 20, FontWeight.Bold, C.muted, Modifier.clickable { onDelete() }.padding(4.dp))
    }
}

@Composable
private fun PickRow(title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(title, 15, FontWeight.Bold); T(sub, 12, color = C.muted) }
        T("+", 20, FontWeight.Bold, C.violet)
    }
}

/* ---------- money to collect, with the route order ---------- */

@Composable
fun Pending(nav: Nav) {
    val parties by Repo.parties.collectAsState()
    val bills by Repo.bills.collectAsState()
    val ch by Repo.prefs.changes.collectAsState()
    var mode by remember { mutableStateOf("route") }
    val dues = duesOf(parties, bills).values.filter { it.due > 0 }
    val order = if (ch >= 0) Repo.collectOrder() else emptyList()
    val sorted = if (mode == "route") dues.sortedWith(compareBy<Due> { order.indexOf(it.party.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenByDescending { it.due })
    else dues.sortedByDescending { it.due }
    Column(Modifier.fillMaxSize()) {
        Header("To collect", "${sorted.size} parties · ${money(sorted.sumOf { it.due })}", { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Seg(listOf("route" to "My route", "due" to "Highest due"), mode, { mode = it }, dark = true)
            if (mode == "route") T("Hold ≡ and drag a party to match the collection route. The order is saved.", 13, color = C.muted)
            if (sorted.isEmpty()) T("Nobody owes you anything right now.", 14, color = C.muted, modifier = Modifier.fillMaxWidth().padding(20.dp), align = TextAlign.Center)
            else if (mode == "route") RouteList(sorted, nav)
            else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                sorted.forEachIndexed { i, d -> if (i > 0) Hr(); DueRow(d, nav, null, 0) }
            }
        }
    }
}

@Composable
private fun DueRow(d: Due, nav: Nav, handle: Modifier?, seq: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (handle != null) {
            Icon(Icons.Filled.DragHandle, "Drag to reorder", tint = C.muted, modifier = handle.padding(2.dp))
            T("$seq", 12, FontWeight.Bold, C.violet, Modifier.width(16.dp), align = TextAlign.Center)
        }
        Row(Modifier.weight(1f).clickable { nav.go(Route("pendparty", d.party.id)) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Av(d.party.name)
            Column(Modifier.weight(1f)) {
                T(d.party.name, 15, FontWeight.Bold, maxLines = 1)
                val sub = buildAnnotatedString {
                    append("${d.open} bill${if (d.open == 1) "" else "s"}")
                    if (d.oldestDays > 0) append(" · oldest ${d.oldestDays} days")
                    if (d.overdue > 0) withStyle(SpanStyle(color = C.warn, fontWeight = FontWeight.Bold)) { append(" · overdue") }
                }
                Text(sub, fontSize = 12.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            T(money(d.due), 15, FontWeight.Bold, C.red)
        }
    }
}

@Composable
private fun RouteList(items: List<Due>, nav: Nav) {
    var list by remember(items.map { it.party.id }) { mutableStateOf(items) }
    var dragId by remember { mutableLongStateOf(-1L) }
    var offset by remember { mutableFloatStateOf(0f) }
    var rowH by remember { mutableFloatStateOf(1f) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
        list.forEachIndexed { i, d ->
            key(d.party.id) {
                val dragging = dragId == d.party.id
                val handle = Modifier.pointerInput(d.party.id) {
                    detectDragGestures(
                        onDragStart = { dragId = d.party.id; offset = 0f },
                        onDragEnd = { dragId = -1L; offset = 0f; Repo.setCollectOrder(list.map { it.party.id }) },
                        onDragCancel = { dragId = -1L; offset = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            offset += amount.y
                            val idx = list.indexOfFirst { it.party.id == d.party.id }
                            if (offset > rowH / 2 && idx < list.lastIndex) {
                                list = list.toMutableList().also { java.util.Collections.swap(it, idx, idx + 1) }; offset -= rowH
                            } else if (offset < -rowH / 2 && idx > 0) {
                                list = list.toMutableList().also { java.util.Collections.swap(it, idx, idx - 1) }; offset += rowH
                            }
                        }
                    )
                }
                Column(Modifier.fillMaxWidth().onSizeChanged { rowH = it.height.toFloat().coerceAtLeast(1f) }
                    .zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = if (dragging) offset else 0f }
                    .background(if (dragging) C.soft else Color.White)) {
                    if (i > 0) Hr()
                    DueRow(d, nav, handle, i + 1)
                }
            }
        }
    }
}

@Composable
fun PendingParty(nav: Nav, id: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val parties by Repo.parties.collectAsState()
    val bills by Repo.bills.collectAsState()
    var amount by remember { mutableStateOf("") }
    val p = parties.find { it.id == id } ?: run { Header("Party", "", { nav.back() }); return }
    val d = duesOf(listOf(p), bills)[p.id]
    val open = bills.filter { it.partyId == id && it.isOpen() }.sortedBy { it.iso }
    Column(Modifier.fillMaxSize()) {
        Header(p.name, p.firstPhone() + " · credit ${p.credit} days", { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardBox(gap = 8.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Eyebrow("Pending"); T(money(d?.due ?: 0.0), 24, FontWeight.Bold, C.red) }
                    if ((d?.overdue ?: 0.0) > 0) Column(horizontalAlignment = Alignment.End) { Eyebrow("Overdue", C.warn); T(money(d?.overdue ?: 0.0), 16, FontWeight.Bold, C.warn) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T("Money received", 14, color = C.muted, modifier = Modifier.weight(1f))
                    Cell(amount, { amount = it }, 110.dp, hint = "₹")
                    Btn("Save", {
                        val a = amount.toDoubleOrNull() ?: 0.0
                        if (a <= 0) toast(ctx, "Enter the amount") else scope.launch { Repo.receive(p.id, a); amount = ""; toast(ctx, "Saved against his oldest bills") }
                    }, kind = "dark", small = true)
                }
            }
            if (open.isEmpty()) T("No open bill.", 14, color = C.muted)
            else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                open.forEachIndexed { i, b ->
                    if (i > 0) Hr()
                    val age = daysAgo(b.iso)
                    Row(Modifier.fillMaxWidth().clickable { nav.go(Route("bill", b.id)) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T("Bill no. ${b.no}", 15, FontWeight.Bold)
                            val sub = buildAnnotatedString {
                                append(fmtD(b.iso) + " · " + when { age > 0 -> "$age days"; age == 0L -> "today"; else -> "future date" })
                                if (age > p.credit) withStyle(SpanStyle(color = C.warn, fontWeight = FontWeight.Bold)) { append(" · overdue") }
                                if (b.paid > 0) append(" · paid ${money(b.paid)} of ${money(b.total)}")
                            }
                            Text(sub, fontSize = 12.sp, color = C.muted)
                        }
                        T(money(b.leftToPay()), 15, FontWeight.Bold, C.red)
                    }
                }
            }
            T("Tap a bill to see it.", 13, color = C.muted, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
        }
    }
}
