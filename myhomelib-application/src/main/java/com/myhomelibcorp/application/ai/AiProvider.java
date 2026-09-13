package com.myhomelibcorp.application.ai;

/** Optional provider-neutral AI extension contract. The host always owns consent and secret access. */
public interface AiProvider {
    String id();
    String displayName();
    AiProviderCapabilities capabilities();
    AiResponse execute(AiRequest request, AiProviderContext context) throws AiProviderException;
}
