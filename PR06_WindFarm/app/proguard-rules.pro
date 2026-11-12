# Правила R8 для release-збірки.
# kotlinx.serialization постачає власні consumer-правила; нижче — те, що потрібно саме цьому застосунку.

# Моделі, що серіалізуються в JSON: зберегти серіалізатори (назви полів задані @SerialName, тож обфускація полів безпечна)
-keepclassmembers @kotlinx.serialization.Serializable class ua.kpi.windfarm6.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Номери рядків у стек-трейсах (для аналізу збоїв), але без реальних імен файлів
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Видалити журналювання з release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
