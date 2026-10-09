package com.parchi.billing

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val unit: String = "kg",
    val unit2: String = "",
    val conv: Double = 0.0,
    val category: String = "",
    val sale: Double = 0.0,
    val purchase: Double = 0.0,
    val tax: Int = 0,
    val stock: Double = 0.0,
    val low: Double = 0.0,
    val aliases: String = "",
    val active: Boolean = true
) {
    fun aliasList(): List<String> = aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

@Entity
data class Party(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phones: String = "",
    val gst: Boolean = false,
    val gstin: String = "",
    val credit: Int = 15,
    val flat: Double = 0.0,
    val active: Boolean = true
) {
    fun phoneList(): List<Pair<String, String>> = phones.split(";").filter { it.isNotBlank() }
        .map { val p = it.split("|"); p[0] to p.getOrElse(1) { "" } }

    fun firstPhone(): String = phoneList().firstOrNull()?.first ?: ""
}

@Entity(primaryKeys = ["partyId", "productId"])
data class Special(val partyId: Long, val productId: Long, val type: String, val value: Double)

@Entity(primaryKeys = ["partyId", "productId"])
data class LastPrice(val partyId: Long, val productId: Long, val rate: Double)

@Entity(indices = [Index("partyId"), Index("iso"), Index("callId")])
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val no: Int,
    val partyId: Long,
    val iso: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sub: Double = 0.0,
    val billDisc: Double = 0.0,
    val billDiscPct: Double = 0.0,
    val tax: Double = 0.0,
    val total: Double = 0.0,
    val paid: Double = 0.0,
    val status: String = "open",
    val printWanted: Boolean = false,
    val printed: Boolean = false,
    val delivered: Boolean = false,
    val auto: Boolean = false,
    val callId: Long? = null,
    val gst: Boolean = false,
    val sig: String = ""
) {
    fun isOpen(): Boolean = status == "open" || status == "part"
    fun isCancelled(): Boolean = status == "cancelled"
    fun leftToPay(): Double = if (isOpen()) (total - paid).coerceAtLeast(0.0) else 0.0
    fun printPending(): Boolean = printWanted && !printed && !isCancelled()
}

@Entity(indices = [Index("billId")])
data class BillItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,
    val productId: Long,
    val name: String,
    val qty: Double,
    val unit: String,
    val rate: Double,
    val disc: Double,
    val tax: Int,
    val amt: Double
)

@Entity(indices = [Index(value = ["fileKey"], unique = true)])
data class CallOrder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileKey: String,
    val fileName: String,
    val mime: String = "",
    val phone: String = "",
    val partyId: Long? = null,
    val callAt: Long = 0,
    val secs: Int = 0,
    val status: String = "reading",
    val itemsJson: String = "[]",
    val deliverIso: String = "",
    val dateNote: String = "",
    val transcript: String = "",
    val billId: Long? = null,
    val error: String = "",
    val tgMsgId: Long = 0
)

data class Sold(val productId: Long, val q: Double)

@androidx.room.Dao
interface Store {
    @Query("SELECT * FROM Product WHERE active = 1 ORDER BY name COLLATE NOCASE")
    fun productsFlow(): Flow<List<Product>>

    @Query("SELECT * FROM Product WHERE active = 1")
    suspend fun products(): List<Product>

    @Query("SELECT * FROM Product WHERE id = :id")
    suspend fun product(id: Long): Product?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProduct(p: Product): Long

    @Query("UPDATE Product SET stock = stock + :d WHERE id = :id")
    suspend fun addStock(id: Long, d: Double)

    @Query("UPDATE Product SET aliases = :a WHERE id = :id")
    suspend fun setAliases(id: Long, a: String)

    @Query("SELECT * FROM Party WHERE active = 1 ORDER BY name COLLATE NOCASE")
    fun partiesFlow(): Flow<List<Party>>

    @Query("SELECT * FROM Party WHERE active = 1")
    suspend fun parties(): List<Party>

    @Query("SELECT * FROM Party WHERE id = :id")
    suspend fun party(id: Long): Party?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveParty(p: Party): Long

    @Query("SELECT * FROM Special")
    fun specialsFlow(): Flow<List<Special>>

    @Query("SELECT * FROM Special WHERE partyId = :pid")
    suspend fun specials(pid: Long): List<Special>

    @Query("DELETE FROM Special WHERE partyId = :pid")
    suspend fun clearSpecials(pid: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSpecials(list: List<Special>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLast(l: LastPrice)

    @Query("SELECT * FROM LastPrice WHERE partyId = :pid AND productId = :prid")
    suspend fun last(pid: Long, prid: Long): LastPrice?

    @Query("SELECT * FROM Bill ORDER BY iso DESC, id DESC")
    fun billsFlow(): Flow<List<Bill>>

    @Query("SELECT * FROM Bill WHERE id = :id")
    fun billFlow(id: Long): Flow<Bill?>

    @Query("SELECT * FROM Bill WHERE id = :id")
    suspend fun bill(id: Long): Bill?

    @Query("SELECT * FROM Bill WHERE partyId = :pid AND status IN ('open','part') ORDER BY iso, id")
    suspend fun openBills(pid: Long): List<Bill>

    @Query("SELECT * FROM Bill WHERE partyId = :pid AND sig = :sig AND iso = :iso AND status != 'cancelled' LIMIT 1")
    suspend fun twin(pid: Long, sig: String, iso: String): Bill?

    @Query("SELECT * FROM Bill WHERE callId = :cid AND status != 'cancelled' LIMIT 1")
    suspend fun billByCall(cid: Long): Bill?

    @Query("SELECT MAX(no) FROM Bill")
    suspend fun maxNo(): Int?

    @Insert
    suspend fun insertBill(b: Bill): Long

    @Update
    suspend fun updateBill(b: Bill)

    @Query("SELECT * FROM Bill WHERE printWanted = 1 AND printed = 0 AND status != 'cancelled' ORDER BY id")
    suspend fun printQueue(): List<Bill>

    @Query("SELECT COUNT(*) FROM Bill WHERE printWanted = 1 AND printed = 0 AND status != 'cancelled'")
    fun queueCount(): Flow<Int>

    @Query("SELECT * FROM BillItem WHERE billId = :bid ORDER BY id")
    suspend fun items(bid: Long): List<BillItem>

    @Query("SELECT * FROM BillItem WHERE billId = :bid ORDER BY id")
    fun itemsFlow(bid: Long): Flow<List<BillItem>>

    @Query("SELECT BillItem.* FROM BillItem JOIN Bill ON Bill.id = BillItem.billId WHERE Bill.partyId = :pid AND Bill.status != 'cancelled' ORDER BY Bill.id DESC LIMIT 80")
    suspend fun recentItems(pid: Long): List<BillItem>

    @Query("SELECT productId, SUM(qty) AS q FROM BillItem GROUP BY productId")
    suspend fun soldTotals(): List<Sold>

    @Insert
    suspend fun insertItems(items: List<BillItem>)

    @Query("DELETE FROM BillItem WHERE billId = :bid")
    suspend fun clearItems(bid: Long)

    @Query("SELECT * FROM CallOrder ORDER BY id DESC LIMIT 300")
    fun ordersFlow(): Flow<List<CallOrder>>

    @Query("SELECT * FROM CallOrder WHERE id = :id")
    suspend fun order(id: Long): CallOrder?

    @Query("SELECT * FROM CallOrder WHERE id = :id")
    fun orderFlow(id: Long): Flow<CallOrder?>

    @Query("SELECT * FROM CallOrder WHERE fileKey = :k")
    suspend fun orderByKey(k: String): CallOrder?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrder(o: CallOrder): Long

    @Update
    suspend fun updateOrder(o: CallOrder)

    @Query("SELECT * FROM CallOrder WHERE status = 'reading'")
    suspend fun readingOrders(): List<CallOrder>
}

@Database(
    entities = [Product::class, Party::class, Special::class, LastPrice::class, Bill::class, BillItem::class, CallOrder::class],
    version = 1,
    exportSchema = false
)
abstract class Db : RoomDatabase() {
    abstract fun store(): Store

    companion object {
        @Volatile
        private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "parchi.db").build().also { inst = it }
        }
    }
}
