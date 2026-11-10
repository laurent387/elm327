# Paho MQTT
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.overmind.obdgateway.** { *** Companion; }
-keepclasseswithmembers class com.overmind.obdgateway.** { kotlinx.serialization.KSerializer serializer(...); }
