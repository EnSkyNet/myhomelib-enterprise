# Матеріали сторонніх компонентів

Окрім залежностей Maven, проєкт містить binaries у `tools/`:

- EPUBCheck та 39 залежних JAR: `tools/validators/epubcheck/`. Версії, SHA-256 і ліцензійні матеріали зазначено в [binary-manifest.json](security/binary-manifest.json). Разом — 40 JAR.
- `tools/converters/libmobi/mobitool.exe`: збережено `COPYING` з LGPL-3.0; точне походження збірки й runtime-версію не підтверджено. Це окремий незавершений пункт огляду.
- Граф Maven: агрегований CycloneDX SBOM створюється профілем `sbom`. [Додатковий vendor SBOM](security/vendor-bom.cdx.json) обліковує вбудовані binaries поза цим графом.

Цей перелік не замінює оригінальних ліцензійних текстів і не підтверджує виконання всіх умов розповсюдження. Збережіть notices і перевірте фінальний дистрибутив. Загальний статус наведено в [LICENSE.md](LICENSE.md).
