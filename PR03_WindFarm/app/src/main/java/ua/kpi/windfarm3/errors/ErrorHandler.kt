package ua.kpi.windfarm3.errors

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Централізований обробник помилок: будь-який виняток мережевого шару проходить через
 * handle() — перетворюється на AppError, записується в журнал і Logcat та публікується
 * як подія, на яку UI реагує Snackbar або діалогом.
 */
class ErrorHandler(private val log: ErrorLog) {
    private val _events = MutableSharedFlow<AppError>(extraBufferCapacity = 8)
    val events: SharedFlow<AppError> = _events.asSharedFlow()

    fun handle(source: String, t: Throwable): AppError {
        val e = ErrorMapper.map(t)
        log.log(source, e)
        Log.w(TAG, "$source: $e", t)
        _events.tryEmit(e)
        return e
    }

    /** Повторна спроба теж фіксується в журналі — видно, скільки разів сервер «падав». */
    fun retrying(source: String, attempt: Int, e: AppError, waitMs: Long) {
        log.write(source, "Повтор #$attempt", "${e.title}: повтор через $waitMs мс")
        Log.i(TAG, "$source: retry #$attempt after $waitMs ms — $e")
    }

    fun entries() = log.entries()
    fun clear() = log.clear()

    private companion object { const val TAG = "WindFarmNet" }
}
