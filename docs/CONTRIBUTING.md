# CONTRIBUTING

## Как избегать конфликтов при работе с Codex

* Перед началом работы синхронизируйте ветку с основной: `git fetch origin && git rebase origin/main`. Это помогает получать последние изменения и уменьшает вероятность конфликтов.
* Не пользуйтесь кнопкой «Update branch» в Pull Request. Предпочитайте стратегию «Rebase and merge», а в настройках репозитория включите опцию «Require linear history».
* Для быстрого разрешения конфликтов используйте команды:
  * Принять локальную версию файла: `git checkout --ours path/to/file`
  * Принять версию из удалённой ветки: `git checkout --theirs path/to/file`
  * После выбора нужной версии выполните `git add path/to/file`, затем продолжайте процесс (`git rebase --continue` или `git commit`).
  * Чтобы отменить текущий процесс ребейза, используйте `git rebase --abort`.
