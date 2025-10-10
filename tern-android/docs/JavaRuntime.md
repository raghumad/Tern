Java runtime

This project requires Java 21 (LTS) for local development and CI builds.

Recommended distribution
- Eclipse Temurin (Adoptium) 21.x (e.g. 21.0.8) — good community support and security updates.
- Alternatively: Azul Zulu, Oracle JDK builds, Amazon Corretto 21.

Verify installation
```bash
java -version
# Expected example: openjdk version "21.0.8" 2025-07-15
```

Set JAVA_HOME for shell (temporary)
```bash
export JAVA_HOME=$(dirname $(dirname $(readlink -f "$(which java)")))
export PATH="$JAVA_HOME/bin:$PATH"
```

Make JAVA_HOME persistent (example using ~/.profile)
```bash
# append lines to ~/.profile
echo "export JAVA_HOME=$JAVA_HOME" >> ~/.profile
echo "export PATH=\$JAVA_HOME/bin:\$PATH" >> ~/.profile
source ~/.profile
```

Gradle and IDEs
- The Gradle wrapper in this repo uses Gradle 8.13 which is compatible with Java 21 and AGP 8.13.
- Ensure your IDE (Android Studio) is configured to use JDK 21 for Gradle and project SDK.

If you need me to add automated checks (e.g. CI step that enforces Java 21) I can prepare that next.
