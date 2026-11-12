# ПР2. Меню, форми, MVVM, локальне сховище — варіант 2
**«Вітрова електростанція з моніторингом швидкості вітру»**
Виконав: Гаврилюк Михайло Євгенійович, ТВ-31

Android Studio → File → Open → ця тека → Sync → Run. Unit-тести: `app/src/test`.

| Заняття | Що реалізовано |
|---|---|
| 5. Меню і навігація | NavHost/NavController; аргументи `details/{id}`; повернення результату через `savedStateHandle`; адаптивна навігація (NavigationBar / NavigationRail, master-detail) |
| 6. Форми | валідація (непорожні, формат e-mail, довжина й складність пароля, унікальність e-mail), helper text, індикатор завантаження |
| 7. MVVM | ViewModel + StateFlow, `combine`/`map` похідних станів, корутини, стани Loading/Success/Error, моніторинг у реальному часі |
| 8. Room | Entity/DAO/Database (KSP), Repository: локальне + віддалене джерело, кеш із TTL, CRUD, пошук, фільтр, сортування, статистика |

Пакети: `domain` — бізнес-логіка, `data` — Room і репозиторії, `s7/viewmodel` — ViewModel, `s5…s8` — UI.
