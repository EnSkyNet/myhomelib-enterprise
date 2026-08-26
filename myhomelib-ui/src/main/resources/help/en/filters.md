# Unified filters

Language, year, format, local/read state, rating range and unrated filtering share one `BookFilterSpec` across navigation, SQL tables and Lucene. Column quick filters modify the same state. AND requires all conditions; OR accepts any active condition.
