# Commands, hotkeys and book actions

The Action Registry centralizes command IDs, shortcuts, visibility and availability predicates. Shortcut conflicts are checked before settings are saved.

Book actions can be grouped into named profiles with an ordered set of commands. They run through `ProcessBuilder` without a command shell. Preview shows the argument vector (`argv`) and executes nothing.
