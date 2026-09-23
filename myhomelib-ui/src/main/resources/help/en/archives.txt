# Archives

The catalog, Reader, cover handling and export use the same archive-resource layer. Supported containers include ZIP/FB2ZIP/CBZ/JAR, 7z, RAR/CBR, TAR, TAR.GZ/TGZ, TAR.BZ2/TBZ2, TAR.XZ/TXZ and CPIO. A book is stored as the physical container plus its internal `archiveEntry`, so reading or export does not require manually unpacking the whole archive.

Large entries are streamed or spooled to a temporary file instead of loading the whole archive into memory. Recursive extraction of nested archives is intentionally disabled for safety.
