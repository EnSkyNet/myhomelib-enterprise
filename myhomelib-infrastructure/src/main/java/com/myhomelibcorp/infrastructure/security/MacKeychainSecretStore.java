package com.myhomelibcorp.infrastructure.security;

import com.myhomelibcorp.shared.security.SecretStore;
import com.myhomelibcorp.shared.security.SecretStoreException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.platform.mac.CoreFoundation;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** macOS login Keychain adapter backed directly by Security.framework (no secret in process argv). */
final class MacKeychainSecretStore implements SecretStore {
    private static final byte[] SERVICE = "com.myhomelibcorp.credential-master-key-v1".getBytes(StandardCharsets.UTF_8);
    private static final int ERR_SEC_SUCCESS = 0;
    private static final int ERR_SEC_ITEM_NOT_FOUND = -25300;

    private final byte[] account;

    MacKeychainSecretStore(String account) {
        this.account = account.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Optional<String> read(String key) {
        FoundItem found = find();
        if (found.status == ERR_SEC_ITEM_NOT_FOUND) return Optional.empty();
        ensureSuccess(found.status, "lookup");
        try {
            if (found.passwordData == null || found.passwordLength <= 0) return Optional.of("");
            return Optional.of(new String(found.passwordData.getByteArray(0, found.passwordLength), StandardCharsets.UTF_8));
        } finally {
            free(found);
        }
    }

    @Override
    public void write(String key, String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        FoundItem found = find();
        if (found.status == ERR_SEC_ITEM_NOT_FOUND) {
            int status = SecurityApi.INSTANCE.SecKeychainAddGenericPassword(
                    null,
                    SERVICE.length, SERVICE,
                    account.length, account,
                    bytes.length, bytes,
                    null);
            ensureSuccess(status, "write");
            return;
        }
        ensureSuccess(found.status, "lookup-before-write");
        try {
            int status = SecurityApi.INSTANCE.SecKeychainItemModifyAttributesAndData(
                    found.itemRef, null, bytes.length, bytes);
            ensureSuccess(status, "update");
        } finally {
            free(found);
        }
    }

    @Override
    public void delete(String key) {
        FoundItem found = find();
        if (found.status == ERR_SEC_ITEM_NOT_FOUND) return;
        ensureSuccess(found.status, "lookup-before-delete");
        try {
            ensureSuccess(SecurityApi.INSTANCE.SecKeychainItemDelete(found.itemRef), "delete");
        } finally {
            free(found);
        }
    }

    @Override
    public String backendId() {
        return "macos-keychain-security-framework";
    }

    private FoundItem find() {
        IntByReference length = new IntByReference();
        PointerByReference data = new PointerByReference();
        PointerByReference item = new PointerByReference();
        int status = SecurityApi.INSTANCE.SecKeychainFindGenericPassword(
                null,
                SERVICE.length, SERVICE,
                account.length, account,
                length, data, item);
        return new FoundItem(status, length.getValue(), data.getValue(), item.getValue());
    }

    private void free(FoundItem found) {
        if (found.passwordData != null) {
            SecurityApi.INSTANCE.SecKeychainItemFreeContent(null, found.passwordData);
        }
        if (found.itemRef != null) {
            CoreFoundation.INSTANCE.CFRelease(new CoreFoundation.CFTypeRef(found.itemRef));
        }
    }

    private static void ensureSuccess(int status, String operation) {
        if (status != ERR_SEC_SUCCESS) {
            throw new SecretStoreException("macOS Keychain " + operation + " failed with OSStatus " + status);
        }
    }

    private record FoundItem(int status, int passwordLength, Pointer passwordData, Pointer itemRef) {}

    private interface SecurityApi extends Library {
        SecurityApi INSTANCE = Native.load("Security", SecurityApi.class);

        int SecKeychainFindGenericPassword(
                Pointer keychainOrArray,
                int serviceNameLength, byte[] serviceName,
                int accountNameLength, byte[] accountName,
                IntByReference passwordLength,
                PointerByReference passwordData,
                PointerByReference itemRef);

        int SecKeychainAddGenericPassword(
                Pointer keychain,
                int serviceNameLength, byte[] serviceName,
                int accountNameLength, byte[] accountName,
                int passwordLength, byte[] passwordData,
                PointerByReference itemRef);

        int SecKeychainItemModifyAttributesAndData(Pointer itemRef, Pointer attrList, int length, byte[] data);
        int SecKeychainItemFreeContent(Pointer attrList, Pointer data);
        int SecKeychainItemDelete(Pointer itemRef);
    }
}
