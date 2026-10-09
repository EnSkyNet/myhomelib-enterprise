package com.myhomelibcorp.ui.details;

import com.myhomelibcorp.application.dto.BookArtifactDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookDetailsViewModel;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookDetailsPublisherFallbackActionTest {
    @BeforeAll
    static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void fallbackDetailsKeepPublisherLinkEnabledAndNavigateToCurrentPublisher() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(BookDetailsController.class);
        BookDetailsController controller = fixture.instance();
        NavigationService navigation = fixture.dependency(NavigationService.class);
        ApplicationState appState = fixture.dependency(ApplicationState.class);

        Hyperlink publisherLink = UiActionTestSupport.fx(Hyperlink::new);
        UiActionTestSupport.fx(() -> { publisherLink.setDisable(true); return null; });
        UiActionTestSupport.setField(controller, "publisherLink", publisherLink);
        UiActionTestSupport.setField(controller, "titleLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "authorsLinksPane", UiActionTestSupport.fx(FlowPane::new));
        UiActionTestSupport.setField(controller, "seriesLinksPane", UiActionTestSupport.fx(FlowPane::new));
        UiActionTestSupport.setField(controller, "genresLinksPane", UiActionTestSupport.fx(FlowPane::new));
        UiActionTestSupport.setField(controller, "languageLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "sourceLanguageLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "yearLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "isbnLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "translatorsLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "annotationLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "reviewArea", UiActionTestSupport.fx(TextArea::new));
        UiActionTestSupport.setField(controller, "formatLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "fileSizeLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "artifactBadgesPane", UiActionTestSupport.fx(FlowPane::new));
        UiActionTestSupport.setField(controller, "artifactComboBox", UiActionTestSupport.fx(ComboBox<BookArtifactDto>::new));
        UiActionTestSupport.setField(controller, "artifactLocationLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "setPreferredArtifactButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(controller, "openArtifactButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.setField(controller, "localStatusLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "ratingLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "progressLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "libraryRateLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "sourceUrlLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "keywordsLinksPane", UiActionTestSupport.fx(FlowPane::new));
        UiActionTestSupport.setField(controller, "tocPreviewBox", UiActionTestSupport.fx(VBox::new));
        UiActionTestSupport.setField(controller, "imagesCountLabel", UiActionTestSupport.fx(Label::new));
        UiActionTestSupport.setField(controller, "showImagesButton", UiActionTestSupport.fx(Button::new));

        BookDto book = BookDto.builder().id("book-1").title("Book").publisher("Publisher One").build();
        BookDetailsViewModel details = new BookDetailsViewModel();
        details.setCurrentBook(book);
        when(appState.getBookDetails()).thenReturn(details);
        Method updateBasicUI = BookDetailsController.class.getDeclaredMethod("updateBasicUI", BookDto.class);
        updateBasicUI.setAccessible(true);
        UiActionTestSupport.fx(() -> { updateBasicUI.invoke(controller, book); return null; });

        assertThat(UiActionTestSupport.fx(publisherLink::isDisabled)).isFalse();
        UiActionTestSupport.fx(() -> { publisherLink.fire(); return null; });
        verify(navigation).navigateToPublisher("Publisher One");
    }
}
