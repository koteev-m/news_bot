# P86 — AI-assisted Governance (RCA + Policy Summary)

## Состав
- `tools/ai_gov/rca_collect.py` — собирает артефакты (gov/cv/dlp/security/audit/finops).
- `tools/ai_gov/llm_summarize.py` — формирует отчёт RCA (LLM при наличии `OPENAI_API_KEY`, иначе эвристика).
- `tools/ai_gov/policy_summarizer.py` — дайджест правил Rego (policy-as-code).

## CI
- `AI Governance Weekly` — еженедельный отчёт; можно запустить вручную, добавив `with_llm=true` и секрет `OPENAI_API_KEY`.

## Безопасность
- Скрипты не отправляют конфиденциальные артефакты наружу без явного ключа LLM.
- Рекомендуется анонимизировать/редактировать журналы до загрузки в CI артефакты.

## Использование
```bash
python3 tools/ai_gov/rca_collect.py
python3 tools/ai_gov/llm_summarize.py
python3 tools/ai_gov/policy_summarizer.py
```
