package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.ReaderInputSettings;
import com.myhomelibcorp.reader.api.ReaderSettingsPreset;
import com.myhomelibcorp.reader.api.ReaderSettingsPresets;
import com.myhomelibcorp.reader.api.ReaderTheme;
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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Stage 19: categorized Reader settings with presets, live preview and per-book scope. */
final class ReaderSettingsDialog {

    record Result(ReaderSettings settings, boolean bookOverride) {}

    private ReaderSettingsDialog() {}

    static Optional<Result> show(Window owner, ReaderSettings current, boolean currentBookOverride,
                                 Consumer<ReaderSettings> livePreview) {
        ReaderSettings original = current != null ? current : ReaderSettings.defaultSettings();
        Controls c = new Controls(original, currentBookOverride);

        Dialog<Result> dialog = new Dialog<>();
        dialog.setTitle("Налаштування читання");
        dialog.setHeaderText("Reader — профілі, вигляд, навігація та статус");
        if (owner != null) dialog.initOwner(owner);

        ButtonType apply = new ButtonType("Застосувати", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);

        ComboBox<ReaderSettingsPreset> preset = new ComboBox<>(FXCollections.observableArrayList(ReaderSettingsPresets.builtIns()));
        preset.setCellFactory(v -> presetCell());
        preset.setButtonCell(presetCell());
        preset.setPromptText("Оберіть preset…");
        Button applyPreset = new Button("Застосувати preset");
        applyPreset.setOnAction(e -> {
            ReaderSettingsPreset selected = preset.getValue();
            if (selected != null) {
                c.setFrom(selected.settings());
                preview(livePreview, c.snapshot(original.customCss()));
            }
        });

        CheckBox perBook = c.perBook;
        Label scopeHelp = new Label("Вимкніть, щоб зробити ці налаштування глобальними за замовчуванням.");
        scopeHelp.getStyleClass().addAll("muted-text", "small-text");

        HBox presetRow = new HBox(8, new Label("Preset:"), preset, applyPreset);
        VBox header = new VBox(6, presetRow, perBook, scopeHelp);
        header.setPadding(new Insets(0, 0, 8, 0));

        TabPane tabs = new TabPane(
                tab("Типографіка", typographyPane(c)),
                tab("Кольори", colorsPane(c)),
                tab("Макет", layoutPane(c)),
                tab("Навігація", navigationPane(c)),
                tab("Статус", statusPane(c))
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
        row(grid,r++,"Шрифт",c.fontFamily);
        row(grid,r++,"Розмір",c.fontSize);
        row(grid,r++,"Міжрядковий інтервал",c.lineSpacing);
        row(grid,r++,"Відстань між абзацами",c.paragraphSpacing);
        row(grid,r++,"Відступ першого рядка (em)",c.indent);
        row(grid,r++,"Вирівнювання",c.alignment);
        grid.add(c.hyphenation,0,r++,2,1);
        Button reset = new Button("Скинути типографіку");
        reset.setOnAction(e -> c.resetTypography(ReaderSettings.defaultSettings()));
        grid.add(reset,0,r,2,1);
        return grid;
    }

    private static Node colorsPane(Controls c) {
        GridPane grid=grid(); int r=0;
        row(grid,r++,"Тема",c.theme);
        row(grid,r++,"Колір фону",c.backgroundColor);
        row(grid,r++,"Колір тексту",c.textColor);
        Button reset=new Button("Скинути кольори");
        reset.setOnAction(e -> c.resetColors(ReaderSettings.defaultSettings()));
        grid.add(reset,0,r,2,1);
        return grid;
    }

    private static Node layoutPane(Controls c) {
        GridPane grid=grid(); int r=0;
        row(grid,r++,"Поле ліворуч",c.left);
        row(grid,r++,"Поле праворуч",c.right);
        row(grid,r++,"Поле зверху",c.top);
        row(grid,r++,"Поле знизу",c.bottom);
        grid.add(c.showToolbar,0,r++,2,1);
        Button reset=new Button("Скинути макет");
        reset.setOnAction(e -> c.resetLayout(ReaderSettings.defaultSettings()));
        grid.add(reset,0,r,2,1);
        return grid;
    }

    private static Node navigationPane(Controls c) {
        VBox box = new VBox(12);
        box.setPadding(new Insets(12));
        box.getChildren().addAll(c.twoPageMode, c.autoTwoPageLandscape, c.autoScroll,
                labeled("Швидкість автопрокрутки", c.scrollSpeed), c.pinchZoom);

        GridPane taps = inputGrid("Коротке натискання", c.tapControls());
        GridPane longTaps = inputGrid("Довге натискання", c.longTapControls());
        GridPane gestures = grid();
        int r = 0;
        row(gestures, r++, "Swipe ліворуч", c.swipeLeft);
        row(gestures, r++, "Swipe праворуч", c.swipeRight);
        row(gestures, r++, "Swipe вгору", c.swipeUp);
        row(gestures, r++, "Swipe вниз", c.swipeDown);
        box.getChildren().addAll(new Separator(), taps, new Separator(), longTaps, new Separator(), gestures);

        Label hint = new Label("9 зон = 3×3 площі Reader. Довге натискання ≈0,52 с. Shift+drag залишено для виділення тексту.");
        hint.setWrapText(true);
        hint.getStyleClass().add("muted-text");
        Button reset = new Button("Скинути навігацію");
        reset.setOnAction(e -> c.resetNavigation(ReaderSettings.defaultSettings()));
        box.getChildren().addAll(hint, reset);
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private static Node labeled(String text, Node control) {
        return new HBox(8, new Label(text + ':'), control);
    }

    private static GridPane inputGrid(String title, ComboBox<String>[] controls) {
        GridPane grid = grid();
        grid.add(new Label(title), 0, 0, 4, 1);
        String[] rows = {"Верх", "Середина", "Низ"};
        String[] cols = {"Ліворуч", "Центр", "Праворуч"};
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
        Button reset=new Button("Скинути статус");
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
        final ComboBox<String> theme=new ComboBox<>(FXCollections.observableArrayList("light","sepia","dark","amoled"));
        final ColorPicker backgroundColor=new ColorPicker();
        final ColorPicker textColor=new ColorPicker();
        final ComboBox<String> fontFamily=new ComboBox<>(FXCollections.observableArrayList(Font.getFamilies()));
        final Spinner<Double> fontSize=doubleSpinner(10,52,18,.5);
        final Spinner<Double> lineSpacing=doubleSpinner(1,2.5,1.6,.05);
        final Spinner<Double> paragraphSpacing=doubleSpinner(0,4,1.5,.1);
        final Spinner<Double> indent=doubleSpinner(0,4,1.5,.1);
        final ComboBox<String> alignment=new ComboBox<>(FXCollections.observableArrayList("left","justify","center"));
        final Spinner<Double> left=doubleSpinner(0,120,30,1),right=doubleSpinner(0,120,30,1),top=doubleSpinner(0,120,20,1),bottom=doubleSpinner(0,120,20,1);
        final CheckBox hyphenation=new CheckBox("Переноси слів");
        final CheckBox twoPageMode=new CheckBox("Дві сторінки");
        final CheckBox autoTwoPageLandscape=new CheckBox("Автоматично дві сторінки у широкому вікні");
        final CheckBox autoScroll=new CheckBox("Автопрокрутка");
        final Spinner<Integer> scrollSpeed=new Spinner<>(1,5,3);
        final CheckBox pinchZoom=new CheckBox("Масштаб шрифту жестом pinch");
        final CheckBox showToolbar=new CheckBox("Показувати панель інструментів");
        final CheckBox showStatusBar=new CheckBox("Показувати нижній status bar");
        final CheckBox showStatusProgress=new CheckBox("Прогрес");
        final CheckBox showStatusChapter=new CheckBox("Назва розділу");
        final CheckBox showStatusPage=new CheckBox("Номер сторінки");
        final CheckBox showStatusClock=new CheckBox("Годинник");
        final ComboBox<String> tapTopLeft=actionBox(),tapTopCenter=actionBox(),tapTopRight=actionBox();
        final ComboBox<String> tapMiddleLeft=actionBox(),tapMiddleCenter=actionBox(),tapMiddleRight=actionBox();
        final ComboBox<String> tapBottomLeft=actionBox(),tapBottomCenter=actionBox(),tapBottomRight=actionBox();
        final ComboBox<String> longTopLeft=actionBox(),longTopCenter=actionBox(),longTopRight=actionBox();
        final ComboBox<String> longMiddleLeft=actionBox(),longMiddleCenter=actionBox(),longMiddleRight=actionBox();
        final ComboBox<String> longBottomLeft=actionBox(),longBottomCenter=actionBox(),longBottomRight=actionBox();
        final ComboBox<String> swipeLeft=actionBox(),swipeRight=actionBox(),swipeUp=actionBox(),swipeDown=actionBox();
        final CheckBox perBook=new CheckBox("Лише для цієї книги");
        boolean legacyPageMode;

        Controls(ReaderSettings settings,boolean bookOverride){
            fontFamily.setEditable(true); fontFamily.setPrefWidth(230);
            setFrom(settings); perBook.setSelected(bookOverride);
            theme.setOnAction(e->{ ReaderTheme t=ReaderTheme.fromName(theme.getValue()); backgroundColor.setValue(Color.web(t.background())); textColor.setValue(Color.web(t.foreground())); });
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
                    input.middleLeft(),input.middleCenter(),input.middleRight(),twoPageMode.isSelected(),autoTwoPageLandscape.isSelected(),showStatusClock.isSelected(),input);
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
            legacyPageMode=s.pageMode();
            theme.setValue(s.themeName()); ReaderTheme rt=ReaderTheme.fromSettings(s); backgroundColor.setValue(Color.web(rt.background())); textColor.setValue(Color.web(rt.foreground()));
            fontFamily.setValue(s.fontFamily()); fontFamily.getEditor().setText(s.fontFamily()); set(fontSize,s.fontSize()); set(lineSpacing,s.lineSpacing()); set(paragraphSpacing,s.paragraphSpacing()); set(indent,s.firstLineIndent()); alignment.setValue(s.alignment());
            set(left,s.leftMargin());set(right,s.rightMargin());set(top,s.topMargin());set(bottom,s.bottomMargin());hyphenation.setSelected(s.hyphenation());twoPageMode.setSelected(s.twoPageMode());autoTwoPageLandscape.setSelected(s.autoTwoPageLandscape());autoScroll.setSelected(s.autoScroll());scrollSpeed.getValueFactory().setValue(Math.max(1,Math.min(5,s.scrollSpeed())));showToolbar.setSelected(s.showToolbar());
            showStatusBar.setSelected(s.showStatusBar());showStatusProgress.setSelected(s.showStatusProgress());showStatusChapter.setSelected(s.showStatusChapter());showStatusPage.setSelected(s.showStatusPage());showStatusClock.setSelected(s.showStatusClock());
            ReaderInputSettings i=s.input(); pinchZoom.setSelected(i.pinchZoom());
            setActions(tapControls(), new String[]{i.topLeft(),i.topCenter(),i.topRight(),i.middleLeft(),i.middleCenter(),i.middleRight(),i.bottomLeft(),i.bottomCenter(),i.bottomRight()});
            setActions(longTapControls(), new String[]{i.longTopLeft(),i.longTopCenter(),i.longTopRight(),i.longMiddleLeft(),i.longMiddleCenter(),i.longMiddleRight(),i.longBottomLeft(),i.longBottomCenter(),i.longBottomRight()});
            swipeLeft.setValue(i.swipeLeft());swipeRight.setValue(i.swipeRight());swipeUp.setValue(i.swipeUp());swipeDown.setValue(i.swipeDown());
        }

        static void setActions(ComboBox<String>[] boxes,String[] values){for(int i=0;i<boxes.length;i++)boxes[i].setValue(values[i]);}
        static void set(Spinner<Double> s,double v){ s.getValueFactory().setValue(v); }
        void resetTypography(ReaderSettings d){fontFamily.setValue(d.fontFamily());fontFamily.getEditor().setText(d.fontFamily());set(fontSize,d.fontSize());set(lineSpacing,d.lineSpacing());set(paragraphSpacing,d.paragraphSpacing());set(indent,d.firstLineIndent());alignment.setValue(d.alignment());hyphenation.setSelected(d.hyphenation());}
        void resetColors(ReaderSettings d){theme.setValue(d.themeName());ReaderTheme rt=ReaderTheme.fromName(d.themeName());backgroundColor.setValue(Color.web(rt.background()));textColor.setValue(Color.web(rt.foreground()));}
        void resetLayout(ReaderSettings d){set(left,d.leftMargin());set(right,d.rightMargin());set(top,d.topMargin());set(bottom,d.bottomMargin());showToolbar.setSelected(d.showToolbar());}
        void resetNavigation(ReaderSettings d){twoPageMode.setSelected(d.twoPageMode());autoTwoPageLandscape.setSelected(d.autoTwoPageLandscape());autoScroll.setSelected(d.autoScroll());scrollSpeed.getValueFactory().setValue(d.scrollSpeed());setFromInput(d.input());}
        void setFromInput(ReaderInputSettings i){pinchZoom.setSelected(i.pinchZoom());setActions(tapControls(),new String[]{i.topLeft(),i.topCenter(),i.topRight(),i.middleLeft(),i.middleCenter(),i.middleRight(),i.bottomLeft(),i.bottomCenter(),i.bottomRight()});setActions(longTapControls(),new String[]{i.longTopLeft(),i.longTopCenter(),i.longTopRight(),i.longMiddleLeft(),i.longMiddleCenter(),i.longMiddleRight(),i.longBottomLeft(),i.longBottomCenter(),i.longBottomRight()});swipeLeft.setValue(i.swipeLeft());swipeRight.setValue(i.swipeRight());swipeUp.setValue(i.swipeUp());swipeDown.setValue(i.swipeDown());}
        void resetStatus(ReaderSettings d){showStatusBar.setSelected(d.showStatusBar());showStatusProgress.setSelected(d.showStatusProgress());showStatusChapter.setSelected(d.showStatusChapter());showStatusPage.setSelected(d.showStatusPage());showStatusClock.setSelected(d.showStatusClock());}

        void installPreview(InvalidationListener l){
            theme.valueProperty().addListener(l);backgroundColor.valueProperty().addListener(l);textColor.valueProperty().addListener(l);fontFamily.valueProperty().addListener(l);fontFamily.getEditor().textProperty().addListener(l);
            fontSize.valueProperty().addListener(l);lineSpacing.valueProperty().addListener(l);paragraphSpacing.valueProperty().addListener(l);indent.valueProperty().addListener(l);alignment.valueProperty().addListener(l);left.valueProperty().addListener(l);right.valueProperty().addListener(l);top.valueProperty().addListener(l);bottom.valueProperty().addListener(l);
            hyphenation.selectedProperty().addListener(l);twoPageMode.selectedProperty().addListener(l);autoTwoPageLandscape.selectedProperty().addListener(l);autoScroll.selectedProperty().addListener(l);scrollSpeed.valueProperty().addListener(l);pinchZoom.selectedProperty().addListener(l);showToolbar.selectedProperty().addListener(l);showStatusBar.selectedProperty().addListener(l);showStatusProgress.selectedProperty().addListener(l);showStatusChapter.selectedProperty().addListener(l);showStatusPage.selectedProperty().addListener(l);showStatusClock.selectedProperty().addListener(l);
            for(ComboBox<String> b:tapControls())b.valueProperty().addListener(l);for(ComboBox<String> b:longTapControls())b.valueProperty().addListener(l);swipeLeft.valueProperty().addListener(l);swipeRight.valueProperty().addListener(l);swipeUp.valueProperty().addListener(l);swipeDown.valueProperty().addListener(l);
        }
    }

}
