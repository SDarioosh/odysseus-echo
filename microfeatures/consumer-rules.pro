# The native micro frontend (src/main/cpp/MicroFrontend.cpp) constructs ProcessOutput by JNI
# (GetMethodID "<init>" "([FI)V") and binds MicroFrontend's native methods by name, so R8 must not
# rename or strip these classes. Without this, minified release builds throw NoSuchMethodError as
# soon as the microWakeWord engine starts, and AppExceptionHandler restarts the app in a loop.
-keep class com.example.microfeatures.** { *; }
