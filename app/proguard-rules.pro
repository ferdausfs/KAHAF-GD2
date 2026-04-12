# TFLite
-keep class org.tensorflow.** { *; }
-keep class com.google.flatbuffers.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Accessibility Service
-keep class com.kb.blocker.accessibility.ContentBlockerService { *; }

# Keep model classes
-keepclassmembers class com.kb.blocker.** { *; }
