package com.parchi.billing

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

/** One printed line. */
data class PLine(val text: String, val bold: Boolean = false, val big: Boolean = false, val center: Boolean = false)

object Printer {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun width(): Int = if (Repo.prefs.s("size", "3") == "2") 32 else 48

    /** Thermal printers only know plain letters, so anything else is swapped or dropped. */
    private fun ascii(s: String): String = s.replace("₹", "Rs").map { if (it.code in 32..126) it else '?' }.joinToString("")

    private fun lr(l: String, r: String, w: Int): String {
        val right = r.take(w)
        val left = l.take((w - right.length - 1).coerceAtLeast(0))
        return left + " ".repeat((w - left.length - right.length).coerceAtLeast(1)) + right
    }

    private fun cols(cells: List<String>, widths: List<Int>): String = cells.mapIndexed { i, c ->
        val w = widths[i]
        if (i == 0) c.take(w).padEnd(w) else c.take(w).padStart(w)
    }.joinToString("")

    private fun rs(v: Double): String = money(v).replace("₹ ", "")

    fun lines(b: Bill, items: List<BillItem>, party: Party, oldDue: Double): List<PLine> {
        val p = Repo.prefs
        val w = width()
        val dash = "-".repeat(w)
        val fmt = when (p.s("fmt", "auto")) { "slip" -> "slip"; "simple" -> "simple"; else -> if (b.gst) "gst" else "simple" }
        val out = mutableListOf<PLine>()
        out += PLine(p.s("org", "Your Shop Name"), bold = true, big = true, center = true)
        p.s("addr").takeIf { it.isNotBlank() }?.let { out += PLine(it, center = true) }
        if (p.b("showPhone", true) && p.s("phone").isNotBlank()) out += PLine("Ph " + p.s("phone"), center = true)
        if (fmt == "gst" && p.s("gstin").isNotBlank()) out += PLine("GSTIN " + p.s("gstin"), center = true)
        out += PLine(dash)
        out += PLine(when (fmt) { "slip" -> "DELIVERY SLIP"; "gst" -> "TAX INVOICE"; else -> "BILL" }, bold = true, center = true)
        out += PLine(dash)
        out += PLine(lr("No. ${b.no}", fmtD(b.iso, true), w))
        out += PLine(party.name, bold = true)
        if (p.b("partyPhone", true) && party.firstPhone().isNotBlank()) out += PLine("Ph " + party.firstPhone())
        if (fmt == "gst" && party.gstin.isNotBlank()) out += PLine("GSTIN " + party.gstin)
        out += PLine(dash)
        if (fmt == "slip") {
            out += PLine(lr("Item", "Qty", w), bold = true)
            items.forEach { out += PLine(lr(it.name, qtyStr(it.qty) + " " + unitShort(it.unit), w)) }
            out += PLine(dash)
            out += PLine("${items.size} item${if (items.size == 1) "" else "s"}")
        } else {
            if (w <= 32) {
                items.forEach {
                    out += PLine(it.name + if (it.disc > 0) " (-${qtyStr(it.disc)}%)" else "")
                    out += PLine(lr("  ${qtyStr(it.qty)} ${unitShort(it.unit)} x ${qtyStr(it.rate)}", rs(it.amt), w))
                }
            } else {
                val ws = listOf(w - 25, 8, 8, 9)
                out += PLine(cols(listOf("Item", "Qty", "Rate", "Amt"), ws), bold = true)
                items.forEach {
                    val name = it.name + if (it.disc > 0) " -${qtyStr(it.disc)}%" else ""
                    out += PLine(cols(listOf(name, qtyStr(it.qty) + unitShort(it.unit), qtyStr(it.rate), rs(it.amt)), ws))
                    if (name.length > ws[0]) out += PLine("  " + name.drop(ws[0]).take(w - 2))
                }
            }
            out += PLine(dash)
            out += PLine(lr("Sub total", rs(b.sub), w))
            if (b.billDisc > 0) out += PLine(lr("Discount ${qtyStr(b.billDiscPct)}%", "- " + rs(b.billDisc), w))
            if (fmt == "gst" && b.tax > 0) {
                out += PLine(lr("CGST", rs(b.tax / 2), w))
                out += PLine(lr("SGST", rs(b.tax / 2), w))
            }
            out += PLine(lr("TOTAL", money(b.total), w), bold = true)
            if (b.paid > 0) out += PLine(lr("Received", money(b.paid), w))
            if (p.b("oldDue", true) && oldDue > 0) {
                out += PLine(lr("Old due", money(oldDue), w))
                out += PLine(lr("Balance", money(oldDue + b.leftToPay()), w), bold = true)
            }
        }
        out += PLine(dash)
        out += PLine(p.s("footer", "Thank you, visit again"), center = true)
        return out
    }

    suspend fun linesFor(billId: Long): List<PLine>? {
        val dao = Repo.dao
        val b = dao.bill(billId) ?: return null
        val party = dao.party(b.partyId) ?: return null
        val oldDue = dao.openBills(b.partyId).filter { it.id != b.id }.sumOf { it.leftToPay() }
        return lines(b, dao.items(b.id), party, oldDue)
    }

    private fun bytes(all: List<List<PLine>>): ByteArray {
        val o = ByteArrayOutputStream()
        fun cmd(vararg b: Int) = b.forEach { o.write(it) }
        cmd(0x1B, 0x40)
        all.forEach { lines ->
            lines.forEach { l ->
                cmd(0x1B, 0x61, if (l.center) 1 else 0)
                cmd(0x1B, 0x45, if (l.bold) 1 else 0)
                cmd(0x1D, 0x21, if (l.big) 0x11 else 0x00)
                o.write(ascii(l.text).toByteArray(Charsets.US_ASCII))
                o.write('\n'.code)
            }
            cmd(0x1D, 0x21, 0x00); cmd(0x1B, 0x45, 0)
            o.write("\n\n\n\n".toByteArray())
            cmd(0x1D, 0x56, 0x42, 0x00)
        }
        return o.toByteArray()
    }

    fun hasPermission(c: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun paired(c: Context): List<Pair<String, String>> {
        if (!hasPermission(c)) return emptyList()
        val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try { adapter.bondedDevices.map { (it.name ?: it.address) to it.address } } catch (e: SecurityException) { emptyList() }
    }

    @SuppressLint("MissingPermission")
    suspend fun send(c: Context, data: ByteArray): String? = withContext(Dispatchers.IO) {
        val mac = Repo.prefs.s("printer")
        if (mac.isBlank()) return@withContext "No printer chosen yet"
        if (!hasPermission(c)) return@withContext "Bluetooth permission not given"
        val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return@withContext "This phone has no Bluetooth"
        if (!adapter.isEnabled) return@withContext "Bluetooth is off"
        try {
            val dev = adapter.getRemoteDevice(mac)
            val sock = dev.createRfcommSocketToServiceRecord(SPP)
            try { adapter.cancelDiscovery() } catch (e: Exception) { }
            sock.connect()
            val os = sock.outputStream
            data.toList().chunked(1024).forEach { os.write(it.toByteArray()); os.flush(); Thread.sleep(30) }
            Thread.sleep(500)
            sock.close()
            null
        } catch (e: Exception) {
            "Printer not in range"
        }
    }

    /** Print everything waiting. Returns how many bills went out. */
    suspend fun flush(c: Context): Int {
        val q = Repo.dao.printQueue()
        if (q.isEmpty()) { Repo.printerState.value = ""; return 0 }
        val docs = q.mapNotNull { linesFor(it.id) }
        val err = send(c, bytes(docs))
        if (err != null) {
            Repo.printerState.value = err
            return 0
        }
        q.forEach { Repo.dao.updateBill(it.copy(printed = true)) }
        Repo.printerState.value = ""
        Repo.note("Printer found, ${q.size} bill${if (q.size == 1) "" else "s"} printed")
        return q.size
    }

    suspend fun test(c: Context): String? {
        val w = width()
        val doc = listOf(
            PLine(Repo.prefs.s("org", "Your Shop Name"), bold = true, big = true, center = true),
            PLine("-".repeat(w)), PLine("Test print from Parchi", center = true),
            PLine(lr("Paper", "${Repo.prefs.s("size", "3")} inch, $w letters", w)), PLine("-".repeat(w))
        )
        return send(c, bytes(listOf(doc)))
    }
}
