package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Єдина UI-перевірка, що не дозволяє запускати конфліктну операцію з бібліотекою,
 * доки виконується серіалізована фонова операція.
 */
@Component
@RequiredArgsConstructor
public class LibraryOperationAvailabilityGuard {
    private final LibraryOperationCoordinator libraryOperations;
    private final DialogService dialogService;
    private final LocalizationService localizationService;

    public boolean ensureAvailable(String requestedAction) {
        LibraryOperationType active = libraryOperations.activeOperation();
        if (active == null) {
            return true;
        }
        dialogService.showWarning(localizationService.text("ui.operation.background.title"),
                LibraryOperationUiText.blockingMessage(requestedAction, active));
        return false;
    }
}
