package ua.kpi.windfarm3

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.kpi.windfarm3.auth.Jwt
import ua.kpi.windfarm3.auth.MockAuthBackend
import ua.kpi.windfarm3.auth.Permission
import ua.kpi.windfarm3.auth.Role
import ua.kpi.windfarm3.auth.TokenClaims
import ua.kpi.windfarm3.data.remote.CurrentDto
import ua.kpi.windfarm3.data.remote.ForecastDto
import ua.kpi.windfarm3.data.remote.GeoPlaceDto
import ua.kpi.windfarm3.data.remote.GeoResponseDto
import ua.kpi.windfarm3.data.remote.HourlyDto
import ua.kpi.windfarm3.data.remote.OpenMeteoMapper
import ua.kpi.windfarm3.domain.ForecastAnalytics
import ua.kpi.windfarm3.domain.HourlyWind
import ua.kpi.windfarm3.domain.StationValidator
import ua.kpi.windfarm3.errors.ApiHttpException
import ua.kpi.windfarm3.errors.AppError
import ua.kpi.windfarm3.errors.ErrorMapper
import ua.kpi.windfarm3.errors.FileErrorLog
import ua.kpi.windfarm3.errors.InvalidDataException
import ua.kpi.windfarm3.errors.NoConnectionException
import ua.kpi.windfarm3.errors.RetryPolicy
import java.io.File
import java.net.SocketTimeoutException

/** Заняття 9: перевірка введення станції. */
class StationValidatorTest {
    @Test fun validValues() {
        assertTrue(StationValidator.errors("ВЕС Тест", "46.7", "35,25", "20").all { it == null })
    }

    @Test fun invalidValues() {
        val e = StationValidator.errors("ВЕ", "123", "x", "0")
        assertEquals(4, e.count { it != null })
        assertNotNull(StationValidator.turbines("2.5"))
        assertNull(StationValidator.longitude("-180"))
    }
}

/** Бізнес-логіка прогнозу виробітку. */
class ForecastAnalyticsTest {
    private fun h(hour: Int, v: Double) = HourlyWind("2026-11-11T%02d:00".format(hour), v, 15.0)

    @Test fun energyOfRatedHours() {
        // 3 години на номіналі × 10 турбін × 2 МВт = 60 МВт·год, КВВП 100 %
        val f = ForecastAnalytics.forecast(listOf(h(0, 15.0), h(1, 14.0), h(2, 20.0)), 10)
        assertEquals(60.0, f.energyMwh, 1e-9)
        assertEquals(100.0, f.capacityFactor, 1e-9)
        assertEquals(3, f.productiveHours)
    }

    @Test fun stormAndCalmGiveNoEnergy() {
        val f = ForecastAnalytics.forecast(listOf(h(0, 1.0), h(1, 26.0)), 5)
        assertEquals(0.0, f.energyMwh, 1e-9)
        assertEquals(1, f.stormHours)
        assertEquals(26.0, f.peak!!.speed100, 1e-9)
    }

    @Test fun filterSortAndMaintenanceWindow() {
        val hours = listOf(h(0, 9.0), h(1, 2.0), h(2, 1.0), h(3, 1.5), h(4, 12.0))
        assertEquals(listOf(0, 4), ForecastAnalytics.productive(hours).map { it.hourLabel.take(2).toInt() })
        assertEquals(12.0, ForecastAnalytics.sort(hours, ForecastAnalytics.Sort.SPEED_DESC).first().speed100, 1e-9)
        val w = ForecastAnalytics.maintenanceWindow(hours, 3)!!
        assertEquals("01:00", w.first().hourLabel)
        assertNull(ForecastAnalytics.maintenanceWindow(hours.take(2), 3))
    }
}

/** Заняття 10: перевірка та перетворення JSON-моделей. */
class MapperTest {
    private val current = CurrentDto("2026-11-11T14:15", 6.2, 225.0, 11.0, 8.5)

    @Test fun mapsValidForecast() {
        val dto = ForecastDto(46.7, 35.25, "Europe/Kyiv", 20.0, null, current,
            HourlyDto(listOf("2026-11-11T14:00", "2026-11-11T15:00", "2026-11-11T16:00"), listOf(9.0, null, 11.0), listOf(8.0, 7.5, 7.0)))
        val f = OpenMeteoMapper.toForecast(dto)
        assertEquals(2, f.hourly.size) // година з null відкинута
        assertEquals("ПдЗх", f.current.directionName)
    }

    @Test(expected = InvalidDataException::class)
    fun rejectsMissingCurrent() { OpenMeteoMapper.toForecast(ForecastDto(1.0, 1.0, null, null, null, null, null)) }

    @Test(expected = InvalidDataException::class)
    fun rejectsDifferentArrayLengths() { OpenMeteoMapper.toHourly(HourlyDto(listOf("a", "b"), listOf(1.0), null)) }

    @Test(expected = InvalidDataException::class)
    fun rejectsNegativeWind() { OpenMeteoMapper.toForecast(ForecastDto(1.0, 1.0, null, null, null, current.copy(windSpeed10m = -3.0), null)) }

    @Test fun placesWithoutCoordinatesAreSkipped() {
        val r = GeoResponseDto(listOf(
            GeoPlaceDto(1, "Одеса", 46.48, 30.73, 0.0, "Україна", "Одеська область", 1_000_000),
            GeoPlaceDto(2, "Без координат", null, null, null, null, null, null)))
        assertEquals(listOf("Одеса"), OpenMeteoMapper.toPlaces(r).map { it.name })
        assertTrue(OpenMeteoMapper.toPlaces(GeoResponseDto(null)).isEmpty())
    }
}

/** Заняття 11: токен і розмежування доступу. */
class AuthTest {
    private val secret = "test-secret".toByteArray()
    private var now = 1_000_000L
    private val backend = MockAuthBackend(secret) { now }

    @Test fun signAndVerify() {
        val c = TokenClaims("operator", "Оператор \"Ч\"", Role.OPERATOR, now, now + 60)
        val t = Jwt.sign(c, secret)
        assertEquals(3, t.split('.').size)
        assertEquals(Jwt.Verification.Valid(c), Jwt.verify(t, secret, now))
        assertEquals(c, Jwt.decode(t))
        assertTrue(Jwt.verify(t, secret, now + 60) is Jwt.Verification.Expired)
        assertTrue(Jwt.verify(t, "other".toByteArray(), now) is Jwt.Verification.Invalid)
        assertTrue(Jwt.verify("abc", secret, now) is Jwt.Verification.Invalid)
    }

    @Test fun loginErrors() {
        assertEquals(400, (backend.login("", "") as MockAuthBackend.LoginResult.Failure).code)
        assertEquals(401, (backend.login("admin", "wrong") as MockAuthBackend.LoginResult.Failure).code)
    }

    @Test fun roleBasedAccess() {
        val guest = (backend.login("guest", "Guest2026!") as MockAuthBackend.LoginResult.Success).token
        val admin = (backend.login("admin", "Admin2026!") as MockAuthBackend.LoginResult.Success).token
        assertTrue(backend.authorize("Bearer $guest", Permission.VIEW_SUMMARY) is MockAuthBackend.Access.Granted)
        assertEquals(403, (backend.authorize("Bearer $guest", Permission.VIEW_TELEMETRY) as MockAuthBackend.Access.Denied).code)
        assertTrue(backend.authorize("Bearer $admin", Permission.CONTROL_TURBINES) is MockAuthBackend.Access.Granted)
        assertEquals(401, (backend.authorize(null, Permission.VIEW_SUMMARY) as MockAuthBackend.Access.Denied).code)
    }

    @Test fun expiredAndForgedTokensRejected() {
        val t = (backend.login("operator", "Oper2026!", ttlSeconds = 30) as MockAuthBackend.LoginResult.Success).token
        now += 31
        assertEquals(401, (backend.authorize("Bearer $t", Permission.VIEW_SUMMARY) as MockAuthBackend.Access.Denied).code)
        now -= 31
        val p = t.split('.')
        val forgedPayload = String(java.util.Base64.getUrlDecoder().decode(p[1])).replace("OPERATOR", "ADMIN")
        val forged = p[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(forgedPayload.toByteArray()) + "." + p[2]
        assertEquals(Role.ADMIN, Jwt.decode(forged)!!.role) // клієнт бачить «адміна»…
        assertEquals(401, (backend.authorize("Bearer $forged", Permission.CONTROL_TURBINES) as MockAuthBackend.Access.Denied).code) // …а сервер — ні
    }
}

/** Заняття 12: обробка помилок, повтори, журнал. */
class ErrorHandlingTest {
    @Test fun mapsExceptions() {
        assertEquals(AppError.NoInternet, ErrorMapper.map(NoConnectionException()))
        assertEquals(AppError.Timeout, ErrorMapper.map(SocketTimeoutException()))
        val e = ErrorMapper.map(ApiHttpException(503, null)) as AppError.Http
        assertEquals(503, e.code); assertTrue(e.retryable)
        assertFalse(ErrorMapper.http(404).retryable)
        assertFalse(ErrorMapper.http(401).retryable)
        assertTrue(ErrorMapper.http(429).retryable)
        assertTrue(ErrorMapper.http(400, "Latitude must be in range").userMessage.contains("Latitude"))
    }

    @Test fun retriesTransientErrors() = runBlocking {
        var calls = 0
        val retries = mutableListOf<Int>()
        val r = RetryPolicy(maxAttempts = 4, initialDelayMs = 1).run({ a, _, _ -> retries += a }) {
            calls++
            if (calls < 3) throw ApiHttpException(503, null) else "ok"
        }
        assertEquals("ok", r); assertEquals(3, calls); assertEquals(listOf(1, 2), retries)
    }

    @Test fun doesNotRetryClientErrors() = runBlocking {
        var calls = 0
        try {
            RetryPolicy(maxAttempts = 3, initialDelayMs = 1).run { calls++; throw ApiHttpException(404, null) }
        } catch (e: ApiHttpException) { assertEquals(404, e.code) }
        assertEquals(1, calls)
    }

    @Test fun exponentialDelay() {
        val p = RetryPolicy(initialDelayMs = 500, maxDelayMs = 1500)
        assertEquals(listOf(500L, 1000L, 1500L), (1..3).map(p::delayFor))
    }

    @Test fun fileLogKeepsNewestFirstAndTrims() {
        val f = File.createTempFile("errors", ".log").apply { deleteOnExit() }
        val log = FileErrorLog(f, maxLines = 3)
        repeat(5) { log.log("test$it", ErrorMapper.http(500)) }
        val entries = log.entries()
        assertEquals(3, entries.size)
        assertEquals("test4", entries.first().source)
        log.clear(); assertTrue(log.entries().isEmpty())
    }
}
