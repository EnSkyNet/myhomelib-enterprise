package com.myhomelibcorp.reader.session;

import com.myhomelibcorp.application.dto.BookDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ReaderSessionManager {

    private volatile ReaderSession currentSession;

    public ReaderSession createSession(BookDto book) {
        closeCurrentSession();
        ReaderSession session = ReaderSession.create(book);
        currentSession = session;
        log.info("Створено сесію Reader для книги: {} (id: {})", book.getTitle(), session.getSessionId());
        return session;
    }

    public ReaderSession getCurrentSession() {
        return currentSession;
    }

    public boolean isCurrentSession(String sessionId) {
        return currentSession != null && currentSession.getSessionId().equals(sessionId);
    }

    public void closeCurrentSession() {
        if (currentSession != null) {
            currentSession.markClosed();
            log.info("Закрито сесію Reader: {}", currentSession.getSessionId());
            currentSession = null;
        }
    }

    public boolean hasActiveSession() {
        return currentSession != null && currentSession.isActive();
    }
}