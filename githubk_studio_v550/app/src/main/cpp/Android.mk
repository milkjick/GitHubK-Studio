LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := githubkpty
LOCAL_SRC_FILES := termux_pty.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -O2
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
