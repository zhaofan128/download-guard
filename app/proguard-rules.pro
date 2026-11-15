# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepclassmembers class * {
    @androidx.room.* <fields>;
}
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep class com.dlguard.data.db.** { *; }
# WorkManager
-keep class * extends androidx.work.ListenableWorker { *; }
# 反射读取的通知/下载字段
-keepclassmembers class android.app.DownloadManager$Query { *; }
