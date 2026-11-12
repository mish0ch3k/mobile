package ua.kpi.windfarm3.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import ua.kpi.windfarm3.errors.NoConnectionException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

/** Перевірка наявності мережі через ConnectivityManager (дозвіл ACCESS_NETWORK_STATE). */
class NetworkMonitor(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun isOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Потік стану мережі: UI показує індикатор і реагує на появу / зникнення зв'язку. */
    fun observe(): Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isOnline()) }
            override fun onLost(network: Network) { trySend(isOnline()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(isOnline()) }
        }
        trySend(isOnline())
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

/**
 * Перевірка підключення перед кожним запитом: без мережі запит навіть не починається,
 * а користувач одразу отримує зрозуміле повідомлення замість тайм-ауту.
 */
class ConnectivityInterceptor(private val monitor: NetworkMonitor) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!monitor.isOnline()) throw NoConnectionException()
        return chain.proceed(chain.request())
    }
}

/** Сценарії збоїв для демонстрації обробки помилок (заняття 12). */
enum class FaultScenario(val title: String) {
    NONE("Без збоїв"),
    HTTP_401("401"),
    HTTP_403("403"),
    HTTP_429("429"),
    HTTP_500("500"),
    FLAKY_503("503 → OK"),
    TIMEOUT("Тайм-аут"),
    UNKNOWN_HOST("Немає DNS")
}

/**
 * Імітація збоїв сервера на рівні OkHttp (fault injection): дає змогу показати реакцію
 * застосунку на різні HTTP-коди без власного сервера. FLAKY_503 двічі повертає 503,
 * а третій запит пропускає до реального API — так видно роботу повторних спроб.
 */
class FaultInjectionInterceptor : Interceptor {
    @Volatile var scenario: FaultScenario = FaultScenario.NONE
        set(value) { field = value; flakyCounter.set(0) }
    private val flakyCounter = AtomicInteger(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        fun fake(code: Int, reason: String) = Response.Builder()
            .request(req).protocol(Protocol.HTTP_1_1).code(code).message(reason)
            .body("""{"error":true,"reason":"$reason"}""".toResponseBody("application/json".toMediaType()))
            .build()
        return when (scenario) {
            FaultScenario.NONE -> chain.proceed(req)
            FaultScenario.HTTP_401 -> fake(401, "Unauthorized")
            FaultScenario.HTTP_403 -> fake(403, "Forbidden")
            FaultScenario.HTTP_429 -> fake(429, "Too Many Requests")
            FaultScenario.HTTP_500 -> fake(500, "Internal Server Error")
            FaultScenario.FLAKY_503 -> if (flakyCounter.incrementAndGet() <= 2) fake(503, "Service Unavailable") else chain.proceed(req)
            FaultScenario.TIMEOUT -> { Thread.sleep(1500); throw SocketTimeoutException("timeout (імітація)") }
            FaultScenario.UNKNOWN_HOST -> throw UnknownHostException("Unable to resolve host \"${req.url.host}\" (імітація)")
        }
    }
}
