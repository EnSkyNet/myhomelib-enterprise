# Import

You can import an individual file, directory, INPX or archive. The format registry covers FB2/FBD, EPUB, TXT/TEXT/Markdown, PDF, MP3, M4B, MOBI/PRC, AZW/AZW3, DjVu, DOC/DOCX, ODT, RTF, HTML/XHTML, CHM, CBZ/CBR and other registered types, with format-specific metadata, cover, full-text and Reader capabilities. Built-in reading of DRM-free MOBI/PRC/AZW/AZW3 works for uncompressed content or PalmDOC; HUFF/CDIC requires conversion.

Archives include ZIP/FB2ZIP/CBZ/JAR, 7z, RAR/CBR, TAR, TAR.GZ/TGZ, TAR.BZ2/TBZ2, TAR.XZ/TXZ and CPIO. A book can be stored as an entry inside a container. Large entries are streamed or spooled through bounded temporary files.

Directory import and large INPX processing use bounded batches so the whole library is not accumulated in memory. The search index is rebuilt once after a bulk operation.
