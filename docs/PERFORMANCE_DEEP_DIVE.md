# P80 — Performance Deep Dive

## CPU флеймграф (async-profiler)
1. Найти PID `java` процесса приложения.
2. Запустить:
   ```bash
   bash tools/perf/profile_async.sh <PID> 60
   ```
3. Открыть полученный `<svg>` флеймграф и найти «горячие» стеки.

## JFR
```bash
bash tools/perf/record_jfr.sh <PID> 60
```
Открыть запись в JDK Mission Control или IntelliJ IDEA.

## k6 budgets
- Workflow: **Performance Budgets (k6 + server SLO)**.
- Пороговые значения: `p95 < 1500ms`, `http_req_failed < 2%`.

## Рекомендации
- Профилировать под реальной нагрузкой (k6).
- Следить за GC-паузами (JFR) и медленным I/O (флеймграф).
- Переносить блокирующие операции в `Dispatchers.IO`, добавлять кэширование и лимиты пулов.
