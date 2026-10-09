package com.myhomelibcorp.application.update;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/** Read-only application boundary for pending staged update state. */
@Service
public final class GetPendingApplicationUpdateUseCase {
    private final ApplicationUpdatePort port;

    public GetPendingApplicationUpdateUseCase(ApplicationUpdatePort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    public Optional<UpdatePlan> execute() throws Exception {
        return port.pendingPlan();
    }
}
