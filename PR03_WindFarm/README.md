# ПР3. Repository, REST API, автентифікація, обробка помилок — варіант 2
**«Вітрова електростанція з моніторингом швидкості вітру»**
Виконав: Гаврилюк Михайло Євгенійович, ТВ-31

Android Studio → File → Open → ця тека → Sync → Run (JDK 21). Модульні тести: `app/src/test` → Run.
Потрібен інтернет на емуляторі: дані про вітер беруться з безкоштовного REST API Open-Meteo (без ключа).

| Заняття | Що реалізовано |
|---|---|
| 9. Repository і Data Source | Room (станції + кеш прогнозів), Retrofit; StationRepository обирає джерело: свіжий кеш → мережа → застарілий кеш; FleetRepository: паралельні запити, повтори, агрегація парку, фільтр і сортування; CRUD з валідацією |
| 10. REST API | Retrofit + Gson: `/v1/forecast` (вкладені current/hourly), `/v1/search` (масив results); кеш у пам'яті з TTL; картки станцій з прогнозом виробітку на 24/48 год і вікном для ТО |
| 11. Автентифікація | mocked API в OkHttp-інтерсепторі, JWT (HS256) без бібліотек, токен у SharedPreferences, AuthInterceptor, термін дії, ролі Адміністратор / Оператор / Спостерігач, 401 / 403, підробка токена |
| 12. Помилки мережі | перевірка мережі (ConnectivityManager), ErrorHandler → Snackbar / діалог, повтори з експоненційною затримкою, окремі повідомлення для HTTP-кодів, журнал у файлі `network_errors.log` |

Тестові акаунти (заняття 11): `admin / Admin2026!`, `operator / Oper2026!`, `guest / Guest2026!` (є кнопки автозаповнення).

Пакети: `domain` — бізнес-логіка, `data` — Data Source і Repository (`local` — Room, `remote` — Retrofit), `auth`, `errors`, `s9…s12` — ViewModel та UI.
