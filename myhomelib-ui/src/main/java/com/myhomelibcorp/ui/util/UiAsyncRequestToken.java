package com.myhomelibcorp.ui.util;

/** Immutable identity of a UI async request scoped to the active collection. */
public record UiAsyncRequestToken(long requestId, String collectionId) { }
