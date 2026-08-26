# Archives

Books can be cataloged and read directly from ZIP, 7z, RAR/CBR and stream archives (TAR/CPIO plus gzip/bzip2/xz variants). The catalog stores both the physical archive and the internal entry name. Large entries are streamed or spooled to temporary files instead of loading whole archives into memory. Nested archive recursion is intentionally disabled for safety.
