package dev.foistracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Saved(val key: String, val name: String, val favorite: Boolean, val tracking: Boolean)
data class Entry(val key: String, val freight: List<Freight>, val updated: Long)

class Store(context: Context) : SQLiteOpenHelper(context, "freight.db", null, 1) {
    val changes = MutableStateFlow(0)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE saved (query TEXT PRIMARY KEY, name TEXT NOT NULL, favorite INTEGER NOT NULL, tracking INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE enquiry (query TEXT PRIMARY KEY, results TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE eta (fnr TEXT NOT NULL, eta TEXT NOT NULL, observed INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE alerts (query TEXT NOT NULL, category TEXT NOT NULL, fingerprint TEXT NOT NULL, sent INTEGER NOT NULL, PRIMARY KEY(query,category,fingerprint))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private fun changed() { changes.update { it + 1 } }

    fun saved(): List<Saved> = readableDatabase.rawQuery("SELECT query,name,favorite,tracking FROM saved ORDER BY favorite DESC,name COLLATE NOCASE,query", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Saved(cursor.getString(0), cursor.getString(1), cursor.getInt(2) == 1, cursor.getInt(3) == 1)) }
    }
    fun save(item: Saved) {
        writableDatabase.insertWithOnConflict("saved", null, ContentValues().apply {
            put("query", item.key); put("name", item.name); put("favorite", item.favorite); put("tracking", item.tracking)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }
    fun pause(key: String) {
        writableDatabase.update("saved", ContentValues().apply { put("tracking", false) }, "query=?", arrayOf(key))
        changed()
    }
    fun delete(key: String) { writableDatabase.delete("saved", "query=?", arrayOf(key)); changed() }
    fun history(): List<Entry> = entries(null)
    fun cached(key: String): List<Freight> = entries(key).firstOrNull()?.freight.orEmpty()
    private fun entries(key: String?): List<Entry> {
        val where = if (key == null) "" else " WHERE query=?"
        return readableDatabase.rawQuery("SELECT query,results,updated FROM enquiry$where ORDER BY updated DESC",
            key?.let { arrayOf(it) }).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val results = JSONArray(cursor.getString(1))
                    add(Entry(cursor.getString(0), (0 until results.length()).map { decode(results.getJSONObject(it)) }, cursor.getLong(2)))
                }
            }
        }
    }
    private val retainedDetailFields = setOf("Railway receipt", "Rake identification", "Origin", "Destination", "Commodity",
        "Consignor", "Consignee", "Booking date", "Loading date", "Departure date", "Consignment detail",
        "Load type", "Actual arrival")

    fun record(key: String, result: List<Freight>): List<Freight> {
        val db = writableDatabase
        db.beginTransaction()
        val updated = try {
            val old = cached(key)
            val results = result.map { row ->
                val previous = if (row.fnr.isNotEmpty()) old.find { it.fnr == row.fnr } else old.find {
                    it.get("Rake identification") == row.get("Rake identification") &&
                        row.values.containsKey("Rake identification")
                }
                val lastEta = row.fnr.takeIf(String::isNotEmpty)?.let { fnr ->
                    db.rawQuery("SELECT eta FROM eta WHERE fnr=? ORDER BY rowid DESC LIMIT 1", arrayOf(fnr))
                        .use { if (it.moveToFirst()) it.getString(0) else null }
                } ?: previous?.eta
                if (row.fnr.isNotEmpty() && row.eta != null && row.eta != lastEta) {
                    db.insertOrThrow("eta", null, ContentValues().apply {
                        put("fnr", row.fnr); put("eta", row.eta); put("observed", row.retrieved)
                    })
                }
                val previousEta = if (row.eta != lastEta) lastEta else previous?.previousEta
                val knownDetails = if (row.fnr.isNotEmpty()) previous?.values.orEmpty().filterKeys {
                    it in retainedDetailFields
                } else emptyMap()
                row.copy(values = knownDetails + row.values, previousEta = previousEta,
                    journey = row.journey.ifEmpty { previous?.journey.orEmpty() })
            }
            db.insertWithOnConflict("enquiry", null, ContentValues().apply {
                put("query", key); put("results", JSONArray(results.map(::encode)).toString())
                put("updated", results.maxOf(Freight::retrieved))
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            results
        } finally { db.endTransaction() }
        changed()
        return updated
    }
    fun clearHistory() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("enquiry", null, null)
            writableDatabase.delete("eta", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        changed()
    }
    fun registerAlert(query: String, category: String, fingerprint: String): Boolean =
        writableDatabase.insertWithOnConflict("alerts", null, ContentValues().apply {
            put("query", query); put("category", category); put("fingerprint", fingerprint)
            put("sent", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    fun clearAlerts(category: String) {
        writableDatabase.delete("alerts", "category=?", arrayOf(category))
    }

    fun clearAlert(query: String, category: String) {
        writableDatabase.delete("alerts", "query=? AND category=?", arrayOf(query, category))
    }

    private fun encode(item: Freight) = JSONObject().apply {
        put("values", JSONObject(item.values)); put("retrieved", item.retrieved)
        put("source", item.source); put("previousEta", item.previousEta ?: JSONObject.NULL)
        put("journey", JSONArray(item.journey.map { row ->
            JSONArray(listOf(row.station, row.arrival, row.departure, row.zone, row.division, row.movement))
        }))
    }
    private fun decode(json: JSONObject): Freight {
        val values = json.getJSONObject("values")
        val journey = json.getJSONArray("journey")
        return Freight(values.keys().asSequence().associateWith(values::getString),
            (0 until journey.length()).map { index ->
                val row = journey.getJSONArray(index)
                Journey(row.getString(0), row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5))
            }, json.getLong("retrieved"), json.optString("previousEta").takeUnless { json.isNull("previousEta") },
            json.getString("source"))
    }
}

class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("fois-aes-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("fois-aes-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).commit()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).commit()
    }
    @Synchronized fun get(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        return runCatching {
            val data = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            cipher.updateAAD(name.toByteArray())
            cipher.doFinal(data.copyOfRange(12, data.size)).decodeToString()
        }.getOrNull()
    }
}

class Services private constructor(context: Context) {
    val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val vault = Vault(context)
    val store = Store(context)
    private val root = "https://www.fois.indianrail.gov.in/".toHttpUrl()
    private val cookies = FoisCookies(runCatching {
        val json = JSONArray(vault.get("session") ?: "[]")
        (0 until json.length()).mapNotNull { Cookie.parse(root, json.getString(it)) }
    }.getOrDefault(emptyList()))
    val liveTracking = MutableStateFlow<LiveTrackingState?>(null)
    val signedIn = MutableStateFlow(cookies.all().isNotEmpty())
    val client = FoisClient(cookies)
    val networkLock = Mutex()
    fun persistSession() {
        val list = cookies.all()
        vault.put("session", list.takeIf { it.isNotEmpty() }?.let { JSONArray(it.map(Cookie::toString)).toString() })
        signedIn.value = list.isNotEmpty()
    }
    fun forgetSession() { cookies.clear(); vault.put("session", null); signedIn.value = false }
    companion object {
        @Volatile private var instance: Services? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Services(context.applicationContext).also { instance = it }
        }
    }
}
