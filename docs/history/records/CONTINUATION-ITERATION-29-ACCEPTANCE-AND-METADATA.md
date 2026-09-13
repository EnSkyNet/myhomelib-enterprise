# Продовження після Iteration 29

Почати з `ITERATION-29-BATCH-METADATA-EDITOR-CHECKPOINT.md` і `verification/iteration29/`.

1. Надати JDK 21 для Linux x64 або виконати перевірки на власному JDK 21 host. Запустити `./mvnw -o -B -ntp -Dmaven.repo.local=/absolute/path/maven-offline-repo verify`. Якщо cache неповний для цієї платформи, доповнити лише названі Maven артефакти. Не знижувати release у POM.
2. Прогнати affected application/domain/infrastructure/ui reactor і наявний повний infrastructure regression. Окремо перевірити відомий Lucene test-order hang; цільовий PASS не є доказом його повного усунення.
3. JavaFX acceptance: вибір 1/кількох/100 книг; часткова помилка провайдера; cancel lookup/save; preview без default selected fields; різні поля для різних книг; зміна колекції; стан після commit; маленький екран і Windows DPI 100/125/150/200.
4. Завершити повний MHL-111: bulk set/clear/regex/trim/case/language/genre/series/tags для 10000+ книг із sample preview та обмеженим використанням пам’яті. Побудувати спільний журнал/undo MHL-112 до закриття acceptance.
5. MHL-113/114/116/117: повторно використати source monitor, SavedSearch, duplicate hash scanner, integrity/maintenance. Ці основи вже є, але не закривають повні нові сценарії.
6. Для MHL-504/505/506 розширювати ExportProfileService, ExportToDeviceUseCase та BookConverter port. Не створювати другий export/convert pipeline без окремого обґрунтування.
7. Виконати Windows installer/portable та live GitHub CI/branch protection/SBOM/dependency-check/CodeQL acceptance. Зберегти URL run і артефакти результатів; наявність workflow source не дорівнює PASS.

Для повторної перевірки корпусу потрібні початковий `flibusta_online_fb2.inpx` і два FB2 ZIP, надані разом із задачею. Вони залишені поза source package.
