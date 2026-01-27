# Stream Player ProGuard Rules

# Keep ExoPlayer classes
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep RTMP data source
-keep class androidx.media3.datasource.rtmp.** { *; }
