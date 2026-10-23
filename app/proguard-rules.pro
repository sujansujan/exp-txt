# Keep Apache Commons Compress, XZ, and Zip4j classes for comprehensive archive handling
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-keep class net.lingala.zip4j.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn java.awt.**
-dontwarn javax.crypto.**
