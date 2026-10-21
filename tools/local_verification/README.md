# Local verification (fallback when Gradle is unavailable)

`run_tests.sh` compiles the pure-JVM sources of the Custom Location feature together with the project's
**real JUnit 5 test classes** and runs them with the **real JUnit Platform console launcher**. No test
framework is simulated.

It is not a substitute for `./gradlew testDebugUnitTest`: it does not build the Android module and cannot run
tests that need Android classes. See `docs/CUSTOM_LOCATION_TEST_REPORT.md` for what was and was not run.
