package dev.foistracker

import java.util.Locale

// Station names from the FOIS enquiry and station directory.
internal val stations = listOf(
    "ADI" to "Ahmedabad Junction", "AJNI" to "Ajni", "AMLA" to "Amla Jn.",
    "BBS" to "Bhubaneswar", "BKA" to "Barkhera", "BNI" to "Budni",
    "BOK" to "Borkhedi", "BPK" to "Bhugaon", "BPL" to "Bhopal Junction",
    "BTBR" to "Buti Bori", "BUX" to "Bhandak", "BWRA" to "Bharatwada",
    "BYS" to "Barsali", "BZA" to "Vijayawada Junction", "BZU" to "Betul",
    "CD" to "Chandrapur", "CFS" to "Food Corpn Of India Sanatnagar", "CHJC" to "Chitoda",
    "CHQ" to "Choka", "CKNI" to "Chikni Road", "CNB" to "Kanpur Central",
    "CPW" to "Choti Padoli", "CSMT" to "Chhatrapati Shivaji Maharaj Terminus", "DDMT" to "Darimeta",
    "DDU" to "Pt. Deen Dayal Upadhyaya Junction", "DHQ" to "Dharakhoh", "DLI" to "Delhi Junction",
    "GDKP" to "Ghudankhapa", "GMG" to "Gumgaon", "GNQ" to "Godhani",
    "GZB" to "Ghaziabad", "HGT" to "Hinganghat", "HWH" to "Howrah Junction",
    "JUH" to "Jujharpur Cabin", "KOHL" to "Kohli", "KRI" to "Khapri",
    "KRTH" to "Kiratgarh", "KSLA" to "Kesla", "KSWR" to "Kalmeshwar",
    "LKO" to "Lucknow", "MALK" to "Malkapur Road", "MAS" to "MGR Chennai Central",
    "MDDP" to "Mandi Dip", "MGRD" to "Magardoh", "MIG" to "Mid Ghat",
    "MJRI" to "Majri Jn.", "MJY" to "Maramjhiri", "NDLS" to "New Delhi",
    "NDPM" to "Narmadapuram", "NGI" to "Nagri", "NGP" to "Nagpur",
    "NRKR" to "Narkher", "ODG" to "Obaidulla Ganj", "PNBE" to "Patna Junction",
    "PRKD" to "Powerkheda", "PUNE" to "Pune Junction", "RMN" to "Raman",
    "SAHL" to "Saheli", "SBC" to "KSR Bengaluru", "SC" to "Secunderabad Junction",
    "SEGM" to "Sevagram", "SLOR" to "Seloo Road", "SNI" to "Sindi",
    "SNN" to "Sonegaon", "ST" to "Surat", "TAE" to "Tadali",
    "TAKU" to "Taku", "TEO" to "Teegaon", "TGP" to "Tuljapur",
    "TKD" to "Tughlakabad", "VSKP" to "Visakhapatnam", "VVKN" to "Vivekananda Nagar",
    "WG" to "Wagholi", "WRR" to "Warora", "YNA" to "Yenor"
)

private val stationNames = stations.toMap()
private val bareStationCode = Regex("[A-Z][A-Z0-9]{1,4}")
private val stationWithName = Regex("""^(.+?)\s*\(([^()]+)\)$""")

internal fun reportedStationLabel(station: String, journey: List<Journey>): String {
    fun splitStation(value: String): Pair<String, String>? {
        val match = stationWithName.matchEntire(value.trim()) ?: return null
        val (first, second) = match.destructured
        val a = first.trim()
        val b = second.trim()
        return when {
            a in stationNames -> a to (if (b in stationNames) stationNames.getValue(a) else b)
            b in stationNames -> b to a
            bareStationCode.matches(b) && !bareStationCode.matches(a) -> b to a
            bareStationCode.matches(a) && !bareStationCode.matches(b) -> a to b
            else -> null
        }
    }
    val code = station.trim().takeIf(bareStationCode::matches) ?: return station
    val name = stationNames[code] ?: journey.asSequence()
        .mapNotNull { splitStation(it.station) }.firstOrNull { it.first == code }?.second ?: return station
    if (name.equals(code, true)) return station
    return "$code (${name.uppercase(Locale.ROOT)})"
}
