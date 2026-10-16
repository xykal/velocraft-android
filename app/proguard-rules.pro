# Keep VeloCraft Native JNI Bridge signatures intact
-keep class io.velocraft.android.bridge.NativeEngineBridge {
    native <methods>;
}

# Keep C-exported symbols
-keepclasseswithmembernames class * {
    native <methods>;
}
