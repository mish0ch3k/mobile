# Підготовка до релізу — «Вітрова ЕС» (ПР6, заняття 27)

## 1. Ключ підпису (keystore)
Ключ створюється один раз і зберігається поза репозиторієм. Його втрата означає, що оновлення застосунку в Google Play стануть неможливими (якщо не ввімкнено Play App Signing).

```bash
mkdir keystore
keytool -genkeypair -v -keystore keystore/windfarm-release.jks -alias windfarm \
        -keyalg RSA -keysize 4096 -validity 10000 \
        -dname "CN=Mykhailo Havryliuk, OU=TV-31, O=KPI, L=Kyiv, C=UA"
```

Потім скопіюйте `keystore.properties.example` у `keystore.properties` і впишіть паролі. Файл додано до `.gitignore`.
`app/build.gradle.kts` читає його і підставляє в `signingConfigs.release`. Якщо файлу немає, release підписується debug-ключем: так збірка не падає, але таку збірку не можна публікувати (екран «Заняття 27 → Просунутий» показує це як ✗).

## 2. Варіанти збірки
| Варіант | applicationId | Призначення |
|---|---|---|
| `demoDebug` / `demoRelease` | `ua.kpi.windfarm6.demo` | демо: до 3 станцій (`BuildConfig.MAX_STATIONS`) |
| `fullDebug` / `fullRelease` | `ua.kpi.windfarm6` | повна версія |

Debug-збірки мають суфікс `.debug`, тож можуть стояти на пристрої поруч із release.

## 3. Оптимізація та захист коду
- `isMinifyEnabled = true`: R8 видаляє невикористаний код і скорочує назви класів (обфускація).
- `isShrinkResources = true` видаляє невикористані ресурси.
- `proguard-rules.pro` зберігає серіалізатори kotlinx.serialization і номери рядків у стек-трейсах, а також видаляє `Log.d/v`.
- Перевірка: на екрані «Заняття 27 → Середній» у release ім'я класу `WindPowerCalculator` замінюється коротким (наприклад, `a2.c`).

## 4. Збірка
Android Studio: **Build → Generate Signed App Bundle / APK** → App Bundle → ключ → варіант `fullRelease`.
Командний рядок:
```bash
./gradlew bundleFullRelease          # app/build/outputs/bundle/fullRelease/app-full-release.aab
./gradlew assembleFullRelease        # APK для встановлення на тестові пристрої
adb install app/build/outputs/apk/full/release/app-full-release.apk
```

## 5. Тестування перед релізом
```bash
./gradlew testFullDebugUnitTest                    # юніт-тести
./gradlew connectedFullDebugAndroidTest            # UI-тести на емуляторі / пристрої
./gradlew createFullDebugUnitTestCoverageReport    # покриття (HTML у app/build/reports/coverage)
```
Сумісність: minSdk 24 (Android 7.0), targetSdk 35 (Android 15). Перевірити на емуляторах з API 24, 30 і 35, а також на планшеті (ширина ≥ 600 dp).

## 6. Метадані Google Play
Тексти лежать у `metadata/uk-UA/` (назва ≤ 30 символів, короткий опис ≤ 80, повний ≤ 4000, примітки до версії).
Графіка: іконка 512×512 PNG; feature graphic 1024×500; від 2 до 8 скріншотів телефону (16:9 або 9:16, сторона 320–3840 px).
Також потрібно заповнити: політику конфіденційності (застосунок не збирає персональних даних), анкету вікового рейтингу та розділ Data safety (дані не передаються).

## 7. Публікація
Play Console → «Створити застосунок» → завантажити `.aab` у **внутрішнє тестування** (до 100 тестувальників, без перевірки).
Далі **закрите тестування** (запрошені групи), потім **відкрите**, потім **продакшн**. Для кожного нового завантаження `versionCode` збільшується (зараз 6).
