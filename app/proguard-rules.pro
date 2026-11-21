# Only shrink (remove unused code, e.g. unused icons). No obfuscation / optimisation => safe.
-dontobfuscate
-dontoptimize
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.auralis.player.** { *; }
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.** { *; }
-dontwarn **
