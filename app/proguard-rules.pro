# JNI entry points are looked up by name at runtime.
-keepclasseswithmembers class com.deadaccurate.engine.** {
    native <methods>;
}
