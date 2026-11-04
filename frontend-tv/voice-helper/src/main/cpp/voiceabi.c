#include <jni.h>

/*
 * PackageManager only selects a 32-bit process when the APK actually contains a matching native
 * library. The vendor AIUI runtime is loaded from com.jmgo.ai.voice and requires that 32-bit ABI.
 */
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    return JNI_VERSION_1_6;
}
