# Keep KioArch JNI class and its native methods
-keep class com.sorrowblue.kioarch.KioArchJni {
    native <methods>;
}

# Keep JniEntryInfo class and its constructor since it is instantiated from JNI C++ code
-keep class com.sorrowblue.kioarch.JniEntryInfo {
    <init>(...);
    *;
}

# Keep SeekableSource and its implementations since they are invoked from JNI C++ code
-keep interface com.sorrowblue.kioarch.SeekableSource {
    int read(byte[], int, int);
    void seek(long);
    long position();
    long length();
}
-keep class * implements com.sorrowblue.kioarch.SeekableSource {
    int read(byte[], int, int);
    void seek(long);
    long position();
    long length();
}

# Keep kotlinx.io.Sink and its implementations since the write method is invoked from JNI C++ code
-keep interface kotlinx.io.Sink {
    void write(byte[], int, int);
}
-keep class * implements kotlinx.io.Sink {
    void write(byte[], int, int);
}
