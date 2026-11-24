package dev.foistracker

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FoisTest {
    @Test fun validatesFNRAndStationPairs() {
        assertEquals("01234567890", Query.parse(" 01234567890 ")?.key)
        assertNull(Query.parse("1234567890"))
        assertNull(Query.parse("12345678901 12"))
        assertNull(Query.parse("RMN RMN"))
        assertEquals(Query("RMN CFS", "FCI"), Query.parse("rmn to cfs", " fci "))
        assertEquals(Query("RMN CFS", "ABC"), Query.fromStorage(Query("RMN CFS", "ABC").storageKey))
    }

    @Test fun preservesPublicEnquiryAndLegacyJourneyLayout() {
        val result = FreightParser.details("""
            <table>
            <tr><td><b>ETA At Destination</b></td><td>24-11-2026 18:45</td></tr>
            <tr><td>From</td><td>Alpha (AAA)</td><td>To</td><td>Beta (BBB)</td></tr>
            <tr><td>Station</td><td>Alpha (AAA)</td></tr>
            <tr><td>Departure Time</td><td>24-11-2026 10:15</td></tr>
            <tr><td>Zone (Division)</td><td>ZZ (Div A)</td></tr>
            </table>
        """, "01234567890", public = true)
        assertEquals("Public FNR", result.source)
        assertEquals("24-11-2026 18:45", result.eta)
        assertEquals("Alpha (AAA)", result.get("Origin"))
        assertEquals("Alpha (AAA)", result.get("Last reported station"))
        assertEquals("Div A", result.journey.single().division)
        assertEquals("Not Reported", result.get("Locomotive type"))
        assertNull(result.arrived)
    }

    @Test fun structuredTimelineDeduplicatesStations() {
        val row = "<tr><td>Alpha</td><td>AAA</td><td>24-11-2026 10:15</td><td>ZZ</td></tr>"
        val result = FreightParser.details("""
            <table><tr><th>Station Name</th><th>Station Code</th><th>Arrival Time</th><th>Zone</th></tr>
            $row$row</table>
        """, "01234567890")
        assertEquals(1, result.journey.size)
        assertEquals("Alpha (AAA)", result.journey.single().station)
    }

    @Test fun distinguishesEmptyChangedAndExpiredResponses() {
        assertThrows(FoisError.Authentication::class.java) {
            FreightParser.details("<form onsubmit='submitLogin()'></form>", "01234567890")
        }
        assertThrows(FoisError.Empty::class.java) {
            FreightParser.details("<p>No records found</p>", "01234567890")
        }
        assertThrows(FoisError.Format::class.java) {
            FreightParser.details("<p>Unexpected page</p>", "01234567890")
        }
    }

    @Test fun encryptionMatchesCryptoJsOpenSslVector() {
        val salt = "12345678".toByteArray()
        assertEquals("U2FsdGVkX18xMjM0NTY3OP9NrjDgk/gDkTXJVt5DU0Y=", cryptoJsPassword("test freight", "example-key", salt))
    }

    @Test fun ecbsLoginUsesVerificationSessionAndEncryption() = MockWebServer().use { server ->
        server.enqueue(MockResponse().addHeader("Set-Cookie", "JSESSIONID=fixture; Path=/ecbs")
            .setBody("<input name=logintimeform value=1795509063000>"))
        server.enqueue(MockResponse().setBody("fixture-image"))
        server.enqueue(MockResponse().setBody("Y"))
        server.enqueue(MockResponse().setBody("example-key"))
        server.enqueue(MockResponse().setBody("<p>Welcome</p>"))
        server.enqueue(MockResponse().setBody("<a href='/ecbs/logout'>Logout</a>"))
        val client = FoisClient(root = server.url("/"))
        assertEquals("fixture-image", client.image(false, true).decodeToString())
        client.signIn("fixtureuser", "fixturepassword", "AB12")
        assertEquals("/ecbs/JSP/LoginNew.jsp", server.takeRequest().path)
        assertEquals("JSESSIONID=fixture", server.takeRequest().getHeader("Cookie"))
        assertEquals("result=AB12", server.takeRequest().body.readUtf8())
        assertEquals("/ecbs/PassSecure", server.takeRequest().path)
        val login = server.takeRequest().body.readUtf8()
        assertTrue(login.contains("txtUserID=FIXTUREUSER"))
        assertTrue(login.contains("logintimeform=1795509063000"))
        assertFalse(login.contains("fixturepassword"))
        assertTrue(login.contains("txtPassword=U2FsdGVkX1"))
        assertTrue(server.takeRequest().path.orEmpty().contains("action=Query"))
    }

    @Test fun rejectedVerificationDoesNotSubmitCredentials() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("N"))
        assertThrows(FoisError.Verification::class.java) {
            FoisClient(root = server.url("/")).signIn("fixtureuser", "fixturepassword", "wrong")
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun publicRouteDoesNotReceiveEcbsCookieAndPostsStandardForm() = MockWebServer().use { server ->
        val url = server.url("/")
        val cookies = FoisCookies(listOf(requireNotNull(okhttp3.Cookie.parse(url, "JSESSIONID=private; Path=/"))))
        server.enqueue(MockResponse().setBody("<table><tr><td>ETA At Destination</td><td>24-11-2026 18:45</td></tr></table>"))
        val result = FoisClient(cookies, url).publicFnr("01234567890", "AB12")
        val request = server.takeRequest()
        assertNull(request.getHeader("Cookie"))
        assertEquals("/FOISWebPortal/FWP_FNREnq", request.path)
        assertEquals("Qry=FNR_ENQUIRY&txtFNRNumb=01234567890&captchaText=AB12", request.body.readUtf8())
        assertEquals("24-11-2026 18:45", result.eta)
    }

    @Test fun recognizesExplicitFreightNumbersAndPreservesLeadingZeroes() {
        val html = "<table><tr><td>FNR No. :</td><td>01234567890</td></tr></table>"
        assertEquals("01234567890", FreightParser.reportedFnr(html))
        assertEquals(null, FreightParser.reportedFnr("<p>Railway receipt 01234567890</p>"))
        val routes = FreightParser.route("""
            <table><tr><th>STTN FROM</th><th>STTN TO</th><th>LOAD TYPE</th><th>Freight Number (FNR)</th></tr>
            <tr><td>RMN</td><td>CFS</td><td>BOX</td><td>01234567890</td></tr>
            <tr><td>RMN</td><td>CFS</td><td>BOX</td><td>1234</td></tr></table>
        """)
        assertEquals("01234567890", routes.first().freight.fnr)
        assertEquals("", routes.last().freight.fnr)
    }

    @Test fun stationRouteUsesAuthorizedInsightAndReturnsAllRows() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""
            <table><tr><th>STTN FROM</th><th>STTN TO</th><th>LOAD TYPE</th><th>LOAD NAME</th><th>EXPD ARVLTIME</th></tr>
            <tr><td>RMN</td><td>CFS</td><td>BOX</td><td>A-1</td><td>24-11-2026 12:00</td></tr>
            <tr><td>RMN</td><td>CFS</td><td>BOX</td><td>A-2</td><td>24-11-2026 16:00</td></tr></table>
        """))
        val result = FoisClient(root = server.url("/")).enquiry(Query("RMN CFS"))
        assertEquals(2, result.size)
        assertEquals("Not Reported", result.first().get("FNR"))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/ecbs/RouterServlet", url.encodedPath)
        assertEquals("insight", url.queryParameter("suboperation"))
        assertEquals("RMN", url.queryParameter("txtSttnFrom"))
        assertEquals("CFS", url.queryParameter("txtSttnTo"))
    }

    @Test fun routeInvoiceEnrichesOnlyReportedReceiptAndFNR() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""
            <table><tr><th>STTN FROM</th><th>STTN TO</th><th>LOAD TYPE</th></tr>
            <tr><td>RMN</td><td>CFS</td><td><a href='QryLoadInvcDtls.jsp?loadid=abc'>BOX</a></td></tr></table>
        """))
        server.enqueue(MockResponse().setBody("""<a onclick="submitForm('CFS','RMN','123456789','24-11-2026')">123456789</a>"""))
        server.enqueue(MockResponse().setBody("<table><tr><td>FNR Number</td><td>01234567890</td></tr></table>"))
        val result = FoisClient(root = server.url("/")).enquiry(Query("RMN CFS")).single()
        assertEquals("01234567890", result.fnr)
        assertEquals("123456789", result.get("Railway receipt"))
        assertEquals("RMN", result.get("Origin"))
    }

    @Test fun routeInvoiceFallsBackToFirstReceiptAndReadsFoisFormattedFnr() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""
            <table><tr><th>STTN FROM</th><th>STTN TO</th><th>LOAD TYPE</th><th>LDNG TIME</th><th>RMNG KM</th></tr>
            <tr><td>RMN</td><td>CFS</td><td><a href='QryLoadInvcDtls.jsp?loadid=fixture'>BOX</a></td>
            <td>24-11-2026 12:00</td><td>415</td></tr></table>
        """))
        server.enqueue(MockResponse().setBody("<p>Invoice without a receipt link</p>"))
        server.enqueue(MockResponse().setBody("<p>No FNR in the POST response</p>"))
        server.enqueue(MockResponse().setBody("""<span class="textBoldMR">01234 567890</span>"""))
        val result = FoisClient(root = server.url("/")).enquiry(Query("RMN CFS")).single()
        assertEquals("01234567890", result.fnr)
        assertEquals("415", result.get("Remaining distance"))
        server.takeRequest()
        server.takeRequest()
        val post = server.takeRequest().body.readUtf8()
        assertTrue(post.contains("txtInvcNo=1"))
        assertTrue(post.contains("txtDate=24-11-2026"))
        val fallback = server.takeRequest()
        assertEquals("GET", fallback.method)
        assertEquals("rrdtls", fallback.requestUrl?.queryParameter("suboperation"))
        assertEquals("fixture", fallback.requestUrl?.queryParameter("LoadID"))
    }

    @Test fun publicStationLookupParsesOfficialDirectoryHeaders() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("<form></form>"))
        server.enqueue(MockResponse().setBody("""
            <table><tr><th><p class="sttnhead">&nbsp;AAA (ALPHA (IN - T) (OUT - T))</p></th></tr></table>
        """))
        assertEquals(listOf("AAA" to "ALPHA"), FoisClient(root = server.url("/")).findStations("alpha", false))
        assertEquals("/FOISWebPortal/pages/FWP_SttnHelp.jsp", server.takeRequest().path)
        val post = server.takeRequest()
        assertEquals("/FOISWebPortal/FWP_SttnHelp", post.path)
        assertEquals("Qry=CRT_LOCN&txtSttnCode=&txtSttnName=ALPHA&hidreset=A", post.body.readUtf8())
    }

    @Test fun freightAlertsUseReportedChangesAndIndianTime() {
        val a = Freight(mapOf("Expected arrival" to "24-11-2026 12:00", "Last reported station" to "AAA"))
        val b = Freight(mapOf("Expected arrival" to "24-11-2026 14:30", "Last reported station" to "BBB",
            "Actual arrival" to "24-11-2026 14:28", "Freight status" to "Arrived"))
        assertEquals(Instant.parse("2026-11-24T06:30:00Z"), freightTime(a.eta))
        assertEquals(setOf(Alert.ETA, Alert.DELAY, Alert.MOVEMENT, Alert.ARRIVED, Alert.STATUS), freightAlerts(a, b).map { it.first }.toSet())
        assertTrue(freightAlerts(b, b).isEmpty())
    }

    @Test fun reportsQuoteCsvAndIncludeJourney() {
        assertEquals("\"'=1+1\"", Reports.csvCell("=1+1"))
        assertEquals("\"A,\"\"B\"\"\"", Reports.csvCell("A,\"B\""))
        val text = Reports.text(Freight(mapOf("FNR" to "01234567890"), listOf(Journey("Alpha", "", "", "", "", ""))))
        assertTrue(text.contains("Journey station: Alpha"))
        assertTrue(text.contains("Report generated"))
    }
}
