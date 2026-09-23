# INPX and online libraries

MyHomeLib processes every `.inp` part inside an INPX package and respects `structure.info` and `archives.info`. A book record keeps the physical archive/file together with its internal `archiveEntry`. Stable `LibID` is preserved during catalog refresh, so personal ratings, progress, reviews, groups and bookmarks should not be discarded by a normal re-import.

An online collection can define a base URL or templates containing `{archive}`, `{file}` and `{entry}`. If the required book or archive is not local, MyHomeLib downloads the resource into the collection before opening it. Downloads use an intermediate `.part` file; cancellation or failure must not leave an incomplete file behind.
