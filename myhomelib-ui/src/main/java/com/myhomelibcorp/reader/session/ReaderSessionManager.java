package com.myhomelibcorp.reader.session;

import com.myhomelibcorp.application.dto.BookDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@Slf4j
public class ReaderSessionManager {

    private final ConcurrentMap<String, ReaderSession> sessions = new ConcurrentHashMap<>();
    private volatile ReaderSession currentSession;

    public ReaderSession createSession(BookDto book) {
        closeCurrentSession();
        ReaderSession session = ReaderSession.create(book);
        sessions.put(session.getSessionId(), session);
        currentSession = session;
        log.info("Створено сесію Reader для книги: {} (id: {})", book.getTitle(), session.getSessionId());
        return session;
    }

    public ReaderSession getCurrentSession() {
        return currentSession;
    }

    public ReaderSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public boolean isCurrentSession(String sessionId) {
        return currentSession != null && currentSession.getSessionId().equals(sessionId);
    }

    public void closeCurrentSession() {
        if (currentSession != null) {
            currentSession.markClosed();
            sessions.remove(currentSession.getSessionId());
            log.info("Закрито сесію Reader: {}", currentSession.getSessionId());
            currentSession = null;
        }
    }

    public void closeSession(String sessionId) {
        ReaderSession session = sessions.remove(sessionId);
        if (session != null) {
            session.markClosed();
            if (currentSession == session) {
                currentSession = null;
            }
            log.info("Закрито сесію Reader за ID: {}", sessionId);
        }
    }

    public boolean hasActiveSession() {
        return currentSession != null && currentSession.isActive();
    }
}