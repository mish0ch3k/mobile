package ua.kpi.windfarm4.domain

/** Ключі сортування списку об'єктів. */
enum class SortKey(val title: String) {
    NAME("Назва"),
    TYPE("Тип"),
    CAPACITY("Потужність"),
    POWER("Генерація"),
    WIND("Вітер"),
    UPDATED("Оновлено"),
    DISTANCE("Відстань")
}

/**
 * Опис запиту до списку: пошук, багаторівнева фільтрація та сортування за основним
 * і додатковим ключем. Незмінний (immutable) — кожна зміна у UI створює нову копію,
 * а StateFlow у ViewModel перераховує список.
 */
data class ObjectQuery(
    val text: String = "",
    val types: Set<ObjectType> = emptySet(),        // порожньо = усі
    val statuses: Set<ObjectStatus> = emptySet(),
    val regions: Set<String> = emptySet(),
    val minCapacity: Double = 0.0,
    val maxCapacity: Double = Double.MAX_VALUE,
    val sort: SortKey = SortKey.NAME,
    val descending: Boolean = false,
    val thenBy: SortKey? = null
) {
    val activeFilters: Int
        get() = listOf(text.isNotBlank(), types.isNotEmpty(), statuses.isNotEmpty(), regions.isNotEmpty(),
            minCapacity > 0.0, maxCapacity < Double.MAX_VALUE).count { it }
}

object ObjectFilter {

    /** Пошук без урахування регістру за назвою, регіоном та описом; кілька слів — усі мають збігтися. */
    fun matchesText(o: EnergyObject, text: String): Boolean {
        val words = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = "${o.name} ${o.region} ${o.type.title} ${o.description}".lowercase()
        return words.all { it in hay }
    }

    fun matches(o: EnergyObject, q: ObjectQuery): Boolean =
        matchesText(o, q.text) &&
            (q.types.isEmpty() || o.type in q.types) &&
            (q.statuses.isEmpty() || o.status in q.statuses) &&
            (q.regions.isEmpty() || o.region in q.regions) &&
            o.capacityMw >= q.minCapacity && o.capacityMw <= q.maxCapacity

    private fun comparator(key: SortKey, user: UserLocation?): Comparator<EnergyObject> = when (key) {
        SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortKey.TYPE -> compareBy { it.type.ordinal }
        SortKey.CAPACITY -> compareBy { it.capacityMw }
        SortKey.POWER -> compareBy { it.powerMw }
        SortKey.WIND -> compareBy { it.windSpeed }
        SortKey.UPDATED -> compareBy { it.updatedAt }
        SortKey.DISTANCE -> compareBy { o -> user?.let { distanceKm(o, it) } ?: Double.MAX_VALUE }
    }

    /** Фільтрація + сортування; для однакових значень — додатковий ключ, далі за назвою (стабільний порядок). */
    fun apply(list: List<EnergyObject>, q: ObjectQuery, user: UserLocation? = null): List<EnergyObject> {
        var cmp = comparator(q.sort, user)
        if (q.descending) cmp = cmp.reversed()
        q.thenBy?.let { cmp = cmp.then(comparator(it, user)) }
        cmp = cmp.then(comparator(SortKey.NAME, user))
        return list.filter { matches(it, q) }.sortedWith(cmp)
    }

    fun distanceKm(o: EnergyObject, u: UserLocation) = Geo.distanceKm(u.latitude, u.longitude, o.latitude, o.longitude)

    /** Підсумок для вибраних об'єктів (просунутий рівень заняття 15). */
    data class Selection(val count: Int, val capacityMw: Double, val powerMw: Double, val avgWind: Double)

    fun summarize(items: List<EnergyObject>): Selection = Selection(
        count = items.size,
        capacityMw = items.sumOf { it.capacityMw },
        powerMw = items.sumOf { it.powerMw },
        avgWind = items.filter { it.windSpeed > 0 }.map { it.windSpeed }.average().takeIf { !it.isNaN() } ?: 0.0
    )
}
