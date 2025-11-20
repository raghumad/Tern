export ANDROID_HOME=$HOME/src/android-sdk
export PATH=$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH
./gradlew testAll
