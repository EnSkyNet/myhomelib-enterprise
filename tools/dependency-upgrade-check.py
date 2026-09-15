#!/usr/bin/env python3
from pathlib import Path
import re, sys, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
errors=[]
def need(cond,msg):
    if not cond: errors.append(msg)

def text(rel): return (ROOT/rel).read_text(encoding='utf-8')
pom=text('pom.xml')
for tag,val in {
    'spring-boot.version':'4.1.1',
    'javafx.version':'21.0.12',
    'sqlite.version':'3.53.4.0',
    'flyway.version':'12.4.0',
    'junit.version':'6.0.3',
    'mockito.version':'5.23.0',
    'assertj.version':'3.27.7',
    'archunit.version':'1.5.0',
    'testcontainers.version':'2.0.5',
}.items():
    need(f'<{tag}>{val}</{tag}>' in pom, f'{tag} must be {val}')
need('<artifactId>archunit-junit5</artifactId>' not in pom, 'JUnit 5 ArchUnit integration must not remain with JUnit 6')
need('<artifactId>archunit</artifactId>' in pom, 'ArchUnit core must be managed explicitly')
need('<artifactId>maven-surefire-plugin</artifactId>' in pom and '<version>3.5.6</version>' in pom,
     'Surefire 3.5.6 alignment missing')
health=text('myhomelib-bootstrap/src/main/java/com/myhomelibcorp/monitoring/LibraryHealthIndicator.java')
need('org.springframework.boot.health.contributor.Health' in health, 'Boot 4 Health import missing')
need('org.springframework.boot.actuate.health' not in health, 'Boot 3 actuator health import remains')
infra_pom=text('myhomelib-infrastructure/pom.xml')
need('<artifactId>spring-boot-starter-jdbc-test</artifactId>' in infra_pom, 'Boot 4 JDBC test starter missing')
need('<artifactId>flyway-core</artifactId>' in infra_pom, 'Explicit Flyway core dependency missing')
need('<artifactId>spring-boot-starter-flyway</artifactId>' not in infra_pom,
     'Generic Flyway auto-config starter must not be enabled for multi-DataSource explicit migration design')
for rel in [
'myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/persistence/sqlite/DatabaseTest.java',
'myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteNavigationFacetRepositoryStage8Test.java',
'myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteBookQueryRepositoryTest.java']:
    s=text(rel)
    need('org.springframework.boot.test.autoconfigure.jdbc' not in s, f'old JDBC test package remains in {rel}')
    need('org.springframework.boot.jdbc.test.autoconfigure' in s, f'Boot 4 JDBC test package missing in {rel}')
yml=text('myhomelib-bootstrap/src/main/resources/application.yml')
need('rollingpolicy:' in yml and 'max-file-size: 10MB' in yml, 'Logback rolling policy namespace missing')
need(re.search(r'(?m)^\s+max-size:', yml) is None, 'legacy logging.file.max-size remains')
ui=text('myhomelib-ui/pom.xml')
need('<artifactId>maven-surefire-plugin</artifactId>' in ui and '<version>3.5.6</version>' in ui,
     'UI Surefire must align to 3.5.6')
# Validate all project POMs independently of Maven artifact resolution.
for p in ROOT.rglob('pom.xml'):
    if '.mvn' in p.parts or 'target' in p.parts: continue
    try: ET.parse(p)
    except Exception as e: errors.append(f'invalid XML {p.relative_to(ROOT)}: {e}')
if errors:
    print('DEPENDENCY UPGRADE STATIC CHECK: FAIL')
    for e in errors: print(' -',e)
    sys.exit(1)
print('DEPENDENCY UPGRADE STATIC CHECK: PASS')
print(' - Spring Boot 4.1.1 migration imports/configuration present')
print(' - JavaFX 21.0.12 and SQLite JDBC 3.53.4.0 requested')
print(' - JUnit/Mockito/AssertJ/Testcontainers/Surefire aligned with Boot 4.1 managed stack')
print(' - ArchUnit moved to JUnit-agnostic core 1.5.0 for JUnit 6 compatibility')
print(' - POM XML is structurally valid')
