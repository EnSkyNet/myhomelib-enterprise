package com.myhomelibcorp.ui.opds;

import com.myhomelibcorp.application.opds.*;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OpdsProgrammaticActionBehaviorContractTest {
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void tokenRefreshReloadsTokensAnd_refreshReloadsRuntimeStatus() throws Exception {
        OpdsServerControl serverControl = mock(OpdsServerControl.class);
        OpdsSettingsService settingsService = mock(OpdsSettingsService.class);
        OpdsCertificateManager certificateManager = mock(OpdsCertificateManager.class);
        OpdsAccessTokenService accessTokens = mock(OpdsAccessTokenService.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        LocalizationService i18n = mock(LocalizationService.class);
        when(i18n.text(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(i18n.format(anyString(), any(Object[].class))).thenAnswer(inv -> inv.getArgument(0));
        when(settingsService.load()).thenReturn(OpdsServerSettings.defaults());
        when(certificateManager.inspect(any())).thenReturn(Optional.empty());
        when(accessTokens.list()).thenReturn(List.of());
        when(serverControl.status()).thenReturn(OpdsServerStatus.stopped());
        OpdsUiService service = new OpdsUiService(serverControl, settingsService, certificateManager, accessTokens, background, i18n, mock(com.myhomelibcorp.ui.service.FileChooserService.class));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Platform.runLater(() -> service.show(null));
        Platform.runLater(() -> {
            try {
                DialogPane pane = latestDialogPane();
                List<Button> buttons = descendants(pane).stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
                Button tokenRefresh = buttons.stream().filter(b -> "ui.opds.tokens.refresh".equals(b.getText())).findFirst().orElseThrow();
                Button refresh = buttons.stream().filter(b -> "ui.opds.refresh_status".equals(b.getText())).findFirst().orElseThrow();
                Button stop = buttons.stream().filter(b -> "ui.opds.stop".equals(b.getText())).findFirst().orElseThrow();
                tokenRefresh.fire();
                refresh.fire();
                stop.fire();
                verify(accessTokens, atLeast(2)).list();
                verify(serverControl).stop();
                verify(serverControl, atLeast(3)).status();
                assertThat(tokenRefresh.isDisabled()).isFalse();
                ((Button) pane.lookupButton(javafx.scene.control.ButtonType.CLOSE)).fire();
            } catch (Throwable t) { error.set(t); }
            finally { done.countDown(); }
        });
        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @Test
    void importPemImportsChosenCertificateAndPrivateKey() throws Exception {
        OpdsServerControl serverControl = mock(OpdsServerControl.class);
        OpdsSettingsService settingsService = mock(OpdsSettingsService.class);
        OpdsCertificateManager certificateManager = mock(OpdsCertificateManager.class);
        OpdsAccessTokenService accessTokens = mock(OpdsAccessTokenService.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        LocalizationService i18n = mock(LocalizationService.class);
        com.myhomelibcorp.ui.service.FileChooserService chooser = mock(com.myhomelibcorp.ui.service.FileChooserService.class);
        when(i18n.text(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(i18n.format(anyString(), any(Object[].class))).thenAnswer(inv -> inv.getArgument(0));
        when(settingsService.load()).thenReturn(OpdsServerSettings.defaults());
        when(certificateManager.inspect(any())).thenReturn(Optional.empty());
        when(accessTokens.list()).thenReturn(List.of());
        when(serverControl.status()).thenReturn(OpdsServerStatus.stopped());
        java.io.File cert = new java.io.File("/tmp/myhomelib-cert.pem");
        java.io.File key = new java.io.File("/tmp/myhomelib-key.pem");
        when(chooser.chooseFile(isNull(), anyString(), anyList())).thenReturn(cert, key);
        java.time.Instant now = java.time.Instant.parse("2026-10-06T00:00:00Z");
        OpdsCertificateInfo info = new OpdsCertificateInfo("BB", "CN=imported", now.minusSeconds(60), now.plusSeconds(3600), false);
        OpdsTlsSettings tls = new OpdsTlsSettings(true, "/tmp/imported.p12", "PKCS12", "secret");
        when(certificateManager.importPem(cert.toPath(), key.toPath()))
                .thenReturn(new OpdsCertificateManager.ManagedCertificate(tls, info));
        when(background.submit(any(java.util.concurrent.Callable.class))).thenAnswer(inv -> {
            java.util.concurrent.Callable<?> task = inv.getArgument(0);
            try { return java.util.concurrent.CompletableFuture.completedFuture(task.call()); }
            catch (Exception e) { return java.util.concurrent.CompletableFuture.failedFuture(e); }
        });
        OpdsUiService service = new OpdsUiService(serverControl, settingsService, certificateManager, accessTokens, background, i18n, chooser);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Platform.runLater(() -> service.show(null));
        Platform.runLater(() -> {
            try {
                DialogPane pane = latestDialogPane();
                Button importPem = descendants(pane).stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(b -> "ui.opds.certificate.import".equals(b.getText())).findFirst().orElseThrow();
                importPem.fire();
                verify(certificateManager).importPem(cert.toPath(), key.toPath());
                ((Button) pane.lookupButton(javafx.scene.control.ButtonType.CLOSE)).fire();
            } catch (Throwable t) { error.set(t); }
            finally { done.countDown(); }
        });
        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @Test
    void regenerateGeneratesNewCertificateAndEnablesTlsState() throws Exception {
        OpdsServerControl serverControl = mock(OpdsServerControl.class);
        OpdsSettingsService settingsService = mock(OpdsSettingsService.class);
        OpdsCertificateManager certificateManager = mock(OpdsCertificateManager.class);
        OpdsAccessTokenService accessTokens = mock(OpdsAccessTokenService.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        LocalizationService i18n = mock(LocalizationService.class);
        when(i18n.text(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(i18n.format(anyString(), any(Object[].class))).thenAnswer(inv -> inv.getArgument(0));
        when(settingsService.load()).thenReturn(OpdsServerSettings.defaults());
        java.time.Instant now = java.time.Instant.parse("2026-09-29T00:00:00Z");
        OpdsCertificateInfo info = new OpdsCertificateInfo("AA", "CN=test", now.minusSeconds(60), now.plusSeconds(3600), true);
        OpdsTlsSettings tls = new OpdsTlsSettings(true, "/tmp/opds.p12", "PKCS12", "secret");
        when(certificateManager.inspect(any())).thenReturn(Optional.of(info));
        when(certificateManager.generateSelfSigned(anyString())).thenReturn(new OpdsCertificateManager.ManagedCertificate(tls, info));
        when(accessTokens.list()).thenReturn(List.of());
        when(serverControl.status()).thenReturn(OpdsServerStatus.stopped());
        when(background.submit(any(java.util.concurrent.Callable.class))).thenAnswer(inv -> {
            java.util.concurrent.Callable<?> task = inv.getArgument(0);
            try { return java.util.concurrent.CompletableFuture.completedFuture(task.call()); }
            catch (Exception e) { return java.util.concurrent.CompletableFuture.failedFuture(e); }
        });
        OpdsUiService service = new OpdsUiService(serverControl, settingsService, certificateManager, accessTokens, background, i18n,
                mock(com.myhomelibcorp.ui.service.FileChooserService.class));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Platform.runLater(() -> service.show(null));
        Platform.runLater(() -> {
            try {
                DialogPane pane = latestDialogPane();
                Button regenerate = descendants(pane).stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(b -> "ui.opds.certificate.regenerate".equals(b.getText())).findFirst().orElseThrow();
                assertThat(regenerate.isDisabled()).isFalse();
                regenerate.fire();
                Platform.runLater(() -> {
                    try {
                        verify(certificateManager).generateSelfSigned(anyString());
                        ((Button) pane.lookupButton(javafx.scene.control.ButtonType.CLOSE)).fire();
                    } catch (Throwable t) { error.set(t); }
                    finally { done.countDown(); }
                });
            } catch (Throwable t) { error.set(t); done.countDown(); }
        });
        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @Test
    void copyCopiesOneTimeTokenToSystemClipboard() throws Exception {
        OpdsUiService service = new OpdsUiService(mock(OpdsServerControl.class), mock(OpdsSettingsService.class),
                mock(OpdsCertificateManager.class), mock(OpdsAccessTokenService.class), mock(UiBackgroundExecutor.class),
                mock(LocalizationService.class), mock(com.myhomelibcorp.ui.service.FileChooserService.class));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        java.lang.reflect.Method showTokenOnce = OpdsUiService.class.getDeclaredMethod("showTokenOnce", Window.class, String.class);
        showTokenOnce.setAccessible(true);

        Platform.runLater(() -> {
            try { showTokenOnce.invoke(service, null, "mhl-secret-token"); }
            catch (Throwable t) { error.set(t.getCause() == null ? t : t.getCause()); }
            finally { done.countDown(); }
        });
        Platform.runLater(() -> {
            try {
                DialogPane pane = latestDialogPane();
                Button copy = descendants(pane).stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(b -> b.getOnAction() != null && !b.isDefaultButton() && !b.isCancelButton())
                        .findFirst().orElseThrow();
                copy.fire();
                assertThat(javafx.scene.input.Clipboard.getSystemClipboard().getString()).isEqualTo("mhl-secret-token");
                ((Button) pane.lookupButton(javafx.scene.control.ButtonType.CLOSE)).fire();
            } catch (Throwable t) { error.set(t); }
        });
        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
    }

    private static DialogPane latestDialogPane() {
        List<Window> windows = Window.getWindows().stream().filter(Window::isShowing).toList();
        for (int i = windows.size() - 1; i >= 0; i--) {
            Node root = windows.get(i).getScene() == null ? null : windows.get(i).getScene().getRoot();
            if (root instanceof DialogPane pane) return pane;
        }
        throw new AssertionError("No visible dialog");
    }
    private static List<Node> descendants(Parent parent) {
        ArrayList<Node> out = new ArrayList<>();
        if (parent instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            Node content = scroll.getContent(); out.add(content); if (content instanceof Parent p) out.addAll(descendants(p));
        }
        for (Node child : parent.getChildrenUnmodifiable()) { out.add(child); if (child instanceof Parent p) out.addAll(descendants(p)); }
        return out;
    }
}
