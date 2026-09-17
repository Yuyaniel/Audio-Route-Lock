cmake_minimum_required(VERSION 3.22)
project(arl_audio CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ShadowHook（inline hook）以 prefab 形式随 AAR 提供。
find_package(shadowhook REQUIRED CONFIG)

add_library(arl_audio SHARED arl_audio.cpp)
# aaudio / OpenSLES：NDK 公共库。链接它们顺带保证本库被加载时两个音频库都已被拉起。
target_link_libraries(arl_audio PRIVATE shadowhook::shadowhook aaudio OpenSLES log)
