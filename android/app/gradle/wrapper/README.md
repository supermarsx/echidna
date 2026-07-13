# Gradle wrapper — missing binary

This directory is missing `gradle-wrapper.jar`, the small bootstrap binary the
`gradlew` / `gradlew.bat` scripts load (`org.gradle.wrapper.GradleWrapperMain`).

It was **intentionally not committed** because it is a binary that must come from
a real Gradle distribution — it was not fabricated here (the build host has no
Gradle/JDK toolchain). The wrapper is otherwise fully configured:

- `gradle-wrapper.properties` pins `gradle-8.5-bin.zip`.
- `../../gradlew` and `../../gradlew.bat` are the standard Gradle 8.5 scripts.

## Generate the jar

From `android/app/`, with any local Gradle 8.5 available on PATH:

```sh
gradle wrapper --gradle-version 8.5 --distribution-type bin
```

That regenerates `gradle/wrapper/gradle-wrapper.jar` (and refreshes the scripts /
properties to match). After it exists, `./gradlew assembleDebug` is self-bootstrapping
and no system Gradle is needed. Commit the generated `gradle-wrapper.jar`.
