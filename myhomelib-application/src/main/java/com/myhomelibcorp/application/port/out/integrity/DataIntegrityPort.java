package com.myhomelibcorp.application.port.out.integrity;

import com.myhomelibcorp.application.usecase.integrity.IntegrityReport;

/**
 * Порт для перевірки та виправлення цілісності даних.
 * Реалізується в інфраструктурному шарі.
 */
public interface DataIntegrityPort {

    /**
     * Виконує перевірку цілісності даних.
     * @return звіт про цілісність
     */
    IntegrityReport checkIntegrity();

    /**
     * Legacy destructive repair hook. Implementations must not perform unreviewed deletion;
     * the supported workflow is CollectionMaintenanceUseCase.
     */
    @Deprecated(forRemoval = true)
    void fixOrphanedData();
}