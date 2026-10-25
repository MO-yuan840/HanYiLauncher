LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := inputbridge
LOCAL_SRC_FILES := input_bridge.c
LOCAL_LDLIBS := -llog -landroid
LOCAL_CFLAGS := -O2 -Wall -fvisibility=hidden
include $(BUILD_SHARED_LIBRARY)
