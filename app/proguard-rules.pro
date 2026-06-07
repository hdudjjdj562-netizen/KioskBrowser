-keep public class com.enterprise.kioskbrowser.** { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-dontnote android.net.http.SslError
-dontnote android.webkit.**
