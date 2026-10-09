package com.parchi.billing

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

class ParchiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}

/* ---------- small helpers ---------- */

fun r2(v: Double): Double = (v * 100).roundToLong() / 100.0

fun money(v: Double): String {
    val r = r2(v)
    val f = NumberFormat.getNumberInstance(Locale("en", "IN"))
    f.maximumFractionDigits = 2
    f.minimumFractionDigits = if (abs(r % 1.0) < 0.001) 0 else 2
    return "₹ " + f.format(r)
}

fun qtyStr(d: Double): String = if (abs(d % 1.0) < 0.0001) d.toLong().toString() else r2(d).toString()

fun today(): String = LocalDate.now().toString()

fun shiftDay(iso: String, n: Long): String = LocalDate.parse(iso).plusDays(n).toString()

fun fmtD(iso: String, year: Boolean = false): String = try {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern(if (year) "dd MMM yyyy" else "dd MMM", Locale.ENGLISH))
} catch (e: Exception) { iso }

fun daysAgo(iso: String): Long = try { ChronoUnit.DAYS.between(LocalDate.parse(iso), LocalDate.now()) } catch (e: Exception) { 0 }

fun clock(ms: Long): String = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase()

fun last10(n: String): String = n.filter { it.isDigit() }.takeLast(10)

fun unitShort(u: String): String = when (u) {
    "gram" -> "g"; "litre" -> "L"; "piece" -> "pc"; "dozen" -> "dz"; "packet" -> "pkt"; else -> u
}

val UNITS = listOf("kg", "gram", "litre", "ml", "piece", "box", "dozen", "packet")

/* ---------- settings ---------- */

class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("parchi", Context.MODE_PRIVATE)
    val changes = MutableStateFlow(0)
    fun s(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun b(k: String, d: Boolean = false): Boolean = sp.getBoolean(k, d)
    fun l(k: String, d: Long = 0L): Long = sp.getLong(k, d)
    fun put(k: String, v: Any) {
        val e = sp.edit()
        when (v) {
            is String -> e.putString(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Long -> e.putLong(k, v)
            is Int -> e.putInt(k, v)
        }
        e.apply()
        changes.value = changes.value + 1
    }
}

/* ---------- bill draft maths ---------- */

data class Line(
    val productId: Long,
    val name: String,
    val unit: String,
    val qty: String,
    val rate: String,
    val disc: String = "",
    val tax: Int = 0,
    val src: String = "",
    val showDisc: Boolean = false
)

data class Totals(val sub: Double, val billDisc: Double, val tax: Double, val total: Double, val amts: List<Double>)

fun calc(lines: List<Line>, billDiscPct: Double, gst: Boolean): Totals {
    val amts = lines.map { l ->
        val gross = (l.qty.toDoubleOrNull() ?: 0.0) * (l.rate.toDoubleOrNull() ?: 0.0)
        r2(gross - gross * (l.disc.toDoubleOrNull() ?: 0.0) / 100)
    }
    val sub = r2(amts.sum())
    val bd = r2(sub * billDiscPct / 100)
    val after = sub - bd
    var tax = 0.0
    if (gst && sub > 0) lines.forEachIndexed { i, l -> tax += after * (amts[i] / sub) * l.tax / 100 }
    tax = r2(tax)
    return Totals(sub, bd, tax, r2(after + tax), amts)
}

data class BillInput(
    val partyId: Long,
    val lines: List<Line>,
    val billDiscPct: Double,
    val received: Double,
    val iso: String,
    val print: Boolean,
    val callId: Long? = null,
    val editId: Long? = null
)

data class Due(val party: Party, val due: Double, val overdue: Double, val open: Int, val oldestDays: Long)

fun duesOf(parties: List<Party>, bills: List<Bill>): Map<Long, Due> {
    val byParty = bills.filter { it.isOpen() }.groupBy { it.partyId }
    return parties.associate { p ->
        val ob = byParty[p.id].orEmpty()
        var due = 0.0; var od = 0.0; var oldest = 0L
        ob.forEach { b ->
            val left = b.leftToPay(); val age = daysAgo(b.iso)
            due += left; if (age > p.credit) od += left; if (age > oldest) oldest = age
        }
        p.id to Due(p, r2(due), r2(od), ob.size, oldest)
    }
}

/* ---------- the app's brain ---------- */

object Repo {
    lateinit var app: Context
    lateinit var db: Db
    lateinit var dao: Store
    lateinit var prefs: Prefs
    val log = MutableStateFlow<List<String>>(emptyList())
    val printerState = MutableStateFlow("")
    val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /* live lists kept in memory, so every screen reads the same copy */
    val bills: StateFlow<List<Bill>> by lazy { dao.billsFlow().stateIn(scope, SharingStarted.Eagerly, emptyList()) }
    val parties: StateFlow<List<Party>> by lazy { dao.partiesFlow().stateIn(scope, SharingStarted.Eagerly, emptyList()) }
    val products: StateFlow<List<Product>> by lazy { dao.productsFlow().stateIn(scope, SharingStarted.Eagerly, emptyList()) }
    val orders: StateFlow<List<CallOrder>> by lazy { dao.ordersFlow().stateIn(scope, SharingStarted.Eagerly, emptyList()) }
    val specials: StateFlow<List<Special>> by lazy { dao.specialsFlow().stateIn(scope, SharingStarted.Eagerly, emptyList()) }
    val queue: StateFlow<Int> by lazy { dao.queueCount().stateIn(scope, SharingStarted.Eagerly, 0) }

    fun init(c: Context) {
        if (::db.isInitialized) return
        app = c.applicationContext
        db = Db.get(app)
        dao = db.store()
        prefs = Prefs(app)
    }

    fun note(msg: String) {
        val t = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        log.value = (listOf("$t  $msg") + log.value).take(300)
    }

    fun startService(c: Context) {
        val i = Intent(c, ParchiService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        } catch (e: Exception) {
            note("Could not start background service: ${e.message}")
        }
    }

    /* prices */
    fun rateFor(p: Product, flat: Double, sp: Special?): Double = when {
        sp != null && sp.type == "rate" -> sp.value
        sp != null -> r2(p.sale * (1 - sp.value / 100))
        flat > 0 -> r2(p.sale * (1 - flat / 100))
        else -> p.sale
    }

    fun rateNote(flat: Double, sp: Special?): String = when {
        sp != null && sp.type == "rate" -> "special price"
        sp != null -> "${qtyStr(sp.value)}% off"
        flat > 0 -> "${qtyStr(flat)}% off all"
        else -> "standard"
    }

    suspend fun priceFor(party: Party, p: Product): Pair<Double, String> {
        if (prefs.b("lastPrice", true)) dao.last(party.id, p.id)?.let { return it.rate to "last price" }
        val sp = dao.specials(party.id).find { it.productId == p.id }
        return rateFor(p, party.flat, sp) to rateNote(party.flat, sp)
    }

    /* what this party usually takes, from his last bills */
    suspend fun suggest(partyId: Long): List<Pair<Product, Double>> {
        val products = dao.products().associateBy { it.id }
        val items = dao.recentItems(partyId)
        val lastBills = items.map { it.billId }.distinct().take(6).toSet()
        val byProduct = items.filter { it.billId in lastBills }.groupBy { it.productId }
        val list = byProduct.entries.sortedByDescending { it.value.size }.mapNotNull { e ->
            val p = products[e.key] ?: return@mapNotNull null
            val mode = e.value.groupingBy { it.qty }.eachCount().maxByOrNull { it.value }?.key ?: 1.0
            p to mode
        }.toMutableList()
        if (list.size < 6) {
            dao.soldTotals().sortedByDescending { it.q }.forEach { s ->
                val p = products[s.productId]
                if (list.size < 6 && p != null && list.none { it.first.id == p.id }) list.add(p to 1.0)
            }
            products.values.forEach { p -> if (list.size < 6 && list.none { it.first.id == p.id }) list.add(p to 1.0) }
        }
        return list.take(6)
    }

    /* money in: settle oldest open bills first */
    suspend fun applyPayment(partyId: Long, amount: Double, skipBill: Long = -1): Double {
        var left = r2(amount)
        for (b in dao.openBills(partyId)) {
            if (left <= 0) break
            if (b.id == skipBill) continue
            val pay = min(r2(b.total - b.paid), left)
            val paid = r2(b.paid + pay)
            left = r2(left - pay)
            dao.updateBill(b.copy(paid = paid, status = if (paid >= b.total - 0.001) "paid" else "part"))
        }
        if (left > 0.5) note("${money(left)} extra received, kept as advance")
        return left
    }

    suspend fun receive(partyId: Long, amount: Double) {
        db.withTransaction { applyPayment(partyId, amount) }
        note("${money(amount)} received from ${dao.party(partyId)?.name}")
    }

    /* make or edit a bill. Returns bill id, or an error message */
    suspend fun saveBill(inp: BillInput): Pair<Long?, String?> {
        val result: Pair<Long?, String?> = db.withTransaction<Pair<Long?, String?>> {
            val party = dao.party(inp.partyId) ?: return@withTransaction null to "Party not found"
            val lines = inp.lines.filter { (it.qty.toDoubleOrNull() ?: 0.0) > 0 }
            if (lines.isEmpty()) return@withTransaction null to "Add at least one item with quantity"
            val t = calc(lines, inp.billDiscPct, party.gst)
            val sig = lines.sortedBy { it.productId }.joinToString(";") { "${it.productId}:${it.qty}" }
            var old: Bill? = null
            if (inp.editId == null) {
                if (inp.callId != null) dao.billByCall(inp.callId)?.let { return@withTransaction it.id to "This call is already billed as no. ${it.no}" }
                dao.twin(party.id, sig, inp.iso)?.let { return@withTransaction null to "Same order already billed today as no. ${it.no}" }
            } else {
                old = dao.bill(inp.editId)
                if (old != null) {
                    dao.items(old.id).forEach { dao.addStock(it.productId, it.qty) }
                    dao.clearItems(old.id)
                }
            }
            val no = old?.no ?: ((dao.maxNo() ?: 1000) + 1)
            val paid = min(inp.received, t.total)
            val bill = Bill(
                id = old?.id ?: 0, no = no, partyId = party.id, iso = inp.iso,
                createdAt = old?.createdAt ?: System.currentTimeMillis(),
                sub = t.sub, billDisc = t.billDisc, billDiscPct = inp.billDiscPct, tax = t.tax, total = t.total,
                paid = paid, status = if (paid >= t.total - 0.001) "paid" else if (paid > 0) "part" else "open",
                printWanted = inp.print || (old?.printWanted ?: false),
                printed = if (inp.print) false else (old?.printed ?: false),
                delivered = old?.delivered ?: false,
                auto = old?.auto ?: (inp.callId != null),
                callId = old?.callId ?: inp.callId,
                gst = party.gst, sig = sig
            )
            val id = if (old != null) { dao.updateBill(bill); old.id } else dao.insertBill(bill)
            dao.insertItems(lines.mapIndexed { i, l ->
                BillItem(billId = id, productId = l.productId, name = l.name, unit = l.unit,
                    qty = l.qty.toDoubleOrNull() ?: 0.0, rate = l.rate.toDoubleOrNull() ?: 0.0,
                    disc = l.disc.toDoubleOrNull() ?: 0.0, tax = if (party.gst) l.tax else 0, amt = t.amts[i])
            })
            lines.forEach { l ->
                dao.addStock(l.productId, -(l.qty.toDoubleOrNull() ?: 0.0))
                dao.saveLast(LastPrice(party.id, l.productId, l.rate.toDoubleOrNull() ?: 0.0))
            }
            if (inp.received > t.total) applyPayment(party.id, r2(inp.received - t.total), id)
            note("Bill no. $no ${if (old != null) "updated" else "made"} for ${party.name}, ${money(t.total)}" +
                if (inp.iso != today()) ", dated ${fmtD(inp.iso)}" else "")
            id to null
        }
        if (result.first != null && inp.print) wake.tryEmit(Unit)
        return result
    }

    suspend fun cancelBill(id: Long) {
        db.withTransaction {
            val b = dao.bill(id) ?: return@withTransaction
            if (b.isCancelled()) return@withTransaction
            dao.items(id).forEach { dao.addStock(it.productId, it.qty) }
            dao.updateBill(b.copy(status = "cancelled"))
            if (b.paid > 0) applyPayment(b.partyId, b.paid)
            note("Bill no. ${b.no} cancelled, stock returned")
        }
    }

    suspend fun toggleDelivered(id: Long) {
        val b = dao.bill(id) ?: return
        dao.updateBill(b.copy(delivered = !b.delivered))
        note("Bill no. ${b.no} ${if (b.delivered) "marked not delivered" else "delivered"}")
    }

    suspend fun printAgain(id: Long) {
        val b = dao.bill(id) ?: return
        dao.updateBill(b.copy(printWanted = true, printed = false))
        wake.tryEmit(Unit)
    }

    suspend fun learnWord(productId: Long, word: String) {
        val p = dao.product(productId) ?: return
        val w = word.trim().lowercase()
        if (w.isEmpty() || p.aliasList().contains(w)) return
        dao.setAliases(productId, (p.aliasList() + w).joinToString(","))
        note("Learned: \"$w\" means ${p.name}")
    }

    fun collectOrder(): List<Long> = prefs.s("collectOrder").split(",").mapNotNull { it.toLongOrNull() }
    fun setCollectOrder(ids: List<Long>) = prefs.put("collectOrder", ids.joinToString(","))

    fun billedToday(bills: List<Bill>): Set<Long> {
        val t = today()
        return bills.filter { it.iso == t && !it.isCancelled() }.map { it.partyId }.toSet()
    }

    fun partyByPhone(parties: List<Party>, phone: String): Party? {
        val k = last10(phone)
        if (k.length < 10) return null
        return parties.firstOrNull { p -> p.phoneList().any { last10(it.first) == k } }
    }
}
