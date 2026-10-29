# Room entities and DAOs are kept by Room's own consumer rules.
# Add rules here for networking/serialization libraries in later phases.

# The app uses kotlinx-serialization only through JsonElement (no @Serializable classes); Room (KSP) and
# OkHttp ship their own consumer rules.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
# Action history stores javaClass.simpleName ("Call", "SetTimer", ...): keep those names readable in release.
-keepnames class com.raju.aiassistant.domain.assistant.AssistantAction$*
