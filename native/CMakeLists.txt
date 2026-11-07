cmake_minimum_required(VERSION 3.18)
project(srjni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ANDROID_ABI comes from the NDK toolchain file
set(NCNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/ncnn-20261020-android-vulkan/${ANDROID_ABI}")

message(STATUS "ncnn dir : ${NCNN_DIR}")
message(STATUS "abi      : ${ANDROID_ABI}  platform: ${ANDROID_PLATFORM}")

add_library(srjni SHARED srjni.cpp)

target_include_directories(srjni PRIVATE "${NCNN_DIR}/include/ncnn")

# ncnn is a static lib: it needs the glslang stack, and link order matters,
# so keep the whole group together.
target_link_libraries(srjni
    -Wl,--start-group
    "${NCNN_DIR}/lib/libncnn.a"
    "${NCNN_DIR}/lib/libglslang.a"
    "${NCNN_DIR}/lib/libSPIRV.a"
    "${NCNN_DIR}/lib/libMachineIndependent.a"
    "${NCNN_DIR}/lib/libGenericCodeGen.a"
    "${NCNN_DIR}/lib/libOSDependent.a"
    "${NCNN_DIR}/lib/libglslang-default-resource-limits.a"
    -Wl,--end-group
    android
    jnigraphics
    vulkan
    log
    z
    m
)

target_compile_options(srjni PRIVATE -O2 -fopenmp -fno-rtti -fno-exceptions)
target_link_options(srjni PRIVATE -fopenmp -Wl,--gc-sections)
