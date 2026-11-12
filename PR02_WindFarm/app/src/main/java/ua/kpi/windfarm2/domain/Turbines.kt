package ua.kpi.windfarm2.domain

/** Демо-дані: турбіни станції та останні виміри швидкості вітру. */
data class Turbine(val id: Int, val name: String, val windSpeed: Double)

object TurbineCatalog {
    val turbines = listOf(
        Turbine(1, "ВТ-01 «Північна»", 4.2),
        Turbine(2, "ВТ-02 «Схил»", 9.8),
        Turbine(3, "ВТ-03 «Гребінь»", 13.5),
        Turbine(4, "ВТ-04 «Узбережна»", 26.1),
        Turbine(5, "ВТ-05 «Долина»", 7.0)
    )

    fun byId(id: Int): Turbine? = turbines.firstOrNull { it.id == id }
}
