package com.myhomelibcorp.domain.model.collection;

import com.myhomelibcorp.shared.util.EncryptionUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;

@Getter
@RequiredArgsConstructor
public class Collection {
    private final String id;
    private final String name;
    private final Path rootFolder;
    private final String dbFile;
    private final int type;
    private final String user;
    private final String password; // Зберігається зашифрованим в БД
    private final String url;
    private final String notes;

    public Collection(String name, Path rootFolder) {
        this.id = java.util.UUID.randomUUID().toString();
        this.name = name;
        this.rootFolder = rootFolder;
        this.dbFile = null;
        this.type = 0;
        this.user = null;
        this.password = null;
        this.url = null;
        this.notes = null;
    }

    /**
     * Повертає дешифрований пароль.
     * @throws SecurityException якщо дешифрування не вдалося
     */
    public String getDecryptedPassword() {
        if (password == null || password.isEmpty()) {
            return null;
        }
        return EncryptionUtil.decrypt(password);
    }

    /**
     * Створює нову колекцію з зашифрованим паролем.
     */
    public Collection withEncryptedPassword(String plainPassword) {
        String encrypted = plainPassword != null && !plainPassword.isEmpty()
                ? EncryptionUtil.encrypt(plainPassword)
                : null;
        return new Collection(
                this.id,
                this.name,
                this.rootFolder,
                this.dbFile,
                this.type,
                this.user,
                encrypted,
                this.url,
                this.notes
        );
    }

    /**
     * Перевіряє, чи пароль зашифрований.
     */
    public boolean isPasswordEncrypted() {
        return password != null && EncryptionUtil.isEncrypted(password);
    }

    @Override
    public String toString() {
        return name != null && !name.isBlank() ? name : "Без назви";
    }
}