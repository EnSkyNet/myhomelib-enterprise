# Unified filters

Language, year, format, local availability, reading state, rating range and unrated-book filters share one `BookFilterSpec` across navigation, table SQL queries and Lucene.

The table quick filter changes the same state. `AND` requires all active conditions; `OR` accepts a book that matches at least one active condition. Reset returns the profile to its default state.
