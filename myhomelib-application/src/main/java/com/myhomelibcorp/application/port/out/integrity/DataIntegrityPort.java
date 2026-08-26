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

}