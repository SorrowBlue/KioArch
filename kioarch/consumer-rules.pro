# Keep KioArch JNI class and its native and bridge methods
-keep class com.sorrowblue.kioarch.KioArchJni {
    native <methods>;
    public static int readDirect(com.sorrowblue.kioarch.DirectSeekableSource, java.nio.ByteBuffer, int);
}

# Keep JniEntries class and its constructor since it is instantiated from JNI C++ code
-keep class com.sorrowblue.kioarch.JniEntries {
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

# Keep DirectSeekableSource and its implementations since they are invoked from JNI C++ code
-keep interface com.sorrowblue.kioarch.DirectSeekableSource {
    int read(java.nio.ByteBuffer);
}
-keep class * implements com.sorrowblue.kioarch.DirectSeekableSource {
    int read(java.nio.ByteBuffer);
}

# Keep DirectExtractCallback and its implementations since onData is invoked from JNI C++ code
-keep interface com.sorrowblue.kioarch.DirectExtractCallback {
    void onData(java.nio.ByteBuffer, int);
}
-keep class * implements com.sorrowblue.kioarch.DirectExtractCallback {
    void onData(java.nio.ByteBuffer, int);
}

# Keep kotlinx.io.Sink and its implementations since the write method is invoked from JNI C++ code
-keep interface kotlinx.io.Sink {
    void write(byte[], int, int);
}
-keep class * implements kotlinx.io.Sink {
    void write(byte[], int, int);
}

# Keep ArchiveException and all its subclasses, including their constructors, since they are instantiated from JNI C++ code
-keep class com.sorrowblue.kioarch.ArchiveException {
    <init>(...);
}
-keep class * extends com.sorrowblue.kioarch.ArchiveException {
    <init>(...);
}

