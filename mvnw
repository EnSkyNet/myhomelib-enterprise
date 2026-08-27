# Створення mvnw (без BOM, з Unix LF)
@"
#!/bin/sh
# ----------------------------------------------------------------------------
# Maven Wrapper
# ----------------------------------------------------------------------------

# Use the full path to this script
BASE_DIR=\$(cd "\$(dirname "\$0")" && pwd)
PROPS="\$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "\$PROPS" ]; then
    echo "ERROR: Missing \$PROPS" >&2
    exit 1
fi

# Read distributionUrl from properties file
DIST_URL=\$(grep '^distributionUrl=' "\$PROPS" | cut -d= -f2)
if [ -z "\$DIST_URL" ]; then
    echo "ERROR: distributionUrl is not configured in \$PROPS" >&2
    exit 1
fi

# Extract Maven version from URL
MVN_VERSION=\$(echo "\$DIST_URL" | sed -n 's|.*/apache-maven/\([^/]*\)/apache-maven-.*|\1|p')
if [ -z "\$MVN_VERSION" ]; then
    echo "ERROR: Cannot determine Maven version from distributionUrl: \$DIST_URL" >&2
    exit 1
fi

# Determine Maven home
if [ -n "\$MAVEN_WRAPPER_HOME" ]; then
    WRAPPER_HOME="\$MAVEN_WRAPPER_HOME"
else
    WRAPPER_HOME="\$HOME/.m2/wrapper/dists"
fi

DIST_DIR="\$WRAPPER_HOME/apache-maven-\$MVN_VERSION"
MVN_CMD="\$DIST_DIR/apache-maven-\$MVN_VERSION/bin/mvn"
TMP_ZIP="\$DIST_DIR/apache-maven-\$MVN_VERSION-bin.zip.part"

# Download Maven if not present
if [ ! -f "\$MVN_CMD" ]; then
    mkdir -p "\$DIST_DIR"
    [ -f "\$TMP_ZIP" ] && rm -f "\$TMP_ZIP"
    echo "Downloading Maven \$MVN_VERSION..." >&2
    echo "Source: \$DIST_URL" >&2
    
    if command -v curl >/dev/null 2>&1; then
        curl -L -o "\$TMP_ZIP" "\$DIST_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "\$TMP_ZIP" "\$DIST_URL"
    else
        echo "ERROR: curl or wget is required" >&2
        exit 1
    fi
    
    if [ ! -f "\$TMP_ZIP" ]; then
        echo "ERROR: Failed to download Maven" >&2
        exit 1
    fi
    
    echo "Extracting..." >&2
    unzip -q "\$TMP_ZIP" -d "\$DIST_DIR"
    rm -f "\$TMP_ZIP"
    echo "Maven \$MVN_VERSION installed successfully" >&2
fi

if [ ! -f "\$MVN_CMD" ]; then
    echo "ERROR: Maven command not found: \$MVN_CMD" >&2
    exit 1
fi

exec "\$MVN_CMD" "\$@"
"@ | Out-File -FilePath "mvnw" -Encoding ASCII

# Встановлення прав на виконання (тільки для WSL/Git Bash)
if (Get-Command wsl -ErrorAction SilentlyContinue) {
    wsl chmod +x mvnw
} else {
    Write-Host "⚠️ Для роботи mvnw на Linux/macOS потрібно встановити права: chmod +x mvnw" -ForegroundColor Yellow
}

Write-Host "✅ mvnw створено" -ForegroundColor Green