package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderSettings;
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

import java.util.List;
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
        scopeHelp.setStyle("-fx-text-fill: #666666; -fx-font-size: 11px;");

        HBox presetRow = new HBox(8, new Label("Preset:"), preset, applyPreset);
        VBox header = new VBox(6, presetRow, perBook, scopeHelp);
        header.setPadding(new Insets(0, 0, 8, 0));

        TabPane tabs = new TabPane(
                tab("Типографіка", typographyPane(c, original)),
                tab("Кольори", colorsPane(c, original)),
                tab("Макет", layoutPane(c, original)),
                tab("Навігація", navigationPane(c, original)),
                tab("Статус", statusPane(c, original))
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

    private static Node typographyPane(Controls c, ReaderSettings original) {
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

    private static Node colorsPane(Controls c, ReaderSettings original) {
        GridPane grid=grid(); int r=0;
        row(grid,r++,"Тема",c.theme);
        row(grid,r++,"Колір фону",c.backgroundColor);
        row(grid,r++,"Колір тексту",c.textColor);
        Button reset=new Button("Скинути кольори");
        reset.setOnAction(e -> c.resetColors(ReaderSettings.defaultSettings()));
        grid.add(reset,0,r,2,1);
        return grid;
    }

    private static Node layoutPane(Controls c, ReaderSettings original) {
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

    private static Node navigationPane(Controls c, ReaderSettings original) {
        GridPane grid=grid(); int r=0;
        grid.add(c.pageMode,0,r++,2,1);
        grid.add(c.autoScroll,0,r++,2,1);
        row(grid,r++,"Швидкість автопрокрутки",c.scrollSpeed);
        row(grid,r++,"Ліва зона",c.tapLeft);
        row(grid,r++,"Центральна зона",c.tapCenter);
        row(grid,r++,"Права зона",c.tapRight);
        Label hint=new Label("Зони натискання займають по 1/3 ширини Reader. Shift+drag використовується для виділення тексту.");
        hint.setWrapText(true); hint.setStyle("-fx-text-fill: #666666;");
        grid.add(hint,0,r++,2,1);
        Button reset=new Button("Скинути навігацію");
        reset.setOnAction(e -> c.resetNavigation(ReaderSettings.defaultSettings()));
        grid.add(reset,0,r,2,1);
        return grid;
    }

    private static Node statusPane(Controls c, ReaderSettings original) {
        VBox box=new VBox(8,c.showStatusBar,c.showStatusProgress,c.showStatusChapter,c.showStatusPage);
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
        final CheckBox pageMode=new CheckBox("Показувати номер сторінки");
        final CheckBox autoScroll=new CheckBox("Автопрокрутка");
        final Spinner<Integer> scrollSpeed=new Spinner<>(1,5,3);
        final CheckBox showToolbar=new CheckBox("Показувати панель інструментів");
        final CheckBox showStatusBar=new CheckBox("Показувати нижній status bar");
        final CheckBox showStatusProgress=new CheckBox("Прогрес");
        final CheckBox showStatusChapter=new CheckBox("Назва розділу");
        final CheckBox showStatusPage=new CheckBox("Номер сторінки");
        final ComboBox<String> tapLeft=actionBox(),tapCenter=actionBox(),tapRight=actionBox();
        final CheckBox perBook=new CheckBox("Лише для цієї книги");

        Controls(ReaderSettings settings,boolean bookOverride){
            fontFamily.setEditable(true); fontFamily.setPrefWidth(230);
            setFrom(settings); perBook.setSelected(bookOverride);
            theme.setOnAction(e->{ ReaderTheme t=ReaderTheme.fromName(theme.getValue()); backgroundColor.setValue(Color.web(t.background())); textColor.setValue(Color.web(t.foreground())); });
        }

        static ComboBox<String> actionBox(){
            return new ComboBox<>(FXCollections.observableArrayList("previous-page","next-page","previous-chapter","next-chapter","toggle-toolbar","search","none"));
        }

        ReaderSettings snapshot(String originalCss){
            String family=fontFamily.getEditor().getText(); if(family==null||family.isBlank()) family="Georgia";
            return new ReaderSettings(theme.getValue(),family,fontSize.getValue(),lineSpacing.getValue(),paragraphSpacing.getValue(),indent.getValue(),alignment.getValue(),
                    left.getValue(),right.getValue(),top.getValue(),bottom.getValue(),hyphenation.isSelected(),pageMode.isSelected(),autoScroll.isSelected(),scrollSpeed.getValue(),showToolbar.isSelected(),
                    mergeReaderColors(originalCss,theme.getValue(),backgroundColor.getValue(),textColor.getValue()),
                    showStatusBar.isSelected(),showStatusProgress.isSelected(),showStatusChapter.isSelected(),showStatusPage.isSelected(),tapLeft.getValue(),tapCenter.getValue(),tapRight.getValue());
        }

        void setFrom(ReaderSettings s){
            theme.setValue(s.themeName()); ReaderTheme rt=ReaderTheme.fromSettings(s); backgroundColor.setValue(Color.web(rt.background())); textColor.setValue(Color.web(rt.foreground()));
            fontFamily.setValue(s.fontFamily()); fontFamily.getEditor().setText(s.fontFamily()); set(fontSize,s.fontSize()); set(lineSpacing,s.lineSpacing()); set(paragraphSpacing,s.paragraphSpacing()); set(indent,s.firstLineIndent()); alignment.setValue(s.alignment());
            set(left,s.leftMargin());set(right,s.rightMargin());set(top,s.topMargin());set(bottom,s.bottomMargin());hyphenation.setSelected(s.hyphenation());pageMode.setSelected(s.pageMode());autoScroll.setSelected(s.autoScroll());scrollSpeed.getValueFactory().setValue(Math.max(1,Math.min(5,s.scrollSpeed())));showToolbar.setSelected(s.showToolbar());
            showStatusBar.setSelected(s.showStatusBar());showStatusProgress.setSelected(s.showStatusProgress());showStatusChapter.setSelected(s.showStatusChapter());showStatusPage.setSelected(s.showStatusPage());tapLeft.setValue(s.tapLeftAction());tapCenter.setValue(s.tapCenterAction());tapRight.setValue(s.tapRightAction());
        }

        static void set(Spinner<Double> s,double v){ s.getValueFactory().setValue(v); }
        void resetTypography(ReaderSettings d){fontFamily.setValue(d.fontFamily());fontFamily.getEditor().setText(d.fontFamily());set(fontSize,d.fontSize());set(lineSpacing,d.lineSpacing());set(paragraphSpacing,d.paragraphSpacing());set(indent,d.firstLineIndent());alignment.setValue(d.alignment());hyphenation.setSelected(d.hyphenation());}
        void resetColors(ReaderSettings d){theme.setValue(d.themeName());ReaderTheme rt=ReaderTheme.fromName(d.themeName());backgroundColor.setValue(Color.web(rt.background()));textColor.setValue(Color.web(rt.foreground()));}
        void resetLayout(ReaderSettings d){set(left,d.leftMargin());set(right,d.rightMargin());set(top,d.topMargin());set(bottom,d.bottomMargin());showToolbar.setSelected(d.showToolbar());}
        void resetNavigation(ReaderSettings d){pageMode.setSelected(d.pageMode());autoScroll.setSelected(d.autoScroll());scrollSpeed.getValueFactory().setValue(d.scrollSpeed());tapLeft.setValue(d.tapLeftAction());tapCenter.setValue(d.tapCenterAction());tapRight.setValue(d.tapRightAction());}
        void resetStatus(ReaderSettings d){showStatusBar.setSelected(d.showStatusBar());showStatusProgress.setSelected(d.showStatusProgress());showStatusChapter.setSelected(d.showStatusChapter());showStatusPage.setSelected(d.showStatusPage());}

        void installPreview(InvalidationListener l){
            theme.valueProperty().addListener(l);backgroundColor.valueProperty().addListener(l);textColor.valueProperty().addListener(l);fontFamily.valueProperty().addListener(l);fontFamily.getEditor().textProperty().addListener(l);
            fontSize.valueProperty().addListener(l);lineSpacing.valueProperty().addListener(l);paragraphSpacing.valueProperty().addListener(l);indent.valueProperty().addListener(l);alignment.valueProperty().addListener(l);left.valueProperty().addListener(l);right.valueProperty().addListener(l);top.valueProperty().addListener(l);bottom.valueProperty().addListener(l);
            hyphenation.selectedProperty().addListener(l);pageMode.selectedProperty().addListener(l);autoScroll.selectedProperty().addListener(l);scrollSpeed.valueProperty().addListener(l);showToolbar.selectedProperty().addListener(l);showStatusBar.selectedProperty().addListener(l);showStatusProgress.selectedProperty().addListener(l);showStatusChapter.selectedProperty().addListener(l);showStatusPage.selectedProperty().addListener(l);tapLeft.valueProperty().addListener(l);tapCenter.valueProperty().addListener(l);tapRight.valueProperty().addListener(l);
        }
    }
}
