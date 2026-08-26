@echo off
setlocal
cd /d "%~dp0"

set "SQLITE=myhomelib-infrastructure\src\main\java\com\myhomelibcorp\infrastructure\persistence\sqlite\SqliteBookQueryRepository.java"
set "PG=myhomelib-infrastructure\src\main\java\com\myhomelibcorp\infrastructure\persistence\postgres\PostgresBookRepository.java"
set "WRITER=myhomelib-infrastructure\src\main\java\com\myhomelibcorp\infrastructure\importengine\JdbcBatchWriter.java"
set "NETWORK=myhomelib-application\src\main\java\com\myhomelibcorp\application\usecase\collection\UpdateCollectionFromNetworkUseCase.java"
set "CREATECOL=myhomelib-application\src\main\java\com\myhomelibcorp\application\usecase\collection\CreateCollectionUseCase.java"
set "DOWNLOADER=myhomelib-infrastructure\src\main\java\com\myhomelibcorp\infrastructure\download\HttpRemoteCatalogDownloadAdapter.java"
set "PIPELINE=myhomelib-infrastructure\src\main\java\com\myhomelibcorp\infrastructure\importengine\InpxImportPipeline.java"
set "READER=myhomelib-infrastructure\src\main\java\com\myhomelibcorp\infrastructure\importengine\InpxReader.java"
set "MAPPER=myhomelib-infrastructure\src\main\java\com\myhomelibcorp\infrastructure\persistence\mapper\BookRowMapper.java"
set "METACFG=myhomelib-infrastructure\src\main\java\com\myhomelibcorp\infrastructure\config\MetadataDatabaseConfig.java"
set "COLREPO=myhomelib-infrastructure\src\main\java\com\myhomelibcorp\infrastructure\persistence\sqlite\SqliteCollectionRepository.java"
set "AUTHORREPO=myhomelib-infrastructure\src\main\java\com\myhomelibcorp\infrastructure\persistence\sqlite\SqliteAuthorRepository.java"
set "GROUPBATCH=myhomelib-application\src\main\java\com\myhomelibcorp\application\usecase\group\AddToGroupBatchUseCase.java"

echo [1/7] Removing stale Postgres source if present...
if exist "%PG%" (
  del /f /q "%PG%"
  if exist "%PG%" (
    echo ERROR: Cannot delete stale file: %PG%
    exit /b 2
  )
  echo Removed: %PG%
) else (
  echo OK: stale PostgresBookRepository.java is absent.
)

echo [2/7] Verifying SqliteBookQueryRepository hotfix...
findstr /C:"SqliteBookQueryRepository.this.findPage(query).content();" "%SQLITE%" >nul
if errorlevel 1 (
  echo ERROR: Updated findPage call is NOT present in:
  echo   %SQLITE%
  echo You are probably building an older project tree.
  exit /b 3
)
findstr /C:"SqliteBookQueryRepository.this.find(query);" "%SQLITE%" >nul
if not errorlevel 1 (
  echo ERROR: Old find(query) call is still present in:
  echo   %SQLITE%
  exit /b 4
)
echo OK: source hotfix is present.

echo [3/7] Verifying BookQueryRepository test call sites...
findstr /S /N /C:".find(query);" myhomelib-infrastructure\src\test\java\*.java >nul
if not errorlevel 1 (
  echo ERROR: A stale .find(query) call remains in infrastructure tests.
  findstr /S /N /C:".find(query);" myhomelib-infrastructure\src\test\java\*.java
  exit /b 5
)
findstr /C:"bookQueryRepository.findPage(query).content();" "myhomelib-infrastructure\src\test\java\com\myhomelibcorp\infrastructure\persistence\sqlite\DatabaseTest.java" >nul
if errorlevel 1 (
  echo ERROR: DatabaseTest.java does not contain the updated findPage call.
  exit /b 6
)
findstr /C:"repository.findPage(query).content();" "myhomelib-infrastructure\src\test\java\com\myhomelibcorp\infrastructure\persistence\sqlite\SqliteBookQueryRepositoryTest.java" >nul
if errorlevel 1 (
  echo ERROR: SqliteBookQueryRepositoryTest.java does not contain the updated findPage call.
  exit /b 7
)
echo OK: test call sites use findPage(...).content().

echo [4/7] Verifying INPX author hot-path fix...
findstr /C:"first_name = ? AND last_name = ?" "%WRITER%" >nul
if errorlevel 1 (
  echo ERROR: Index-friendly author lookup is missing in %WRITER%
  exit /b 8
)
findstr /C:"COALESCE(first_name,'') = ?" "%WRITER%" >nul
if not errorlevel 1 (
  echo ERROR: Non-indexable COALESCE author lookup is still present in %WRITER%
  exit /b 9
)
findstr /C:"private record AuthorPair" "%WRITER%" >nul
if errorlevel 1 (
  echo ERROR: Pipe-safe AuthorPair key is missing in %WRITER%
  exit /b 10
)
findstr /C:".batchSize(5000)" "%NETWORK%" >nul
if errorlevel 1 (
  echo ERROR: Online catalog update does not use the 5000-row import batch.
  exit /b 11
)
findstr /C:".batchSize(5000)" "%CREATECOL%" >nul
if errorlevel 1 (
  echo ERROR: Create-with-source does not use the 5000-row import batch.
  exit /b 12
)
echo OK: INPX author resolver uses the SQLite index and pipe-safe keys; catalog paths use 5000-row batches.

echo [5/7] Verifying MyHomeLib/Flibusta online-update protocol...
findstr /C:"FLIBUSTA_FULL_INPX" "%DOWNLOADER%" >nul || goto :online_error
findstr /C:"FLIBUSTA_FULL_UPDATE" "%DOWNLOADER%" >nul || goto :online_error
findstr /C:"FLIBUSTA_EXTRA_UPDATE" "%DOWNLOADER%" >nul || goto :online_error
findstr /C:"resolveMhlBases" "%DOWNLOADER%" >nul || goto :online_error
findstr /C:"text/html" "%DOWNLOADER%" >nul || goto :online_error
findstr /C:"catalogFullSnapshot" "%PIPELINE%" >nul || goto :online_error
findstr /C:"tracked && catalogFullSnapshot" "%PIPELINE%" >nul || goto :online_error
findstr /C:"throw new UncheckedIOException" "%READER%" >nul || goto :online_error
findstr /C:".isbn(parseIsbn(isbn))" "%MAPPER%" >nul || goto :online_error
findstr /C:"catalogVersion" "%NETWORK%" >nul || goto :online_error
echo OK: server root resolution, version markers, full/extra semantics, archive validation and ISBN safety are present.
goto :transactions

:online_error
echo ERROR: MyHomeLib/Flibusta online-update fix is incomplete or this is an older tree.
exit /b 13

:transactions
echo [6/7] Verifying metadata/collection transaction-manager separation...
findstr /C:"metadataTransactionManager" "%METACFG%" >nul || goto :tx_error
findstr /C:"@Primary" "%METACFG%" >nul || goto :tx_error
findstr /C:"metadataTransactionManager" "%COLREPO%" >nul || goto :tx_error
findstr /C:"collectionTransactionManager" "%AUTHORREPO%" >nul || goto :tx_error
findstr /C:"collectionTransactionManager" "%GROUPBATCH%" >nul || goto :tx_error
echo OK: metadata transactions can start before an active collection exists; collection transactions remain explicitly scoped.
goto :maven

:tx_error
echo ERROR: Metadata/collection transaction-manager separation is incomplete.
exit /b 14

:maven
echo [7/7] Starting Maven verification from:
echo   %CD%
call mvnw.cmd clean verify
exit /b %ERRORLEVEL%
