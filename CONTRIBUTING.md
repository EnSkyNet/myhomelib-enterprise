# Робота з проєктом

Потрібні JDK 21, зовнішній Maven 3.9.16, Python 3.12 і залежності з `tools/requirements.txt`. Архів вихідного коду не містить Maven runtime/wrapper. Для offline-збірки потрібен локальний репозиторій залежностей.

Читайте [правила розробки](docs/current/DEVELOPMENT.md) та [архітектуру](ARCHITECTURE.md). Додавайте регресійний тест відтвореної помилки; зберігайте контракти міграцій, скасування, відновлення й діагностики. Не розширюйте baseline для приховування порушень.

```bash
python3 tools/project-hygiene-check.py
python3 tools/empty-catch-growth-check.py
python3 tools/vendor-binary-inventory.py --check
python3 tools/source-archive-policy-test.py
xvfb-run -a ./tools/invoke-maven.sh -B -ntp -Pproduction clean verify
python3 tools/source-archive-smoke.py
```

Windows: `tools/invoke-maven.ps1 -B -ntp -Pproduction clean verify` в інтерактивному desktop-сеансі. У Git зберігайте режим 0755 для `.sh`. Фіксуйте кандидат, команди, середовище, результати й пропуски. Не додавайте секретів і приватних каталогів. Заміна binary потребує [окремого огляду](security/README.md). Дивіться [SECURITY.md](SECURITY.md) і [ліцензійний статус](LICENSE.md).
