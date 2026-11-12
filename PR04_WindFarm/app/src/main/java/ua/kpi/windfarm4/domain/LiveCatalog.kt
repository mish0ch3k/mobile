package ua.kpi.windfarm4.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * Імітація телеметрії: кожні [periodMs] змінюються вітер, статус і час оновлення частини об'єктів.
 * Список у UI підписаний на цей потік, тому картки й маркери оновлюються самі.
 */
class LiveCatalog(
    private val initial: List<EnergyObject> = EnergyCatalog.initial(),
    private val periodMs: Long = 4000,
    private val seed: Int = 11,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun updates(): Flow<List<EnergyObject>> = flow {
        val rnd = Random(seed)
        var list = initial
        emit(list)
        while (true) {
            delay(periodMs)
            list = step(list, rnd, clock())
            emit(list)
        }
    }

    companion object {
        private val spec = TurbineSpec()

        /** Один крок моделювання: оновлюється ~третина об'єктів, що вимірюють вітер. */
        fun step(list: List<EnergyObject>, rnd: Random, now: Long): List<EnergyObject> = list.map { o ->
            if (o.type == ObjectType.SUBSTATION || o.type == ObjectType.STORAGE || o.status == ObjectStatus.OFFLINE || o.status == ObjectStatus.MAINTENANCE || rnd.nextInt(3) != 0) o
            else {
                val wind = round1((o.windSpeed + rnd.nextDouble(-1.5, 1.8)).coerceIn(0.0, 30.0))
                val status = when {
                    wind >= spec.cutOutSpeed -> ObjectStatus.STORM_STOP
                    o.status == ObjectStatus.STORM_STOP && wind < spec.cutOutSpeed - 3 -> ObjectStatus.WORKING // повторний пуск з гістерезисом
                    else -> o.status
                }
                o.copy(windSpeed = wind, status = status, updatedAt = now)
            }
        }
    }
}
