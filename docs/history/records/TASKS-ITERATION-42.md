# Iteration 42 — External acceptance evidence hardening

**Дата:** 11.09.2026  
**База:** Iteration 41  
**Статус зовнішнього backlog:** без змін — MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019 залишаються відкритими до фактичного GitHub + Windows PASS.

## Scope

Це локальне hardening external-acceptance pipeline, без вигаданих нових MHL-номерів і без підміни live acceptance.

1. **Bounded ZIP safety**
   - один shared helper для GitHub ingest, nested Windows evidence та final reviewer bundle;
   - reject absolute/drive/traversal paths, duplicate normalized names, encrypted members, symlink/device-like members;
   - bounded member count, per-member uncompressed size і total uncompressed size.

2. **Windows session chronology**
   - усі requested installer/portable/desktop/DPI reports мають timezone-aware ISO-8601 timestamp;
   - report timestamp не може передувати `windows-host-binding` тієї самої acceptance session.

3. **Closed evidence sets**
   - live Windows evidence directories не можуть містити невраховані файли;
   - nested Windows ZIP містить лише fixed reports + файли, на які реально посилаються report JSON;
   - outer reviewer ZIP має exact member set, а не лише required subset.

4. **Ratchets**
   - ZIP helper входить у candidate-bound `acceptance-harness.sha256`;
   - PR CI запускає окремий ZIP-safety regression;
   - supply-chain/static/harness checks вимагають нові fail-closed contracts.

## Не входить у scope

- фактичний GitHub Actions PASS;
- Windows installer/DPI/desktop live run;
- зміна статусів шести external backlog items;
- production Java functionality.
