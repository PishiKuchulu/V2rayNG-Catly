#!/bin/bash
# FALLBACK ONLY. Runs the pure-JVM tests of this feature with the REAL JUnit 5 (Jupiter + JUnit Platform
# console launcher) when Gradle cannot be used. It is NOT `./gradlew testDebugUnitTest`: it does not
# build the Android module, and the tests that need Android classes are not covered.
#
# Requirements: JDK 17+, kotlinc 2.x on PATH or $KOTLINC, a gson jar ($GSON_JAR), and on Debian/Ubuntu:
#   apt-get install junit5 libkotlinx-coroutines-java libkotlinx-atomicfu-java
set -euo pipefail
cd "$(dirname "$0")/../../V2rayNG/app"
J=${JAVA_LIBS:-/usr/share/java}
KOTLINC=${KOTLINC:-kotlinc}
GSON_JAR=${GSON_JAR:?set GSON_JAR to a gson jar}
STDLIB=${KOTLIN_STDLIB:?set KOTLIN_STDLIB to kotlin-stdlib.jar}
LIBS=$GSON_JAR:$J/kotlinx-coroutines-core.jar:$J/atomicfu.jar
API=$J/junit-jupiter-api.jar:$J/opentest4j.jar:$J/apiguardian-api.jar:$J/junit-platform-commons-1.9.1.jar
D=src/main/java/com/v2ray/ang/handler/location
OUT=$(mktemp -d)
SRC=""
for n in LocationProfile GeoCatalog LocationProfileCodec LocationProfileRepository LocationStatus PrivacyAudit CoordinateParser ExitLocation CandidateScanner ServiceState ServiceGateway; do SRC="$SRC $D/$n.kt"; done
$KOTLINC -nowarn -jvm-target 17 -cp "$LIBS:$API" $SRC src/test/java/com/v2ray/ang/handler/location/*.kt -d "$OUT"
java -jar "$J/junit-platform-console-standalone.jar" -cp "$OUT:$LIBS:$STDLIB" --scan-classpath "$OUT" \
  --disable-banner --details=summary --details-theme=ascii
