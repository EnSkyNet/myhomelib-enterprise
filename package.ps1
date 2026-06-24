# package.ps1
$ErrorActionPreference = "Stop"

$APP_VERSION = "1.0.0"
$APP_NAME = "MyHomeLibEnterprise"
$MAIN_JAR = "myhomelib-bootstrap-1.0.0-SNAPSHOT-exec.jar"
$INPUT_DIR = "myhomelib-bootstrap\target"
$OUTPUT_DIR = "target\jpackage"

Write-Host "Building application..." -ForegroundColor Green
mvn clean package

if ($LASTEXITCODE -ne 0) {
    Write-Host "Maven build failed!" -ForegroundColor Red
    exit 1
}

Write-Host "Creating MSI installer with jpackage..." -ForegroundColor Green
jpackage `
    --type msi `
    --name $APP_NAME `
    --app-version $APP_VERSION `
    --input $INPUT_DIR `
    --main-jar $MAIN_JAR `
    --main-class com.myhomelibcorp.MyHomeLibApp `
    --java-options "--add-modules javafx.controls,javafx.fxml" `
    --dest $OUTPUT_DIR `
    --win-dir-chooser `
    --win-shortcut `
    --win-menu `
    --win-menu-group "MyHomeLib Enterprise"

if ($LASTEXITCODE -eq 0) {
    Write-Host "MSI installer created successfully in $OUTPUT_DIR" -ForegroundColor Green
} else {
    Write-Host "jpackage failed!" -ForegroundColor Red
}