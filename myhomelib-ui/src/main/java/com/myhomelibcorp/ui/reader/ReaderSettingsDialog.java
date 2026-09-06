package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderElementStyle;
import com.myhomelibcorp.reader.api.ReaderSemanticElement;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.ReaderStyleSheet;
import com.myhomelibcorp.reader.api.ReaderInputSettings;
import com.myhomelibcorp.reader.api.ReaderSettingsPreset;
import com.myhomelibcorp.reader.api.ReaderSettingsPresets;
import com.myhomelibcorp.reader.api.ReaderTheme;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.beans.InvalidationListener;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Window;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Stage 19: categorized Reader settings with presets, live preview and per-book scope. */
final class ReaderSettingsDialog {

    record Result(ReaderSettings settings, boolean bookOverride) {}

    private static final ReaderSemanticElement[] COLOR_ELEMENTS = {
            ReaderSemanticElement.BOOK_TITLE, ReaderSemanticElement.CHAPTER_TITLE,
            ReaderSemanticElement.SECTION_TITLE, ReaderSemanticElement.SUBTITLE,
            ReaderSemanticElement.EPIGRAPH, ReaderSemanticElement.QUOTE,
            ReaderSemanticElement.POEM, ReaderSemanticElement.POEM_AUTHOR,
            ReaderSemanticElement.TEXT_AUTHOR, ReaderSemanticElement.ANNOTATION,
            ReaderSemanticElement.LINK, ReaderSemanticElement.FOOTNOTE,
            ReaderSemanticElement.STRONG, ReaderSemanticElement.EMPHASIS, ReaderSemanticElement.CODE
    };

    private ReaderSettingsDialog() {}

    static Optional<Result> show(Window owner, ReaderSettings current, boolean currentBookOverride,
                                 Consumer<ReaderSettings> livePreview, LocalizationService i18n) {
        ReaderSettings original = current != null ? current : ReaderSettings.defaultSettings();
        Controls c = new Controls(original, currentBookOverride, i18n);

        Dialog<Result> dialog = new Dialog<>();
        dialog.setTitle(i18n.text("ui.reader.settings.title"));
        dialog.setHeaderText(i18n.text("ui.reader.settings.header"));
        if (owner != null) dialog.initOwner(owner);

        ButtonType apply = new ButtonType(i18n.text("common.apply"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);

        ComboBox<ReaderSettingsPreset> preset = new ComboBox<>(FXCollections.observableArrayList(ReaderSettingsPresets.builtIns()));
        preset.setCellFactory(v -> presetCell());
        preset.setButtonCell(presetCell());
        preset.setPromptText(i18n.text("ui.reader.settings.preset.prompt"));
        Button applyPreset = new Button(i18n.text("ui.reader.settings.preset.apply"));
        applyPreset.setOnAction(e -> {
            ReaderSettingsPreset selected = preset.getValue();
            if (selected != null) {
                c.setFrom(selected.settings());
                preview(livePreview, c.snapshot(original.customCss()));
            }
        });

        CheckBox perBook = c.perBook;
        Label scopeHelp = new Label(i18n.text("ui.reader.settings.scope.help"));
        scopeHelp.getStyleClass().addAll("muted-text", "small-text");

        HBox presetRow = new HBox(8, new Label(i18n.text("ui.reader.settings.preset.label")), preset, applyPreset);
        VBox header = new VBox(6, presetRow, perBook, scopeHelp);
        header.setPadding(new Insets(0, 0, 8, 0));

        TabPane tabs = new TabPane(
                tab(i18n.text("ui.reader.settings.tab.typography"), typographyPane(c)),
                tab(i18n.text("ui.reader.settings.tab.element_styles"), semanticTypographyPane(c)),
                tab(i18n.text("ui.reader.settings.tab.colors"), colorsPane(c)),
                tab(i18n.text("ui.reader.settings.tab.layout"), layoutPane(c)),
                tab(i18n.text("ui.reader.settings.tab.navigation"), navigationPane(c)),
                tab(i18n.text("ui.reader.settings.tab.status"), statusPane(c))
        );
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane content = new BorderPane(tabs);
        content.setTop(header);
        content.setPrefSize(620, 520);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(680, 620);

        InvalidationListener previewListener = obs -> preview(livePreview, c.snapshot(original.customCss()));
        c.installPreview(previewListener);

        AtomicBoolean accepted = new AtomicBoolean(false);
        dialog.setResultConverter(button -> {
            if (button != apply) return null;
            accepted.set(true);
            return new Result(c.snapshot(original.customCss()), perBook.isSelected());
        });
        dialog.setOnHidden(e -> {
            if (!accepted.get()) preview(livePreview, original);
        });

        return dialog.showAndWait();
    }

    private static ListCell<ReaderSettingsPreset> presetCell() {
        return new ListCell<>() {
            @Override protected void updateItem(ReaderSettingsPreset item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        };
    }

    private static Tab tab(String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private static Node typographyPane(Controls c) {
        GridPane grid = grid();
        int r=0;
        row(grid,r++,c.t("ui.reader.settings.font"),c.fontFamily);
        row(grid,r++,c.t("ui.reader.settings.font_size"),c.fontSize);
        row(grid,r++,c.t("ui.reader.settings.line_spacing"),c.lineSpacing);
        row(grid,r++,c.t("ui.reader.settings.paragraph_spacing"),c.paragraphSpacing);
        row(grid,r++,c.t("ui.reader.settings.first_line_indent"),c.indent);
        row(grid,r++,c.t("ui.reader.settings.alignment"),c.alignment);
        grid.add(c.hyphenation,0,r++,2,1);
        Button reset = new Button(c.t("ui.reader.settings.reset_typography"));
        reset.setOnAction(e -> c.resetTypography(ReaderSettings.defaultSettings()));
        grid.add(reset,0,r,2,1);
        return grid;
    }

    private static Node semanticTypographyPane(Controls c) {
        GridPane grid = grid();
        int r = 0;
        row(grid, r++, c.t("ui.reader.settings.element"), c.semanticElement);
        grid.add(new Separator(), 0, r++, 2, 1);
        row(grid, r++, c.t("ui.reader.settings.semantic_font"), c.semanticFontFamily);
        grid.add(c.semanticAbsoluteSize, 0, r, 1, 1);
        grid.add(c.semanticFontSize, 1, r++, 1, 1);
        row(grid, r++, c.t("ui.reader.settings.scale_from_base"), c.semanticFontScale);
        row(grid, r++, c.t("ui.reader.settings.weight_italic"), c.semanticWeight);
        row(grid, r++, c.t("ui.reader.settings.alignment"), c.semanticAlignment);
        grid.add(c.semanticSpacingBeforeEnabled, 0, r, 1, 1);
        grid.add(c.semanticSpacingBefore, 1, r++, 1, 1);
        grid.add(c.semanticSpacingAfterEnabled, 0, r, 1, 1);
        grid.add(c.semanticSpacingAfter, 1, r++, 1, 1);
        Label hint = new Label(c.t("ui.reader.settings.semantic_typography_hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("muted-text");
        grid.add(hint, 0, r++, 2, 1);
        Button reset = new Button(c.t("ui.reader.settings.reset_selected_typography"));
        reset.setOnAction(e -> c.resetSelectedSemanticTypography());
        grid.add(reset, 0, r, 2, 1);
        return grid;
    }

    private static Node colorsPane(Controls c) {
        GridPane grid=grid(); int r=0;
        row(grid,r++,c.t("ui.reader.settings.theme"),c.theme);
        row(grid,r++,c.t("ui.reader.settings.background_color"),c.backgroundColor);
        row(grid,r++,c.t("ui.reader.settings.main_text"),c.textColor);
        grid.add(new Separator(),0,r++,2,1);
        Label semantic = new Label(c.t("ui.reader.settings.semantic_colors"));
        semantic.getStyleClass().add("section-title");
        grid.add(semantic,0,r++,2,1);
        for (ReaderSemanticElement element : COLOR_ELEMENTS) {
            row(grid, r++, semanticLabel(c, element), c.semanticColor(element));
        }
        Label hint = new Label(c.t("ui.reader.settings.colors_hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("muted-text");
        grid.add(hint,0,r++,2,1);
        Button inherit=new Button(c.t("ui.reader.settings.inherit_colors"));
        inherit.setOnAction(e -> c.clearSemanticColors());
        Button reset=new Button(c.t("ui.reader.settings.reset_colors"));
        reset.setOnAction(e -> c.resetColors(ReaderSettings.defaultSettings()));
        grid.add(new HBox(8, inherit, reset),0,r,2,1);
        Label previewTitle = new Label(c.t("ui.reader.settings.preview"));
        previewTitle.getStyleClass().add("section-title");
        VBox content = new VBox(10, previewTitle, c.colorPreview, grid);
        content.setPadding(new Insets(8));
        c.installColorPreviewListeners();
        c.refreshColorPreview();
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private static String semanticLabel(Controls c, ReaderSemanticElement element) {
        return c.t("ui.reader.semantic." + element.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Node layoutPane(Controls c) {
        GridPane grid=grid(); int r=0;
        row(grid,r++,c.t("ui.reader.settings.margin_left"),c.left);
        row(grid,r++,c.t("ui.reader.settings.margin_right"),c.right);
        row(grid,r++,c.t("ui.reader.settings.margin_top"),c.top);
        row(grid,r++,c.t("ui.reader.settings.margin_bottom"),c.bottom);
        grid.add(c.showToolbar,0,r++,2,1);
        Button reset=new Button(c.t("ui.reader.settings.reset_layout"));
        reset.setOnAction(e -> c.resetLayout(ReaderSettings.defaultSettings()));
        grid.add(reset,0,r,2,1);
        return grid;
    }

    private static Node navigationPane(Controls c) {
        VBox box = new VBox(12);
        box.setPadding(new Insets(12));
        box.getChildren().addAll(c.twoPageMode, c.autoTwoPageLandscape, c.autoScroll,
                labeled(c.t("ui.reader.settings.auto_scroll_speed"), c.scrollSpeed), c.pinchZoom);

        GridPane taps = inputGrid(c, c.t("ui.reader.settings.tap.short"), c.tapControls());
        GridPane longTaps = inputGrid(c, c.t("ui.reader.settings.tap.long"), c.longTapControls());
        GridPane gestures = grid();
        int r = 0;
        row(gestures, r++, c.t("ui.reader.settings.swipe_left"), c.swipeLeft);
        row(gestures, r++, c.t("ui.reader.settings.swipe_right"), c.swipeRight);
        row(gestures, r++, c.t("ui.reader.settings.swipe_up"), c.swipeUp);
        row(gestures, r++, c.t("ui.reader.settings.swipe_down"), c.swipeDown);
        box.getChildren().addAll(new Separator(), taps, new Separator(), longTaps, new Separator(), gestures);

        Label hint = new Label(c.t("ui.reader.settings.navigation_hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("muted-text");
        Button reset = new Button(c.t("ui.reader.settings.reset_navigation"));
        reset.setOnAction(e -> c.resetNavigation(ReaderSettings.defaultSettings()));
        box.getChildren().addAll(hint, reset);
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private static Node labeled(String text, Node control) {
        return new HBox(8, new Label(text + ':'), control);
    }

    private static GridPane inputGrid(Controls owner, String title, ComboBox<String>[] controls) {
        GridPane grid = grid();
        grid.add(new Label(title), 0, 0, 4, 1);
        String[] rows = {owner.t("ui.reader.settings.zone.top"), owner.t("ui.reader.settings.zone.middle"), owner.t("ui.reader.settings.zone.bottom")};
        String[] cols = {owner.t("ui.reader.settings.zone.left"), owner.t("ui.reader.settings.zone.center"), owner.t("ui.reader.settings.zone.right")};
        for (int c = 0; c < 3; c++) grid.add(new Label(cols[c]), c + 1, 1);
        for (int r = 0; r < 3; r++) {
            grid.add(new Label(rows[r]), 0, r + 2);
            for (int c = 0; c < 3; c++) grid.add(controls[r * 3 + c], c + 1, r + 2);
        }
        return grid;
    }

    private static Node statusPane(Controls c) {
        VBox box=new VBox(8,c.showStatusBar,c.showStatusProgress,c.showStatusChapter,c.showStatusPage,c.showStatusClock);
        box.setPadding(new Insets(12));
        Button reset=new Button(c.t("ui.reader.settings.reset_status"));
        reset.setOnAction(e -> c.resetStatus(ReaderSettings.defaultSettings()));
        box.getChildren().add(reset);
        return box;
    }

    private static GridPane grid() {
        GridPane grid=new GridPane();
        grid.setHgap(10); grid.setVgap(9); grid.setPadding(new Insets(12));
        return grid;
    }

    private static void row(GridPane grid,int row,String label,Node control) {
        grid.add(new Label(label+':'),0,row); grid.add(control,1,row);
    }

    private static void preview(Consumer<ReaderSettings> callback, ReaderSettings settings) {
        if (callback != null && settings != null) callback.accept(settings);
    }

    private static Spinner<Double> doubleSpinner(double min,double max,double value,double step) {
        Spinner<Double> spinner=new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(min,max,Math.max(min,Math.min(max,value)),step));
        spinner.setEditable(true); spinner.setPrefWidth(130); return spinner;
    }

    private static String toHex(Color color) {
        Color c=color!=null?color:Color.BLACK;
        return String.format("#%02X%02X%02X",Math.round(c.getRed()*255),Math.round(c.getGreen()*255),Math.round(c.getBlue()*255));
    }

    private static String mergeReaderColors(String css,String themeName,Color background,Color foreground) {
        String result=css==null?"":css;
        result=result.replaceAll("(?i)--reader-background\\s*:\\s*#[0-9a-f]{6,8}\\s*;?","")
                .replaceAll("(?i)--reader-foreground\\s*:\\s*#[0-9a-f]{6,8}\\s*;?","").trim();
        ReaderTheme base=ReaderTheme.fromName(themeName);
        String bg=toHex(background),fg=toHex(foreground);
        if(bg.equalsIgnoreCase(base.background())&&fg.equalsIgnoreCase(base.foreground())) return result;
        String variables="--reader-background: "+bg+"; --reader-foreground: "+fg+";";
        return result.isBlank()?variables:result+System.lineSeparator()+variables;
    }

    private static final class Controls {
        final LocalizationService i18n;
        final ComboBox<String> theme=new ComboBox<>(FXCollections.observableArrayList("light","sepia","dark","amoled"));
        final ColorPicker backgroundColor=new ColorPicker();
        final ColorPicker textColor=new ColorPicker();
        final Map<ReaderSemanticElement, ColorPicker> semanticColors=new EnumMap<>(ReaderSemanticElement.class);
        final Set<ReaderSemanticElement> explicitSemanticColors=EnumSet.noneOf(ReaderSemanticElement.class);
        final VBox colorPreview = new VBox(5);
        final Label previewBookTitle = new Label();
        final Label previewChapterTitle = new Label();
        final Label previewText = new Label();
        final Label previewQuote = new Label();
        final Label previewFootnote = new Label();
        ReaderStyleSheet styleSheet=ReaderStyleSheet.defaults();
        final ComboBox<ReaderSemanticElement> semanticElement = new ComboBox<>(FXCollections.observableArrayList(COLOR_ELEMENTS));
        final ComboBox<String> semanticFontFamily = new ComboBox<>(FXCollections.observableArrayList(Font.getFamilies()));
        final CheckBox semanticAbsoluteSize = new CheckBox();
        final Spinner<Double> semanticFontSize = doubleSpinner(6, 96, 18, .5);
        final Spinner<Double> semanticFontScale = doubleSpinner(.5, 3.0, 1.0, .05);
        final ComboBox<String> semanticWeight = new ComboBox<>(FXCollections.observableArrayList(
                "inherit", "normal", "bold", "semibold", "light", "italic", "bold italic"));
        final ComboBox<String> semanticAlignment = new ComboBox<>(FXCollections.observableArrayList(
                "inherit", "left", "justify", "center", "right"));
        final CheckBox semanticSpacingBeforeEnabled = new CheckBox();
        final Spinner<Double> semanticSpacingBefore = doubleSpinner(0, 120, 0, 1);
        final CheckBox semanticSpacingAfterEnabled = new CheckBox();
        final Spinner<Double> semanticSpacingAfter = doubleSpinner(0, 120, 0, 1);
        boolean semanticEditorLoading;
        final ComboBox<String> fontFamily=new ComboBox<>(FXCollections.observableArrayList(Font.getFamilies()));
        final Spinner<Double> fontSize=doubleSpinner(10,52,18,.5);
        final Spinner<Double> lineSpacing=doubleSpinner(1,2.5,1.6,.05);
        final Spinner<Double> paragraphSpacing=doubleSpinner(0,4,1.5,.1);
        final Spinner<Double> indent=doubleSpinner(0,4,1.5,.1);
        final ComboBox<String> alignment=new ComboBox<>(FXCollections.observableArrayList("left","justify","center"));
        final Spinner<Double> left=doubleSpinner(0,120,30,1),right=doubleSpinner(0,120,30,1),top=doubleSpinner(0,120,20,1),bottom=doubleSpinner(0,120,20,1);
        final CheckBox hyphenation=new CheckBox();
        final CheckBox twoPageMode=new CheckBox();
        final CheckBox autoTwoPageLandscape=new CheckBox();
        final CheckBox autoScroll=new CheckBox();
        final Spinner<Integer> scrollSpeed=new Spinner<>(1,5,3);
        final CheckBox pinchZoom=new CheckBox();
        final CheckBox showToolbar=new CheckBox();
        final CheckBox showStatusBar=new CheckBox();
        final CheckBox showStatusProgress=new CheckBox();
        final CheckBox showStatusChapter=new CheckBox();
        final CheckBox showStatusPage=new CheckBox();
        final CheckBox showStatusClock=new CheckBox();
        final ComboBox<String> tapTopLeft=actionBox(),tapTopCenter=actionBox(),tapTopRight=actionBox();
        final ComboBox<String> tapMiddleLeft=actionBox(),tapMiddleCenter=actionBox(),tapMiddleRight=actionBox();
        final ComboBox<String> tapBottomLeft=actionBox(),tapBottomCenter=actionBox(),tapBottomRight=actionBox();
        final ComboBox<String> longTopLeft=actionBox(),longTopCenter=actionBox(),longTopRight=actionBox();
        final ComboBox<String> longMiddleLeft=actionBox(),longMiddleCenter=actionBox(),longMiddleRight=actionBox();
        final ComboBox<String> longBottomLeft=actionBox(),longBottomCenter=actionBox(),longBottomRight=actionBox();
        final ComboBox<String> swipeLeft=actionBox(),swipeRight=actionBox(),swipeUp=actionBox(),swipeDown=actionBox();
        final CheckBox perBook=new CheckBox();
        boolean legacyPageMode;

        Controls(ReaderSettings settings,boolean bookOverride, LocalizationService i18n){
            this.i18n = java.util.Objects.requireNonNull(i18n, "i18n");
            previewBookTitle.setText(t("ui.reader.settings.preview.book_title"));
            previewChapterTitle.setText(t("ui.reader.settings.preview.chapter_title"));
            previewText.setText(t("ui.reader.settings.preview.text"));
            previewQuote.setText(t("ui.reader.settings.preview.quote"));
            previewFootnote.setText(t("ui.reader.settings.preview.footnote"));
            semanticAbsoluteSize.setText(t("ui.reader.settings.absolute_size"));
            semanticSpacingBeforeEnabled.setText(t("ui.reader.settings.spacing_before"));
            semanticSpacingAfterEnabled.setText(t("ui.reader.settings.spacing_after"));
            hyphenation.setText(t("ui.reader.settings.hyphenation"));
            twoPageMode.setText(t("ui.reader.settings.two_pages"));
            autoTwoPageLandscape.setText(t("ui.reader.settings.auto_two_pages_landscape"));
            autoScroll.setText(t("ui.reader.settings.auto_scroll"));
            pinchZoom.setText(t("ui.reader.settings.pinch_zoom"));
            showToolbar.setText(t("ui.reader.settings.show_toolbar"));
            showStatusBar.setText(t("ui.reader.settings.show_status_bar"));
            showStatusProgress.setText(t("ui.reader.settings.show_status_progress"));
            showStatusChapter.setText(t("ui.reader.settings.show_status_chapter"));
            showStatusPage.setText(t("ui.reader.settings.show_status_page"));
            showStatusClock.setText(t("ui.reader.settings.show_status_clock"));
            perBook.setText(t("ui.reader.settings.per_book"));
            colorPreview.setPadding(new Insets(14));
            colorPreview.setMinHeight(175);
            colorPreview.setMaxWidth(Double.MAX_VALUE);
            previewText.setWrapText(true);
            previewQuote.setWrapText(true);
            previewFootnote.setWrapText(true);
            colorPreview.getChildren().addAll(previewBookTitle, previewChapterTitle, previewText, previewQuote, previewFootnote);

            fontFamily.setEditable(true); fontFamily.setPrefWidth(230);
            semanticFontFamily.setEditable(true); semanticFontFamily.setPrefWidth(230);
            semanticElement.setCellFactory(v -> semanticElementCell());
            semanticElement.setButtonCell(semanticElementCell());
            semanticElement.valueProperty().addListener((obs, oldValue, newValue) -> {
                if (semanticEditorLoading) return;
                if (oldValue != null) commitSemanticEditor(oldValue);
                loadSemanticEditor(newValue);
            });
            for (ReaderSemanticElement element : COLOR_ELEMENTS) {
                ColorPicker picker = new ColorPicker();
                picker.setPrefWidth(130);
                picker.setOnAction(e -> {
                    explicitSemanticColors.add(element);
                    ReaderElementStyle current = styleSheet.styles().getOrDefault(
                            element, ReaderStyleSheet.defaults().styles().get(element));
                    styleSheet = styleSheet.with(element, current.withColor(toHex(picker.getValue())));
                });
                semanticColors.put(element, picker);
            }
            setFrom(settings); perBook.setSelected(bookOverride);
            theme.setOnAction(e->{
                ReaderTheme t=ReaderTheme.fromName(theme.getValue());
                backgroundColor.setValue(Color.web(t.background()));
                textColor.setValue(Color.web(t.foreground()));
                refreshInheritedSemanticColors();
            });
            textColor.setOnAction(e -> refreshInheritedSemanticColors());
        }

        String t(String key) { return i18n.text(key); }

        ListCell<ReaderSemanticElement> semanticElementCell() {
            return new ListCell<>() {
                @Override protected void updateItem(ReaderSemanticElement item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : semanticLabel(Controls.this, item));
                }
            };
        }

        static ComboBox<String> actionBox(){
            ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(
                    "previous-page","next-page","previous-chapter","next-chapter","start","end",
                    "toggle-toolbar","toggle-two-page","toggle-auto-scroll","search","zoom-in","zoom-out","theme","none"));
            box.setPrefWidth(150);
            return box;
        }

        @SuppressWarnings("unchecked")
        ComboBox<String>[] tapControls(){ return new ComboBox[]{tapTopLeft,tapTopCenter,tapTopRight,tapMiddleLeft,tapMiddleCenter,tapMiddleRight,tapBottomLeft,tapBottomCenter,tapBottomRight}; }
        @SuppressWarnings("unchecked")
        ComboBox<String>[] longTapControls(){ return new ComboBox[]{longTopLeft,longTopCenter,longTopRight,longMiddleLeft,longMiddleCenter,longMiddleRight,longBottomLeft,longBottomCenter,longBottomRight}; }

        ReaderSettings snapshot(String originalCss){
            String family=fontFamily.getEditor().getText(); if(family==null||family.isBlank()) family="Georgia";
            ReaderInputSettings input = inputSettings();
            return new ReaderSettings(theme.getValue(),family,fontSize.getValue(),lineSpacing.getValue(),paragraphSpacing.getValue(),indent.getValue(),alignment.getValue(),
                    left.getValue(),right.getValue(),top.getValue(),bottom.getValue(),hyphenation.isSelected(),legacyPageMode,autoScroll.isSelected(),scrollSpeed.getValue(),showToolbar.isSelected(),
                    mergeReaderColors(originalCss,theme.getValue(),backgroundColor.getValue(),textColor.getValue()),
                    showStatusBar.isSelected(),showStatusProgress.isSelected(),showStatusChapter.isSelected(),showStatusPage.isSelected(),
                    input.middleLeft(),input.middleCenter(),input.middleRight(),twoPageMode.isSelected(),autoTwoPageLandscape.isSelected(),showStatusClock.isSelected(),input, snapshotStyleSheet());
        }

        ReaderStyleSheet snapshotStyleSheet(){
            if (!semanticEditorLoading && semanticElement.getValue() != null) commitSemanticEditor(semanticElement.getValue());
            EnumMap<ReaderSemanticElement, ReaderElementStyle> styles = new EnumMap<>(ReaderSemanticElement.class);
            styles.putAll(styleSheet.styles());
            for (ReaderSemanticElement element : COLOR_ELEMENTS) {
                ReaderElementStyle current = styles.getOrDefault(element, ReaderStyleSheet.defaults().styles().get(element));
                ColorPicker picker = semanticColors.get(element);
                String color = explicitSemanticColors.contains(element) && picker != null ? toHex(picker.getValue()) : "";
                styles.put(element, current.withColor(color));
            }
            return ReaderStyleSheet.withOverrides(styles);
        }

        ReaderInputSettings inputSettings(){
            return new ReaderInputSettings(
                    tapTopLeft.getValue(),tapTopCenter.getValue(),tapTopRight.getValue(),
                    tapMiddleLeft.getValue(),tapMiddleCenter.getValue(),tapMiddleRight.getValue(),
                    tapBottomLeft.getValue(),tapBottomCenter.getValue(),tapBottomRight.getValue(),
                    longTopLeft.getValue(),longTopCenter.getValue(),longTopRight.getValue(),
                    longMiddleLeft.getValue(),longMiddleCenter.getValue(),longMiddleRight.getValue(),
                    longBottomLeft.getValue(),longBottomCenter.getValue(),longBottomRight.getValue(),
                    swipeLeft.getValue(),swipeRight.getValue(),swipeUp.getValue(),swipeDown.getValue(),pinchZoom.isSelected());
        }

        void setFrom(ReaderSettings s){
            semanticEditorLoading = true;
            legacyPageMode=s.pageMode();
            styleSheet=s.styleSheet()!=null?s.styleSheet():ReaderStyleSheet.defaults();
            explicitSemanticColors.clear();
            theme.setValue(s.themeName()); ReaderTheme rt=ReaderTheme.fromSettings(s); backgroundColor.setValue(Color.web(rt.background())); textColor.setValue(Color.web(rt.foreground()));
            for (ReaderSemanticElement element : COLOR_ELEMENTS) {
                ReaderElementStyle style = styleSheet.styles().get(element);
                if (style != null && style.color() != null && !style.color().isBlank()) explicitSemanticColors.add(element);
            }
            refreshInheritedSemanticColors();
            fontFamily.setValue(s.fontFamily()); fontFamily.getEditor().setText(s.fontFamily()); set(fontSize,s.fontSize()); set(lineSpacing,s.lineSpacing()); set(paragraphSpacing,s.paragraphSpacing()); set(indent,s.firstLineIndent()); alignment.setValue(s.alignment());
            set(left,s.leftMargin());set(right,s.rightMargin());set(top,s.topMargin());set(bottom,s.bottomMargin());hyphenation.setSelected(s.hyphenation());twoPageMode.setSelected(s.twoPageMode());autoTwoPageLandscape.setSelected(s.autoTwoPageLandscape());autoScroll.setSelected(s.autoScroll());scrollSpeed.getValueFactory().setValue(Math.max(1,Math.min(5,s.scrollSpeed())));showToolbar.setSelected(s.showToolbar());
            showStatusBar.setSelected(s.showStatusBar());showStatusProgress.setSelected(s.showStatusProgress());showStatusChapter.setSelected(s.showStatusChapter());showStatusPage.setSelected(s.showStatusPage());showStatusClock.setSelected(s.showStatusClock());
            ReaderInputSettings i=s.input(); pinchZoom.setSelected(i.pinchZoom());
            setActions(tapControls(), new String[]{i.topLeft(),i.topCenter(),i.topRight(),i.middleLeft(),i.middleCenter(),i.middleRight(),i.bottomLeft(),i.bottomCenter(),i.bottomRight()});
            setActions(longTapControls(), new String[]{i.longTopLeft(),i.longTopCenter(),i.longTopRight(),i.longMiddleLeft(),i.longMiddleCenter(),i.longMiddleRight(),i.longBottomLeft(),i.longBottomCenter(),i.longBottomRight()});
            swipeLeft.setValue(i.swipeLeft());swipeRight.setValue(i.swipeRight());swipeUp.setValue(i.swipeUp());swipeDown.setValue(i.swipeDown());
            if (semanticElement.getValue() == null) semanticElement.setValue(COLOR_ELEMENTS[0]);
            loadSemanticEditor(semanticElement.getValue());
            semanticEditorLoading = false;
        }

        void loadSemanticEditor(ReaderSemanticElement element) {
            if (element == null) return;
            boolean previousLoading = semanticEditorLoading;
            semanticEditorLoading = true;
            ReaderElementStyle style = styleSheet.styles().getOrDefault(element, ReaderStyleSheet.defaults().styles().get(element));
            String family = style.fontFamily() == null ? "" : style.fontFamily();
            semanticFontFamily.setValue(family);
            semanticFontFamily.getEditor().setText(family);
            semanticAbsoluteSize.setSelected(style.fontSize() != null);
            set(semanticFontSize, style.fontSize() != null ? style.fontSize() : Math.max(6, fontSize.getValue() * style.fontScale()));
            set(semanticFontScale, style.fontScale());
            semanticWeight.setValue(style.fontWeight() == null || style.fontWeight().isBlank() ? "inherit" : style.fontWeight());
            semanticAlignment.setValue(style.alignment() == null || style.alignment().isBlank() ? "inherit" : style.alignment());
            semanticSpacingBeforeEnabled.setSelected(style.spacingBefore() != null);
            set(semanticSpacingBefore, style.spacingBefore() == null ? 0 : style.spacingBefore());
            semanticSpacingAfterEnabled.setSelected(style.spacingAfter() != null);
            set(semanticSpacingAfter, style.spacingAfter() == null ? 0 : style.spacingAfter());
            semanticEditorLoading = previousLoading;
        }

        void commitSemanticEditor(ReaderSemanticElement element) {
            if (semanticEditorLoading || element == null) return;
            ReaderElementStyle current = styleSheet.styles().getOrDefault(element, ReaderStyleSheet.defaults().styles().get(element));
            String family = semanticFontFamily.getEditor().getText();
            String weight = semanticWeight.getValue();
            String alignmentValue = semanticAlignment.getValue();
            ReaderElementStyle updated = new ReaderElementStyle(
                    family == null ? "" : family,
                    semanticAbsoluteSize.isSelected() ? semanticFontSize.getValue() : null,
                    semanticFontScale.getValue(),
                    "inherit".equalsIgnoreCase(weight) ? "" : weight,
                    current.color(),
                    "inherit".equalsIgnoreCase(alignmentValue) ? "" : alignmentValue,
                    semanticSpacingBeforeEnabled.isSelected() ? semanticSpacingBefore.getValue() : null,
                    semanticSpacingAfterEnabled.isSelected() ? semanticSpacingAfter.getValue() : null);
            styleSheet = styleSheet.with(element, updated);
        }

        void resetSelectedSemanticTypography() {
            ReaderSemanticElement element = semanticElement.getValue();
            if (element == null) return;
            ReaderElementStyle current = styleSheet.styles().getOrDefault(element, ReaderStyleSheet.defaults().styles().get(element));
            ReaderElementStyle defaults = ReaderStyleSheet.defaults().styles().get(element);
            styleSheet = styleSheet.with(element, defaults.withColor(current.color()));
            loadSemanticEditor(element);
        }

        static void setActions(ComboBox<String>[] boxes,String[] values){for(int i=0;i<boxes.length;i++)boxes[i].setValue(values[i]);}
        static void set(Spinner<Double> s,double v){ s.getValueFactory().setValue(v); }
        void resetTypography(ReaderSettings d){fontFamily.setValue(d.fontFamily());fontFamily.getEditor().setText(d.fontFamily());set(fontSize,d.fontSize());set(lineSpacing,d.lineSpacing());set(paragraphSpacing,d.paragraphSpacing());set(indent,d.firstLineIndent());alignment.setValue(d.alignment());hyphenation.setSelected(d.hyphenation());}
        void resetColors(ReaderSettings d){
            theme.setValue(d.themeName());
            ReaderTheme rt=ReaderTheme.fromName(d.themeName());
            backgroundColor.setValue(Color.web(rt.background()));
            textColor.setValue(Color.web(rt.foreground()));
            clearSemanticColors();
        }
        ColorPicker semanticColor(ReaderSemanticElement element){ return semanticColors.get(element); }
        void clearSemanticColors(){
            explicitSemanticColors.clear();
            EnumMap<ReaderSemanticElement, ReaderElementStyle> styles = new EnumMap<>(ReaderSemanticElement.class);
            styles.putAll(styleSheet.styles());
            for (ReaderSemanticElement element : COLOR_ELEMENTS) {
                ReaderElementStyle current = styles.getOrDefault(element, ReaderStyleSheet.defaults().styles().get(element));
                styles.put(element, current.withColor(""));
            }
            styleSheet = ReaderStyleSheet.withOverrides(styles);
            refreshInheritedSemanticColors();
        }
        void refreshInheritedSemanticColors(){
            ReaderTheme rt = ReaderTheme.fromName(theme.getValue());
            String globalForeground = textColor.getValue() != null ? toHex(textColor.getValue()) : rt.foreground();
            for (ReaderSemanticElement element : COLOR_ELEMENTS) {
                ColorPicker picker = semanticColors.get(element);
                if (picker == null) continue;
                ReaderElementStyle style = styleSheet.styles().get(element);
                if (explicitSemanticColors.contains(element) && style != null && !style.color().isBlank()) {
                    picker.setValue(Color.web(style.color()));
                } else {
                    picker.setValue(Color.web(semanticFallback(element, rt, globalForeground)));
                }
            }
        }
        static String semanticFallback(ReaderSemanticElement element, ReaderTheme theme, String foreground){
            return switch (element) {
                case LINK -> theme.linkColor();
                case SUBTITLE, EPIGRAPH, POEM_AUTHOR, TEXT_AUTHOR, FOOTNOTE -> theme.secondaryText();
                default -> foreground;
            };
        }
        void installColorPreviewListeners() {
            InvalidationListener listener = obs -> refreshColorPreview();
            theme.valueProperty().addListener(listener);
            backgroundColor.valueProperty().addListener(listener);
            textColor.valueProperty().addListener(listener);
            fontFamily.valueProperty().addListener(listener);
            fontFamily.getEditor().textProperty().addListener(listener);
            fontSize.valueProperty().addListener(listener);
            semanticColors.values().forEach(picker -> picker.valueProperty().addListener(listener));
        }

        void refreshColorPreview() {
            ReaderTheme currentTheme = ReaderTheme.fromName(theme.getValue());
            Color background = backgroundColor.getValue() != null
                    ? backgroundColor.getValue() : Color.web(currentTheme.background());
            Color foreground = textColor.getValue() != null
                    ? textColor.getValue() : Color.web(currentTheme.foreground());

            colorPreview.setStyle("-fx-background-color: " + toHex(background)
                    + "; -fx-border-color: #80808066; -fx-border-radius: 4; -fx-background-radius: 4;");

            String family = fontFamily.getEditor().getText();
            if (family == null || family.isBlank()) family = "Georgia";
            double baseSize = fontSize.getValue() == null ? 18.0 : fontSize.getValue();

            previewBookTitle.setFont(Font.font(family, Math.min(34, baseSize * 1.45)));
            previewChapterTitle.setFont(Font.font(family, Math.min(30, baseSize * 1.22)));
            previewText.setFont(Font.font(family, baseSize));
            previewQuote.setFont(Font.font(family, baseSize));
            previewFootnote.setFont(Font.font(family, Math.max(9, baseSize * 0.82)));

            previewBookTitle.setTextFill(previewColor(ReaderSemanticElement.BOOK_TITLE, foreground, currentTheme));
            previewChapterTitle.setTextFill(previewColor(ReaderSemanticElement.CHAPTER_TITLE, foreground, currentTheme));
            previewText.setTextFill(foreground);
            previewQuote.setTextFill(previewColor(ReaderSemanticElement.QUOTE, foreground, currentTheme));
            previewFootnote.setTextFill(previewColor(ReaderSemanticElement.FOOTNOTE, foreground, currentTheme));
        }

        private Color previewColor(ReaderSemanticElement element, Color foreground, ReaderTheme currentTheme) {
            ColorPicker picker = semanticColors.get(element);
            if (picker != null && picker.getValue() != null) return picker.getValue();
            return Color.web(semanticFallback(element, currentTheme, toHex(foreground)));
        }

        void resetLayout(ReaderSettings d){set(left,d.leftMargin());set(right,d.rightMargin());set(top,d.topMargin());set(bottom,d.bottomMargin());showToolbar.setSelected(d.showToolbar());}
        void resetNavigation(ReaderSettings d){twoPageMode.setSelected(d.twoPageMode());autoTwoPageLandscape.setSelected(d.autoTwoPageLandscape());autoScroll.setSelected(d.autoScroll());scrollSpeed.getValueFactory().setValue(d.scrollSpeed());setFromInput(d.input());}
        void setFromInput(ReaderInputSettings i){pinchZoom.setSelected(i.pinchZoom());setActions(tapControls(),new String[]{i.topLeft(),i.topCenter(),i.topRight(),i.middleLeft(),i.middleCenter(),i.middleRight(),i.bottomLeft(),i.bottomCenter(),i.bottomRight()});setActions(longTapControls(),new String[]{i.longTopLeft(),i.longTopCenter(),i.longTopRight(),i.longMiddleLeft(),i.longMiddleCenter(),i.longMiddleRight(),i.longBottomLeft(),i.longBottomCenter(),i.longBottomRight()});swipeLeft.setValue(i.swipeLeft());swipeRight.setValue(i.swipeRight());swipeUp.setValue(i.swipeUp());swipeDown.setValue(i.swipeDown());}
        void resetStatus(ReaderSettings d){showStatusBar.setSelected(d.showStatusBar());showStatusProgress.setSelected(d.showStatusProgress());showStatusChapter.setSelected(d.showStatusChapter());showStatusPage.setSelected(d.showStatusPage());showStatusClock.setSelected(d.showStatusClock());}

        void installPreview(InvalidationListener l){
            InvalidationListener semanticListener = obs -> {
                if (!semanticEditorLoading && semanticElement.getValue() != null) commitSemanticEditor(semanticElement.getValue());
                l.invalidated(obs);
            };
            semanticFontFamily.valueProperty().addListener(semanticListener);semanticFontFamily.getEditor().textProperty().addListener(semanticListener);
            semanticAbsoluteSize.selectedProperty().addListener(semanticListener);semanticFontSize.valueProperty().addListener(semanticListener);
            semanticFontScale.valueProperty().addListener(semanticListener);semanticWeight.valueProperty().addListener(semanticListener);
            semanticAlignment.valueProperty().addListener(semanticListener);semanticSpacingBeforeEnabled.selectedProperty().addListener(semanticListener);
            semanticSpacingBefore.valueProperty().addListener(semanticListener);semanticSpacingAfterEnabled.selectedProperty().addListener(semanticListener);
            semanticSpacingAfter.valueProperty().addListener(semanticListener);
            theme.valueProperty().addListener(l);backgroundColor.valueProperty().addListener(l);textColor.valueProperty().addListener(l);
            semanticColors.values().forEach(p -> p.valueProperty().addListener(l));
            fontFamily.valueProperty().addListener(l);fontFamily.getEditor().textProperty().addListener(l);
            fontSize.valueProperty().addListener(l);lineSpacing.valueProperty().addListener(l);paragraphSpacing.valueProperty().addListener(l);indent.valueProperty().addListener(l);alignment.valueProperty().addListener(l);left.valueProperty().addListener(l);right.valueProperty().addListener(l);top.valueProperty().addListener(l);bottom.valueProperty().addListener(l);
            hyphenation.selectedProperty().addListener(l);twoPageMode.selectedProperty().addListener(l);autoTwoPageLandscape.selectedProperty().addListener(l);autoScroll.selectedProperty().addListener(l);scrollSpeed.valueProperty().addListener(l);pinchZoom.selectedProperty().addListener(l);showToolbar.selectedProperty().addListener(l);showStatusBar.selectedProperty().addListener(l);showStatusProgress.selectedProperty().addListener(l);showStatusChapter.selectedProperty().addListener(l);showStatusPage.selectedProperty().addListener(l);showStatusClock.selectedProperty().addListener(l);
            for(ComboBox<String> b:tapControls())b.valueProperty().addListener(l);for(ComboBox<String> b:longTapControls())b.valueProperty().addListener(l);swipeLeft.valueProperty().addListener(l);swipeRight.valueProperty().addListener(l);swipeUp.valueProperty().addListener(l);swipeDown.valueProperty().addListener(l);
        }
    }

}
