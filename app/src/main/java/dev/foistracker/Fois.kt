package dev.foistracker

import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl.Companion.toHttpUrl

val ist: ZoneId = ZoneId.of("Asia/Kolkata")
val fields = listOf("FNR", "Railway receipt", "Rake identification", "Origin", "Destination",
    "Commodity", "Consignor", "Consignee", "Booking date", "Loading date", "Departure date",
    "Expected arrival", "Actual arrival", "Last reported station", "Last reported movement",
    "Railway zone", "Railway division", "Locomotive number", "Locomotive type", "Wagon count",
    "Wagon numbers", "Wagon types", "Freight status", "Remaining distance", "Indent date")

data class Journey(val station: String, val arrival: String, val departure: String,
    val zone: String, val division: String, val movement: String)
data class Freight(val values: Map<String, String>, val journey: List<Journey> = emptyList(),
    val retrieved: Long = System.currentTimeMillis(), val previousEta: String? = null,
    val source: String = "ECBS") {
    val fnr get() = values["FNR"].orEmpty()
    val eta get() = values["Expected arrival"]
    val arrived get() = values["Actual arrival"]
    fun get(key: String): String {
        val value = values[key] ?: return "Not Reported"
        return if (key == "Last reported station") reportedStationLabel(value, journey) else value
    }
}

data class Query(val key: String, val consignor: String = "FCI") {
    val isFnr get() = key.matches(Regex("[0-9]{11}"))
    val storageKey get() = if (isFnr) key else "$key|$consignor"
    companion object {
        fun parse(text: String, consignor: String = "FCI"): Query? {
            val normalized = text.trim().uppercase(Locale.ROOT).replace(Regex("\\s+(?:TO\\s+|->\\s*)?"), " ")
            return if (normalized.matches(Regex("[0-9]{11}|[A-Z0-9]{2,5} [A-Z0-9]{2,5}")) &&
                (normalized.none { it == ' ' } || normalized.substringBefore(' ') != normalized.substringAfter(' '))) {
                Query(normalized, consignor.trim().uppercase(Locale.ROOT))
            } else null
        }
        fun fromStorage(key: String) = Query(key.substringBefore('|'), key.substringAfter('|', "FCI"))
    }
}

sealed class FoisError(message: String) : IOException(message) {
    class Authentication : FoisError("Your ECBS session has ended. Sign in again.")
    class Verification : FoisError("FOIS rejected the verification code. Request a new image and try again.")
    class Credentials : FoisError("FOIS rejected sign-in. Check your username and password.")
    class Format : FoisError("FOIS returned an unrecognized response. Try again later.")
    class Empty : FoisError("FOIS found no matching consignments.")
    class Unavailable : FoisError("FOIS is unavailable. Try again later.")
}

private val aliases = mapOf(
    "fnr" to "FNR", "fnr number" to "FNR", "fnr no" to "FNR", "f.n.r. no" to "FNR",
    "freight number" to "FNR", "freight number (fnr)" to "FNR", "rr number" to "Railway receipt", "rr no" to "Railway receipt",
    "railway receipt number" to "Railway receipt", "sttn from" to "Origin", "station from" to "Origin",
    "sttn to" to "Destination", "station to" to "Destination", "source station" to "Origin",
    "destination station" to "Destination", "load name" to "Rake identification", "rake id" to "Rake identification",
    "eta at destination" to "Expected arrival", "expd arvltime" to "Expected arrival",
    "expected arrival time" to "Expected arrival", "actual arrival time" to "Actual arrival",
    "locn" to "Last reported station", "from" to "Origin", "to" to "Destination", "detail" to "Consignment detail",
    "status location" to "Last reported station", "stts time" to "Last reported movement",
    "stts code" to "Freight status", "status" to "Freight status", "ldng time" to "Loading date",
    "rmng km" to "Remaining distance", "remaining km" to "Remaining distance",
    "remaining distance (km)" to "Remaining distance", "loco numb" to "Locomotive number", "loco type" to "Locomotive type",
    "totl unts" to "Wagon count", "no of wagons" to "Wagon count", "load type" to "Load type"
) + fields.associateBy { it.lowercase(Locale.ROOT) }

object FreightParser {
    private fun normalized(text: String) = text.trimEnd { it.isWhitespace() || it == ':' || it == '.' }.trimStart().lowercase(Locale.ROOT)
    fun clean(text: String) = text.trim().takeUnless { it.isBlank() || it.uppercase() in setOf("N/A", "NA", "-", "--", "NOT REPORTED") }
    fun cells(row: Element) = row.children().filter { it.tagName() in setOf("td", "th") }
    private fun key(text: String) = aliases[normalized(text)]
    fun expired(html: String) = html.contains("LoginNew", true) || html.contains("submitLogin", true)
    fun empty(doc: Document) = Regex("no (?:matching |freight )?(?:records?|data|results?|consignments?)|invalid fnr|not found", RegexOption.IGNORE_CASE).containsMatchIn(doc.text())

    fun details(html: String, fnr: String, public: Boolean = false): Freight {
        if (!public && expired(html)) throw FoisError.Authentication()
        val doc = Jsoup.parse(html)
        if (empty(doc)) throw FoisError.Empty()
        val values = linkedMapOf<String, String>()
        doc.select("tr").forEach { row ->
            val cells = cells(row)
            if (cells.size == 2 || cells.size >= 4 && cells.size % 2 == 0) {
                cells.chunked(2).forEach { pair ->
                    key(pair[0].text())?.let { name -> clean(pair[1].text())?.let { values[name] = it } }
                }
            }
            if (!public && cells.size >= 4 &&
                cells.take(2).all { Regex("\\([A-Z0-9]{2,5}\\)").containsMatchIn(it.text()) }) {
                listOf("Origin", "Destination", "Last reported station", "Expected arrival").forEachIndexed { i, name ->
                    clean(cells[i].text())?.let { values.putIfAbsent(name, it) }
                }
            }
        }
        val journey = timeline(doc)
        val last = journey.filter { freightTime(it.departure) != null || freightTime(it.arrival) != null }
            .maxByOrNull { freightTime(it.departure) ?: freightTime(it.arrival)!! }
        if (last != null) {
            values.putIfAbsent("Last reported station", last.station)
            (clean(last.departure) ?: clean(last.arrival))?.let { values.putIfAbsent("Last reported movement", it) }
        }
        if (values.keys.none { it != "FNR" } && journey.isEmpty()) throw FoisError.Format()
        values["FNR"] = fnr
        return Freight(values, journey, source = if (public) "Public FNR" else "ECBS")
    }

    fun timeline(doc: Document): List<Journey> = buildList {
        doc.select("table").forEach { table ->
            var vertical = linkedMapOf<String, String>()
            fun flush() {
                val station = vertical["station"]
                if (station != null) {
                    val combined = vertical["zone (division)"].orEmpty()
                    add(Journey(station, vertical["arrival"].orEmpty(), vertical["departure"].orEmpty(),
                        vertical["zone"].orEmpty().ifEmpty { combined.substringBefore('(').trim() },
                        vertical["division"].orEmpty().ifEmpty { combined.substringAfter('(', "").substringBefore(')').trim() },
                        vertical["movement"].orEmpty()))
                }
                vertical = linkedMapOf()
            }
            var headers = emptyList<String>()
            table.select("tr").filter { it.closest("table") == table }.forEach { row ->
                val cells = cells(row)
                val labels = cells.map { normalized(it.text()) }
                if (cells.size == 2 && cells[0].tagName() != "th") {
                    val label = labels.first()
                    val value = clean(cells[1].text())
                    val name = when (label) {
                        in stationLabels -> "station"
                        in arrivalLabels -> "arrival"
                        in departureLabels -> "departure"
                        in movementLabels -> "movement"
                        "zone (division)", "zone", "division" -> label
                        else -> null
                    }
                    if (name == "station") flush()
                    if (name != null && value != null) vertical[name] = value
                }
                if (labels.any { it in stationLabels } && labels.any { it in arrivalLabels || it in departureLabels || it in movementLabels }) {
                    headers = labels
                } else if (headers.isNotEmpty() && cells.size == headers.size) {
                    val rowValues = headers.zip(cells.map { clean(it.text()).orEmpty() }).toMap()
                    fun find(keys: Set<String>) = keys.firstNotNullOfOrNull { rowValues[it]?.takeIf(String::isNotEmpty) }.orEmpty()
                    val station = find(stationLabels)
                    val code = find(setOf("station code", "sttn code"))
                    val label = if (station.isNotEmpty() && code.isNotEmpty() && code != station) "$station ($code)" else station.ifEmpty { code }
                    if (label.isNotEmpty()) add(Journey(label, find(arrivalLabels), find(departureLabels),
                        find(setOf("zone", "railway zone")), find(setOf("division", "railway division")), find(movementLabels)))
                }
            }
            flush()
        }
    }.distinct()

    private val stationLabels = setOf("station", "station name", "sttn", "station code", "sttn code")
    private val arrivalLabels = setOf("arrival", "arrival time", "actual arrival", "arvl time")
    private val departureLabels = setOf("departure", "departure time", "dprt time")
    private val movementLabels = setOf("movement", "status", "event")
    data class RouteRow(val freight: Freight, val link: String?, val loadId: String?)
    fun route(html: String): List<RouteRow> {
        if (expired(html)) throw FoisError.Authentication()
        val doc = Jsoup.parse(html)
        if (empty(doc)) return emptyList()
        val output = mutableListOf<RouteRow>()
        var recognized = false
        doc.select("table").forEach { table ->
            var headers = emptyList<String>()
            table.select("tr").filter { it.closest("table") == table }.forEach { row ->
                val cells = cells(row)
                val labels = cells.map { normalized(it.text()) }
                if ("sttn from" in labels && "load type" in labels) {
                    recognized = true
                    headers = labels
                } else if (headers.isNotEmpty() && cells.size == headers.size) {
                    val values = headers.zip(cells).mapNotNull { (label, cell) ->
                        val name = key(label)
                        val value = clean(cell.text())
                        if (name != null && value != null && (name != "FNR" || value.matches(Regex("[0-9]{11}")))) {
                            name to value
                        } else null
                    }.toMap()
                    if (values["Origin"] != null && values["Destination"] != null) {
                        val markup = row.outerHtml().replace("&amp;", "&")
                        val link = Regex("QryLoadInvcDtls\\.jsp\\?[^'\"\\s>)]+").find(markup)?.value
                        val loadId = Regex("[A-Z0-9]{2,5}\\s+[A-Z0-9]{2,5}\\s+[0-9]{12,14}").find(markup)?.value
                        output += RouteRow(Freight(values), link, loadId)
                    }
                }
            }
        }
        if (!recognized) throw FoisError.Format()
        return output.distinct()
    }

    fun stations(html: String): List<Pair<String, String>> {
        val pattern = Regex("""^([A-Z0-9]{2,5})\s+\((.+)\)$""")
        return Jsoup.parse(html).select(".sttnhead").mapNotNull { node ->
            val match = pattern.matchEntire(node.text().trim()) ?: return@mapNotNull null
            val name = match.groupValues[2].substringBefore(" (IN -").substringBefore(" (OUT -").trim()
            name.takeIf { it.isNotEmpty() }?.let { match.groupValues[1] to it }
        }.distinctBy { it.first }
    }

    data class Receipt(val origin: String, val destination: String, val index: String, val date: String,
        val reportedNumber: String?)
    fun receipts(html: String): List<Receipt> {
        if (expired(html)) throw FoisError.Authentication()
        val args = Regex("""submitForm\(\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*\)""")
        val doc = Jsoup.parse(html)
        val links = doc.select("[onclick]").mapNotNull { node ->
            val call = args.find(node.attr("onclick")) ?: return@mapNotNull null
            call.value to node.text().trim().takeIf { it.matches(Regex("[0-9]{8,14}")) }
        }.toMap()
        return args.findAll(html).map { match ->
            Receipt(match.groupValues[2], match.groupValues[1], match.groupValues[3], match.groupValues[4],
                links[match.value])
        }.distinct().toList()
    }
    fun reportedFnr(html: String): String? {
        if (expired(html)) throw FoisError.Authentication()
        val doc = Jsoup.parse(html)
        val formatted = doc.select("span[class]").filter { it.className().contains("textboldmr", ignoreCase = true) }
            .map { it.text().filter { character -> character in '0'..'9' } }
            .filter { it.length == 11 }.distinct()
        return formatted.singleOrNull() ?: doc.select("tr").firstNotNullOfOrNull { row ->
            val cells = cells(row)
            val index = cells.indexOfFirst { key(it.text()) == "FNR" }
            cells.getOrNull(index + 1)?.text()?.takeIf { index >= 0 && it.matches(Regex("[0-9]{11}")) }
        } ?: Regex("""\bFNR(?:\s*(?:No\.?|Number))?\s*[:#-]?\s*([0-9]{11})\b""", RegexOption.IGNORE_CASE)
            .find(doc.text())?.groupValues?.get(1)
    }
}

fun cryptoJsPassword(password: String, passphrase: String,
    salt: ByteArray = ByteArray(8).also(SecureRandom()::nextBytes)): String {
    require(salt.size == 8)
    val md5 = MessageDigest.getInstance("MD5")
    var block = byteArrayOf()
    val derived = ByteArray(48)
    repeat(3) { index ->
        block = md5.digest(block + passphrase.toByteArray(Charsets.UTF_8) + salt)
        block.copyInto(derived, index * 16)
    }
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived.copyOfRange(0, 32), "AES"),
        IvParameterSpec(derived.copyOfRange(32, 48)))
    return Base64.getEncoder().encodeToString("Salted__".toByteArray() + salt + cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
}

class FoisCookies(initial: List<Cookie> = emptyList()) : CookieJar {
    private val cookies = initial.toMutableList()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { fresh ->
            this.cookies.removeAll { it.name == fresh.name && it.domain == fresh.domain && it.path == fresh.path }
            if (fresh.expiresAt > System.currentTimeMillis()) this.cookies.add(fresh)
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl) =
        cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    @Synchronized fun all() = cookies.filter { it.expiresAt > System.currentTimeMillis() }
    @Synchronized fun clear() = cookies.clear()
}

class FoisClient(private val authCookies: FoisCookies = FoisCookies(),
    private val root: HttpUrl = "https://www.fois.indianrail.gov.in/".toHttpUrl()) {
    private val publicCookies = FoisCookies()
    private fun client(jar: FoisCookies) = OkHttpClient.Builder().cookieJar(jar)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()
    private val auth = client(authCookies)
    private val guest = client(publicCookies)
    private val directory = client(FoisCookies())
    private val stationPage = "FOISWebPortal/pages/FWP_SttnHelp.jsp"
    private val loginPage = "ecbs/JSP/LoginNew.jsp"
    private val publicPage = "FOISWebPortal/pages/FWP_FNREnq.jsp"
    private val router = "ecbs/RouterServlet"
    private val home = "ecbs/JSP/HomeLinks.jsp"
    private var loginTimestamp: String? = null
    private fun today() = LocalDate.now(ist).format(DateTimeFormatter.ofPattern("dd-MM-uuuu"))

    private fun request(path: String, query: Map<String, String> = emptyMap(), form: Map<String, String>? = null,
        referer: String? = null, ajax: Boolean = false, public: Boolean = false,
        stationDirectory: Boolean = false): ByteArray {
        var url = requireNotNull(root.resolve(path)).newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        var payload = form
        repeat(6) {
            if (url.host != root.host || url.scheme != root.scheme || url.port != root.port) throw FoisError.Format()
            val builder = Request.Builder().url(url)
            referer?.let { builder.header("Referer", requireNotNull(root.resolve(it)).toString()) }
            if (ajax) builder.header("X-Requested-With", "XMLHttpRequest")
            payload?.let { data ->
                builder.header("Origin", requireNotNull(root.resolve("/")).toString().trimEnd('/'))
                builder.post(FormBody.Builder().apply { data.forEach { (key, value) -> add(key, value) } }.build())
            }
            val http = if (stationDirectory) directory else if (public) guest else auth
            http.newCall(builder.build()).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val destination = response.header("Location")?.let(url::resolve) ?: throw FoisError.Format()
                    if (response.code in listOf(301, 302, 303)) payload = null
                    url = destination
                } else {
                    if (response.code in listOf(401, 403) && !public) throw FoisError.Authentication()
                    if (!response.isSuccessful) throw FoisError.Unavailable()
                    val body = response.body ?: throw FoisError.Format()
                    val source = body.source()
                    if (source.request(4_194_305)) throw FoisError.Format()
                    return source.readByteArray()
                }
            }
        }
        throw FoisError.Format()
    }

    fun image(public: Boolean, reset: Boolean): ByteArray {
        if (reset) {
            (if (public) publicCookies else authCookies).clear()
            val page = request(if (public) publicPage else loginPage, public = public)
            if (!public) loginTimestamp = Jsoup.parse(page.decodeToString())
                .selectFirst("input[name=logintimeform]")?.attr("value")?.takeIf { it.matches(Regex("[0-9]{10,16}")) }
        }
        return request(if (public) "FOISWebPortal/portalCaptcha.jpg" else "ecbs/ecustCaptcha.jpg",
            query = mapOf("_" to System.currentTimeMillis().toString()),
            referer = if (public) publicPage else loginPage, public = public)
    }

    fun signIn(username: String, password: String, answer: String) {
        if (request("ecbs/matchcaptcha", form = mapOf("result" to answer), referer = loginPage,
                ajax = true).decodeToString().trim() != "Y") throw FoisError.Verification()
        val key = request("ecbs/PassSecure", form = emptyMap(), referer = loginPage, ajax = true).decodeToString().trim()
        if (key.isEmpty() || key.contains('<') || key.contains("server issue", true)) throw FoisError.Format()
        request(router, form = mapOf("operation" to "login", "logintimeform" to (loginTimestamp ?: System.currentTimeMillis().toString()),
            "txtInputPage" to "", "txtUserID" to username.uppercase(Locale.ROOT),
            "passwd" to cryptoJsPassword(password, key), "txtLangFlag" to "E", "txtSHYFlag" to "",
            "txtUserId" to username.lowercase(Locale.ROOT), "txtPassword" to (cryptoJsPassword(password, key) + "#" + key),
            "answer" to answer), referer = loginPage)
        if (!validate()) throw FoisError.Credentials()
    }

    fun validate(): Boolean = try {
        val html = request(home, query = mapOf("action" to "Query")).decodeToString()
        !FreightParser.expired(html) && listOf("logout", "QryInsight", "HomeLinks").any { html.contains(it, true) }
    } catch (_: FoisError.Authentication) {
        false
    }

    fun signOut() {
        try {
            val doc = Jsoup.parse(request(home, query = mapOf("action" to "Query")).decodeToString())
            val link = doc.select("a[href]").firstOrNull { it.text().contains("logout", true) || it.text().contains("log out", true) }
            val target = link?.attr("href")?.let { root.resolve("/$home")?.resolve(it) }
            if (target != null && target.encodedPath.startsWith("/ecbs/")) request(target.toString())
        } finally { authCookies.clear() }
    }

    fun findStations(text: String, byCode: Boolean): List<Pair<String, String>> {
        val value = text.trim().uppercase(Locale.ROOT)
        require(value.length in 2..45)
        request(stationPage, public = true, stationDirectory = true)
        val html = request("FOISWebPortal/FWP_SttnHelp", form = mapOf("Qry" to "CRT_LOCN",
            "txtSttnCode" to if (byCode) value else "", "txtSttnName" to if (byCode) "" else value,
            "hidreset" to "A"), referer = stationPage, public = true, stationDirectory = true).decodeToString()
        return FreightParser.stations(html)
    }

    fun publicFnr(fnr: String, answer: String): Freight {
        val html = request("FOISWebPortal/FWP_FNREnq", form = mapOf("Qry" to "FNR_ENQUIRY",
            "txtFNRNumb" to fnr, "captchaText" to answer), referer = publicPage, public = true).decodeToString()
        if (Regex("(?:invalid|incorrect|wrong)\\s+(?:captcha|verification)|captcha[^.<]{0,45}(?:invalid|mismatch|does not match|try again|incorrect)", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            throw FoisError.Verification()
        }
        return FreightParser.details(html, fnr, public = true)
    }

    fun enquiry(query: Query): List<Freight> = if (query.isFnr) listOf(fnr(query.key)) else {
        val (origin, destination) = query.key.split(' ')
        route(origin, destination, query.consignor).ifEmpty { throw FoisError.Empty() }
    }

    private fun fnr(number: String): Freight {
        request("ecbs/JSP/QryTrknTrace.jsp").decodeToString().let {
            if (FreightParser.expired(it)) throw FoisError.Authentication()
        }
        val html = request(router, query = mapOf("operation" to "query", "suboperation" to "fnr",
            "txtOptn" to "F", "txtFNR" to number, "txtSttnFrom" to "", "txtSttnTo" to "", "txtNumb" to "",
            "txtDate" to today())).decodeToString()
        val base = FreightParser.details(html, number)
        fun code(field: String) = base.values[field]?.let {
            Regex("""\(([A-Z0-9]{2,5})\)""").find(it)?.groupValues?.get(1)
                ?: it.takeIf { value -> value.matches(Regex("[A-Z0-9]{2,5}")) }
        }
        val origin = code("Origin") ?: return base
        val destination = code("Destination") ?: return base
        return try {
            val matching = route(origin, destination, "FCI").firstOrNull { it.fnr == number } ?: return base
            base.copy(values = matching.values + base.values, journey = (base.journey + matching.journey).distinct())
        } catch (e: FoisError.Authentication) {
            throw e
        } catch (_: IOException) {
            base
        }
    }

    private fun route(origin: String, destination: String, consignor: String): List<Freight> {
        val html = request(router, query = mapOf("operation" to "query", "suboperation" to "insight",
            "txtSttnFrom" to origin, "txtSttnTo" to destination, "txtCommodity" to "", "txtConsignor" to consignor,
            "txtConsignee" to "", "locnflag" to "S", "user" to consignor, "usrflag" to "C", "txtSysDate" to today(),
            "txtFlag" to "S", "txtTFlag" to "S")).decodeToString()
        return FreightParser.route(html).flatMap { row ->
            try { invoice(row, origin, destination) }
            catch (e: FoisError.Authentication) { throw e }
            catch (_: IOException) { listOf(row.freight) }
        }.distinctBy { it.values }
    }

    private fun invoice(row: FreightParser.RouteRow, origin: String, destination: String): List<Freight> {
        val path = "ecbs/JSP/QryLoadInvcDtls.jsp"
        val loadId = row.loadId ?: row.freight.values["Loading date"]?.filter(Char::isDigit)?.let { digits ->
            when (digits.length) {
                12 -> "$origin $destination ${digits}00"
                14 -> "$origin $destination $digits"
                else -> null
            }
        }
        val url = if (row.link != null) requireNotNull(root.resolve("ecbs/JSP/${row.link}")) else {
            if (loadId == null) return listOf(row.freight)
            requireNotNull(root.resolve(path)).newBuilder()
                .addQueryParameter("loadid", loadId)
                .addQueryParameter("loadname", row.freight.values["Rake identification"].orEmpty())
                .addQueryParameter("sttnfrom", origin).addQueryParameter("sttnto", destination).build()
        }
        val html = request(url.toString(), referer = router).decodeToString()
        val receipts = FreightParser.receipts(html).ifEmpty {
            val number = FreightParser.reportedFnr(html)
            if (number != null) return listOf(row.freight.copy(values = row.freight.values + ("FNR" to number)))
            if (row.freight.fnr.isNotEmpty()) return listOf(row.freight)
            val date = row.freight.values["Loading date"]
                ?.let { Regex("""\b\d{2}-\d{2}-\d{4}\b""").find(it)?.value } ?: today()
            listOf(FreightParser.Receipt(origin, destination, "1", date, null))
        }
        return receipts.map { receipt ->
            val args = mapOf("txtSttnFrom" to receipt.origin, "txtSttnTo" to receipt.destination,
                "txtInvcNo" to receipt.index, "txtDate" to receipt.date, "flag" to "frgt",
                "operation" to "query", "suboperation" to "rrdtls", "LoadID" to url.queryParameter("loadid").orEmpty())
            val values = row.freight.values.toMutableMap()
            receipt.reportedNumber?.let { values["Railway receipt"] = it }
            try {
                var details = request(router, form = args, referer = url.toString()).decodeToString()
                var number = FreightParser.reportedFnr(details)
                if (number == null) {
                    details = request(router, query = args, referer = url.toString()).decodeToString()
                    number = FreightParser.reportedFnr(details)
                }
                if (number != null) values["FNR"] = number
                val parsed = number?.let { runCatching { FreightParser.details(details, it) }.getOrNull() }
                row.freight.copy(values = values + parsed?.values.orEmpty(), journey = parsed?.journey.orEmpty())
            } catch (e: FoisError.Authentication) { throw e }
            catch (_: IOException) { row.freight.copy(values = values) }
        }.ifEmpty { listOf(row.freight) }
    }
}
