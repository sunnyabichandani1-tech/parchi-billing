package com.parchi.billing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.CallLog
import android.provider.DocumentsContract
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** One item read from a call. pid is -1 until a product is matched. */
data class OItem(
    val spoken: String,
    val pid: Long,
    val qty: Double,
    val unit: String,
    val sure: Boolean,
    val cands: List<Long> = emptyList(),
    val learned: Boolean = false,
    val picked: Boolean = false
)

fun itemsFrom(json: String): List<OItem> = try {
    val a = JSONArray(json)
    (0 until a.length()).map { i ->
        val j = a.getJSONObject(i)
        val c = j.optJSONArray("cands") ?: JSONArray()
        OItem(j.optString("spoken"), j.optLong("pid", -1), j.optDouble("qty", 0.0), j.optString("unit"),
            j.optBoolean("sure"), (0 until c.length()).map { c.getLong(it) }, j.optBoolean("learned"), j.optBoolean("picked"))
    }
} catch (e: Exception) { emptyList() }

fun itemsTo(list: List<OItem>): String {
    val a = JSONArray()
    list.forEach { o ->
        a.put(JSONObject().put("spoken", o.spoken).put("pid", o.pid).put("qty", o.qty).put("unit", o.unit)
            .put("sure", o.sure).put("cands", JSONArray(o.cands)).put("learned", o.learned).put("picked", o.picked))
    }
    return a.toString()
}

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

object Pipeline {
    private val AUDIO = setOf("m4a", "mp3", "amr", "aac", "wav", "ogg", "3gp", "opus", "awb", "flac", "mp4")
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS).build()
    private val lock = Mutex()

    private data class Doc(val uri: Uri, val name: String, val modified: Long, val mime: String, val ext: String)

    suspend fun scan(c: Context) {
        lock.withLock { withContext(Dispatchers.IO) { scanInner(c) } }
    }

    private suspend fun scanInner(c: Context) {
        val tree = Repo.prefs.s("folder")
        if (tree.isBlank()) return
        val treeUri = Uri.parse(tree)
        val root = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (e: Exception) { return }
        val now = System.currentTimeMillis()
        val from = Repo.prefs.l("scanFrom", 0L).let { if (it == 0L) { Repo.prefs.put("scanFrom", now); now } else it }
        val docs = mutableListOf<Doc>()
        try { collect(c, treeUri, root, docs, 0) } catch (e: Exception) {
            Repo.note("Cannot read the recording folder. Pick it again in Settings.")
            return
        }
        val fresh = docs.filter { it.modified > from && now - it.modified > 15_000 && it.ext in AUDIO }.sortedBy { it.modified }
        for (d in fresh) {
            val key = d.uri.toString()
            if (Repo.dao.orderByKey(key) != null) continue
            val id = Repo.dao.insertOrder(CallOrder(fileKey = key, fileName = d.name, mime = d.mime, callAt = d.modified))
            if (id > 0) {
                Repo.note("New recording found: ${d.name}")
                process(c, id)
            }
        }
        Repo.dao.readingOrders().forEach { if (now - it.callAt > 60_000) process(c, it.id) }
    }

    private fun collect(c: Context, treeUri: Uri, docId: String, out: MutableList<Doc>, depth: Int) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        c.contentResolver.query(children, cols, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(0) ?: continue
                val name = cur.getString(1) ?: ""
                val mod = cur.getLong(2)
                val mime = cur.getString(3) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < 2) collect(c, treeUri, id, out, depth + 1)
                } else {
                    out += Doc(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, mod, mime, name.substringAfterLast('.', "").lowercase())
                }
            }
        }
    }

    private fun phoneFromName(name: String): String? {
        val clean = name.replace(" ", "").replace("-", "")
        return Regex("(?<!\\d)(?:\\+?91)?([6-9]\\d{9})(?!\\d)").find(clean)?.groupValues?.get(1)
    }

    private fun fromCallLog(c: Context, fileTime: Long, secs: Int): String? {
        if (c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        val from = fileTime - (secs + 900) * 1000L
        val to = fileTime + 180_000L
        return try {
            c.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()), "${CallLog.Calls.DATE} DESC"
            )?.use { cur ->
                var best: String? = null
                var gap = Long.MAX_VALUE
                while (cur.moveToNext()) {
                    val num = cur.getString(0) ?: continue
                    val end = cur.getLong(1) + cur.getLong(2) * 1000
                    val g = abs(end - fileTime)
                    if (g < gap) { gap = g; best = num }
                }
                best
            }
        } catch (e: Exception) { null }
    }

    private fun durationSecs(c: Context, uri: Uri): Int = try {
        val m = MediaMetadataRetriever()
        m.setDataSource(c, uri)
        val d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        try { m.release() } catch (e: Exception) { }
        (d / 1000).toInt()
    } catch (e: Exception) { 0 }

    private fun mimesFor(o: CallOrder): List<String> = when (o.fileName.substringAfterLast('.', "").lowercase()) {
        "mp3" -> listOf("audio/mp3", "audio/mpeg")
        "wav" -> listOf("audio/wav")
        "aac" -> listOf("audio/aac")
        "ogg", "opus" -> listOf("audio/ogg")
        "flac" -> listOf("audio/flac")
        "amr", "awb" -> listOf("audio/amr", "audio/3gpp")
        "3gp" -> listOf("audio/3gpp", "audio/mp4")
        else -> listOf("audio/mp4", "audio/aac", "audio/m4a")
    }

    private fun prompt(products: List<Product>, o: CallOrder): String {
        val t = Instant.ofEpochMilli(o.callAt).atZone(ZoneId.systemDefault())
        val lang = when (Repo.prefs.s("callLang", "auto")) { "hi" -> "Hindi"; "en" -> "English"; "hinglish" -> "Hinglish"; else -> "Hindi, English or Hinglish" }
        val list = products.take(400).joinToString("\n") { p ->
            "${p.id} | ${p.name} | ${p.unit}" + (if (p.aliasList().isNotEmpty()) " | also called: ${p.aliasList().joinToString(", ")}" else "")
        }
        return """
You are reading a phone call recording for a small shop in India. The call is usually in $lang.
Find out if the customer placed an order, and which products and how much.
The call happened on ${t.toLocalDate()} at ${t.toLocalTime().withNano(0)}.

Product list (id | name | unit | other names):
$list

Rules:
- Include only what the customer orders in this call.
- Map each item to a product id from the list. If you are not sure, set product_id to null and put up to 3 likely product ids in candidates.
- qty is a number in the unit the customer said. unit is one of: kg, gram, litre, ml, piece, packet, box, dozen. "aadha kilo" is 0.5 kg, "pav" or "dhai sau gram" is 250 gram, "dedh" is 1.5, "dhai" is 2.5.
- confidence is 0 to 1 for each item.
- If the customer says when to deliver (kal, parson, kal subah, a weekday, a date), give delivery_date as YYYY-MM-DD and delivery_said in the customer's own words. Otherwise both are null.
- transcript is a short transcript of the call in the language spoken.

Reply with JSON only, in this shape:
{"is_order": true, "items": [{"spoken": "paneer paanch kilo", "product_id": 1, "qty": 5, "unit": "kg", "confidence": 0.95, "candidates": []}], "delivery_date": null, "delivery_said": null, "transcript": "..."}
""".trimIndent()
    }

    private suspend fun gemini(audio: ByteArray, mimes: List<String>, prompt: String): JSONObject = withContext(Dispatchers.IO) {
        val key = Repo.prefs.s("geminiKey").trim()
        val model = Repo.prefs.s("geminiModel", "gemini-2.5-flash").trim().ifBlank { "gemini-2.5-flash" }
        val b64 = Base64.encodeToString(audio, Base64.NO_WRAP)
        var lastErr = "Gemini did not answer"
        for (mime in mimes) {
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64))))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1))
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody(JSON_TYPE)).build()
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string() ?: ""
                if (r.isSuccessful) {
                    val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                    val text = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
                    return@withContext JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
                }
                lastErr = "Gemini error ${r.code}: " + try { JSONObject(txt).getJSONObject("error").getString("message") } catch (e: Exception) { txt.take(160) }
                if (r.code != 400) throw IllegalStateException(lastErr)
            }
        }
        throw IllegalStateException(lastErr)
    }

    private fun normUnit(u: String): String = when (u.trim().lowercase()) {
        "g", "gm", "gms", "gram", "grams" -> "gram"
        "kg", "kgs", "kilo", "kilogram", "kilograms" -> "kg"
        "l", "lt", "ltr", "litre", "liter", "litres", "liters" -> "litre"
        "ml" -> "ml"
        "pc", "pcs", "piece", "pieces" -> "piece"
        "dozen", "dz" -> "dozen"
        "packet", "pkt", "packets" -> "packet"
        "box", "dibba", "dabba" -> "box"
        else -> u.trim().lowercase()
    }

    private fun convert(q: Double, u: String, p: Product): Double = when {
        u.isBlank() || u == p.unit -> q
        u == p.unit2 && p.conv > 0 -> q / p.conv
        p.unit == "kg" && u == "gram" -> q / 1000
        p.unit == "litre" && u == "ml" -> q / 1000
        else -> q
    }

    private fun toItem(j: JSONObject, products: List<Product>): OItem {
        val spoken = j.optString("spoken")
        val raw = if (j.isNull("product_id")) -1L else j.optLong("product_id", -1)
        var p = products.find { it.id == raw }
        val conf = j.optDouble("confidence", 0.0)
        var sure = p != null && conf >= 0.75
        var learned = false
        if (!sure) {
            val s = spoken.lowercase()
            val hit = products.firstOrNull { pr -> pr.aliasList().any { a -> a.length >= 3 && s.contains(a) } }
            if (hit != null) { p = hit; sure = true; learned = true }
        }
        val unit = normUnit(j.optString("unit"))
        val qty = r2(if (p != null) convert(j.optDouble("qty", 0.0), unit, p) else j.optDouble("qty", 0.0))
        val ca = j.optJSONArray("candidates") ?: JSONArray()
        val cands = mutableListOf<Long>()
        if (!sure && p != null) cands += p.id
        (0 until ca.length()).forEach { i ->
            val id = ca.optLong(i, -1)
            if (products.any { it.id == id } && id !in cands) cands += id
        }
        return OItem(spoken, if (sure) p!!.id else -1, qty, p?.unit ?: unit, sure, cands.take(3), learned)
    }

    private fun deliveryOf(res: JSONObject, callAt: Long): Pair<String, String> {
        val day = Instant.ofEpochMilli(callAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val d = if (res.isNull("delivery_date")) "" else res.optString("delivery_date")
        val said = if (res.isNull("delivery_said")) "" else res.optString("delivery_said")
        try {
            if (d.isNotBlank()) {
                val ld = LocalDate.parse(d)
                if (!ld.isBefore(day.minusDays(1)) && !ld.isAfter(day.plusDays(30)))
                    return ld.toString() to "Customer said \"${said.ifBlank { d }}\" on the call"
            }
        } catch (e: Exception) { }
        return day.toString() to "No date said, so the call date is used"
    }

    private suspend fun fail(o: CallOrder, msg: String) {
        Repo.dao.updateOrder(o.copy(status = "error", error = msg))
        Repo.note("Call ${o.fileName}: $msg")
    }

    suspend fun process(c: Context, orderId: Long) {
        var o = Repo.dao.order(orderId) ?: return
        try {
            val uri = Uri.parse(o.fileKey)
            val secs = durationSecs(c, uri)
            val parties = Repo.dao.parties()
            val phone = phoneFromName(o.fileName) ?: fromCallLog(c, o.callAt, secs)
            var party = phone?.let { Repo.partyByPhone(parties, it) }
            if (party == null) party = parties.firstOrNull { p -> p.name.length >= 3 && o.fileName.lowercase().contains(p.name.lowercase()) }
            o = o.copy(secs = secs, phone = phone ?: "", partyId = party?.id, status = "reading", error = "")
            Repo.dao.updateOrder(o)
            if (party == null && Repo.prefs.b("onlyParties", true)) {
                Repo.dao.updateOrder(o.copy(status = "skipped"))
                return
            }
            if (Repo.prefs.s("geminiKey").isBlank()) { fail(o, "Add your Gemini key in Settings, then tap Try again"); return }
            val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) { fail(o, "Could not open the recording"); return }
            if (bytes.size > 18_000_000) { fail(o, "Recording is too large to read"); return }
            Repo.note("Reading call from ${party?.name ?: phone ?: o.fileName}")
            val products = Repo.dao.products()
            val res = gemini(bytes, mimesFor(o), prompt(products, o))
            val arr = res.optJSONArray("items") ?: JSONArray()
            val items = if (!res.optBoolean("is_order", true)) emptyList()
            else (0 until arr.length()).map { toItem(arr.getJSONObject(it), products) }.filter { it.qty > 0 }
            val (iso, note) = deliveryOf(res, o.callAt)
            o = o.copy(itemsJson = itemsTo(items), transcript = res.optString("transcript"), deliverIso = iso, dateNote = note)
            decide(c, o, items)
        } catch (e: Exception) {
            fail(o, (e.message ?: "Could not read the call").take(200))
        }
    }

    private suspend fun names(): Map<Long, Product> = Repo.dao.products().associateBy { it.id }

    private fun summary(items: List<OItem>, n: Map<Long, Product>): String = items.joinToString(", ") {
        if (it.sure) "${n[it.pid]?.name} ${qtyStr(it.qty)} ${unitShort(n[it.pid]?.unit ?: it.unit)}" else "\"${it.spoken}\""
    }

    private suspend fun decide(c: Context, start: CallOrder, items: List<OItem>) {
        var o = start
        val party = o.partyId?.let { Repo.dao.party(it) }
        val n = names()
        when {
            items.isEmpty() -> {
                o = o.copy(status = "noorder")
                Repo.note("No order in the call from ${party?.name ?: o.phone.ifBlank { "unknown number" }}")
            }
            party == null -> {
                o = o.copy(status = "noparty")
                Repo.note("Order from a number not in your party list: ${o.phone.ifBlank { "unknown" }}")
                Tg.send("New number ${o.phone.ifBlank { "(unknown)" }} ordered: ${summary(items, n)}.\nAdd this party in the app to bill it.")
            }
            items.any { !it.sure } -> {
                o = o.copy(status = "check")
                Repo.note("Holding the order from ${party.name} for your check")
                Repo.dao.updateOrder(o)
                o = o.copy(tgMsgId = Tg.askPick(o, items, party))
            }
            else -> {
                items.filter { it.learned }.forEach { Repo.note("\"${it.spoken}\" matched from a word you taught") }
                o = bill(o, items, party)
            }
        }
        Repo.dao.updateOrder(o)
    }

    private suspend fun bill(o: CallOrder, items: List<OItem>, party: Party): CallOrder {
        val lines = items.mapNotNull { oi ->
            val p = Repo.dao.product(oi.pid) ?: return@mapNotNull null
            val pr = Repo.priceFor(party, p)
            Line(p.id, p.name, p.unit, qtyStr(oi.qty), qtyStr(pr.first), tax = p.tax, src = pr.second)
        }
        val (id, err) = Repo.saveBill(BillInput(party.id, lines, 0.0, 0.0, o.deliverIso.ifBlank { today() }, print = true, callId = o.id))
        if (id != null && err == null) {
            val b = Repo.dao.bill(id)
            Tg.note("Bill no. ${b?.no} made for ${party.name}, ${money(b?.total ?: 0.0)}. Delivery ${fmtD(o.deliverIso.ifBlank { today() })}.")
            return o.copy(status = "billed", billId = id, error = "")
        }
        if (id != null) return o.copy(status = "billed", billId = id, error = err ?: "")
        return o.copy(status = "check", error = err ?: "Could not make the bill")
    }

    /* actions from the app or Telegram */

    suspend fun approve(orderId: Long, items: List<OItem>, remember: Boolean, iso: String): String? {
        val o = Repo.dao.order(orderId) ?: return "Order not found"
        val party = o.partyId?.let { Repo.dao.party(it) } ?: return "Add the party first"
        if (items.any { !it.sure || it.pid < 0 }) return "Pick a product for every item"
        if (remember) items.filter { it.picked }.forEach { Repo.learnWord(it.pid, it.spoken) }
        val o2 = bill(o.copy(itemsJson = itemsTo(items), deliverIso = iso), items, party)
        Repo.dao.updateOrder(o2)
        if (o2.tgMsgId > 0) Tg.edit(o2.tgMsgId, "Approved in the app. Bill made.")
        return if (o2.status == "billed") null else o2.error
    }

    suspend fun reject(orderId: Long) {
        val o = Repo.dao.order(orderId) ?: return
        Repo.dao.updateOrder(o.copy(status = "rejected"))
        Repo.note("Order rejected, no bill made")
    }

    suspend fun retry(c: Context, orderId: Long) {
        val o = Repo.dao.order(orderId) ?: return
        Repo.dao.updateOrder(o.copy(status = "reading", error = ""))
        lock.withLock { withContext(Dispatchers.IO) { process(c, orderId) } }
    }

    suspend fun partyAdded(orderId: Long, partyId: Long) {
        val o = Repo.dao.order(orderId) ?: return
        val o2 = o.copy(partyId = partyId)
        Repo.dao.updateOrder(o2)
        decide(Repo.app, o2, itemsFrom(o2.itemsJson))
    }

    suspend fun tgPick(orderId: Long, idx: Int, pid: Long): String {
        val o = Repo.dao.order(orderId) ?: return "This order is gone"
        if (o.status != "check") return "Already handled"
        val party = o.partyId?.let { Repo.dao.party(it) } ?: return "Party missing"
        val items = itemsFrom(o.itemsJson).toMutableList()
        val it = items.getOrNull(idx) ?: return "Item not found"
        items[idx] = it.copy(pid = pid, sure = true, picked = true)
        Repo.learnWord(pid, it.spoken)
        val o1 = o.copy(itemsJson = itemsTo(items))
        if (items.any { x -> !x.sure }) {
            Repo.dao.updateOrder(o1)
            Repo.dao.updateOrder(o1.copy(tgMsgId = Tg.askPick(o1, items, party)))
            return "Saved. One more item to check."
        }
        val o2 = bill(o1, items, party)
        Repo.dao.updateOrder(o2)
        return if (o2.status == "billed") "Bill made and sent to the printer" else o2.error
    }
}

object Tg {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    private fun token() = Repo.prefs.s("tgToken").trim()
    private fun chat() = Repo.prefs.s("tgChat")
    fun ready() = token().isNotBlank() && chat().isNotBlank()

    private suspend fun call(method: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        if (token().isBlank()) return@withContext null
        try {
            val req = Request.Builder().url("https://api.telegram.org/bot${token()}/$method")
                .post(body.toString().toRequestBody(JSON_TYPE)).build()
            http.newCall(req).execute().use { r -> JSONObject(r.body?.string() ?: "{}") }
        } catch (e: Exception) { null }
    }

    suspend fun send(text: String, keyboard: JSONArray? = null): Long {
        if (!ready()) return 0
        val b = JSONObject().put("chat_id", chat()).put("text", text)
        if (keyboard != null) b.put("reply_markup", JSONObject().put("inline_keyboard", keyboard))
        return call("sendMessage", b)?.optJSONObject("result")?.optLong("message_id", 0) ?: 0
    }

    /** Messages about bills that need nothing from you. Can be switched off. */
    suspend fun note(text: String) { if (Repo.prefs.b("tgEvery", true)) send(text) }

    suspend fun edit(msgId: Long, text: String) {
        if (!ready() || msgId <= 0) return
        call("editMessageText", JSONObject().put("chat_id", chat()).put("message_id", msgId).put("text", text))
    }

    suspend fun askPick(o: CallOrder, items: List<OItem>, party: Party): Long {
        if (!ready()) return 0
        val idx = items.indexOfFirst { !it.sure }
        if (idx < 0) return 0
        val item = items[idx]
        val all = Repo.dao.products()
        val byId = all.associateBy { it.id }
        var cands = item.cands.filter { byId.containsKey(it) }.take(3)
        if (cands.isEmpty()) {
            val sold = Repo.dao.soldTotals().sortedByDescending { it.q }.map { it.productId }.filter { byId.containsKey(it) }
            cands = (sold + all.map { it.id }).distinct().take(3)
        }
        val text = buildString {
            append("Order needs your check\n${party.name}, call at ${clock(o.callAt)}\n\n")
            items.forEach { i ->
                if (i.sure) append("✓ ${byId[i.pid]?.name} ${qtyStr(i.qty)} ${unitShort(byId[i.pid]?.unit ?: "")}\n")
                else append("? \"${i.spoken}\", ${qtyStr(i.qty)}\n")
            }
            append("\nWhich product is \"${item.spoken}\"?")
        }
        val kb = JSONArray()
        cands.forEach { pid -> kb.put(JSONArray().put(JSONObject().put("text", byId[pid]?.name ?: "?").put("callback_data", "p:${o.id}:$idx:$pid"))) }
        kb.put(JSONArray().put(JSONObject().put("text", "Reject this order").put("callback_data", "r:${o.id}")))
        return send(text, kb)
    }

    private suspend fun answer(id: String, text: String) {
        call("answerCallbackQuery", JSONObject().put("callback_query_id", id).put("text", text))
    }

    suspend fun poll() {
        if (token().isBlank()) { delay(30_000); return }
        val res = call("getUpdates", JSONObject().put("offset", Repo.prefs.l("tgOffset", 0)).put("timeout", 25))
        if (res == null) { delay(10_000); return }
        if (!res.optBoolean("ok")) { Repo.note("Telegram: ${res.optString("description", "token not accepted")}"); delay(60_000); return }
        val arr = res.optJSONArray("result") ?: return
        for (i in 0 until arr.length()) {
            val u = arr.getJSONObject(i)
            Repo.prefs.put("tgOffset", u.getLong("update_id") + 1)
            val m = u.optJSONObject("message")
            if (m != null) {
                val chatId = m.getJSONObject("chat").get("id").toString()
                if (m.optString("text").startsWith("/start") && (chat().isBlank() || chat() == chatId)) {
                    Repo.prefs.put("tgChat", chatId)
                    Repo.note("Telegram connected")
                    send("Parchi is connected. Orders that need your check will come here.")
                }
            }
            val q = u.optJSONObject("callback_query") ?: continue
            val msg = q.optJSONObject("message") ?: continue
            val from = msg.getJSONObject("chat").get("id").toString()
            val qid = q.getString("id")
            if (from != chat()) { answer(qid, "Not allowed"); continue }
            val parts = q.optString("data").split(":")
            val reply = try {
                when (parts[0]) {
                    "p" -> Pipeline.tgPick(parts[1].toLong(), parts[2].toInt(), parts[3].toLong())
                    "r" -> { Pipeline.reject(parts[1].toLong()); "Order rejected" }
                    else -> "Unknown action"
                }
            } catch (e: Exception) { "Something went wrong: ${e.message}" }
            answer(qid, reply.take(190))
            edit(msg.getLong("message_id"), msg.optString("text") + "\n\n→ " + reply)
        }
    }
}
