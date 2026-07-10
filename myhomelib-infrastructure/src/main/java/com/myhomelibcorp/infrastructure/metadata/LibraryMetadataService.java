package com.myhomelibcorp.infrastructure.metadata;

import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class LibraryMetadataService {

    private final QueryExecutor queryExecutor;

    public int getFormatVersion() {
        try {
            String sql = "SELECT value FROM library_metadata WHERE key = 'format_version'";
            return Integer.parseInt(queryExecutor.queryForObject(sql, String.class));
        } catch (Exception e) {
            return 1;
        }
    }

    public void setFormatVersion(int version) {
        String sql = "INSERT OR REPLACE INTO library_metadata (key, value) VALUES ('format_version', ?)";
        queryExecutor.update(sql, String.valueOf(version));
    }
}