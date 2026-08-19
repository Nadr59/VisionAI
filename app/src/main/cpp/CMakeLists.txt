cmake_minimum_required(VERSION 3.22.1)
project("visionai" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# llama.cpp build options
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

# JNI Bridge
add_library(visionai SHARED llama_jni.cpp)

target_include_directories(visionai PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/common
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

target_link_libraries(visionai
    llama
    common
    log
    android
)
