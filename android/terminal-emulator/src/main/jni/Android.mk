LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
# 16 KB page size alignment — required for Android 15+ (API 35) devices.
# Google Play mandates this for all new apps and updates targeting Android 15+
# submitted after November 1, 2025.
# -z max-page-size=16384  → sets the ELF PT_LOAD segment alignment to 16 KB
# -z common-page-size=16384 → aligns BSS/data sections to 16 KB as well
# Both flags are supported by the LLD linker bundled with NDK 23+.
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
