package com.myhomelibcorp.application.export;

@FunctionalInterface
public interface ExportCollisionResolver {
    ExportCollisionDecision resolve(ExportCollisionContext context);
}
