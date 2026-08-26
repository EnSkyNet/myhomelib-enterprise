# Команди, hotkeys і дії з книгою

Action Registry централізує command ID, shortcut, visibility та context predicate. У налаштуванні гарячих клавіш конфлікти перевіряються до збереження.

Дії з книгою — named profiles з ordered commands. Вони запускаються через `ProcessBuilder` без shell. Preview показує argv і нічого не виконує.
