cmake_minimum_required(VERSION 3.22.1)
project("llama_jni")

set(CMAKE_CXX_STANDARD 17)

# llama.cpp source files
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

add_library(llama SHARED
    ${LLAMA_DIR}/src/llama.cpp
    ${LLAMA_DIR}/src/llama-vocab.cpp
    ${LLAMA_DIR}/src/llama-grammar.cpp
    ${LLAMA_DIR}/src/llama-sampling.cpp
    ${LLAMA_DIR}/src/unicode.cpp
    ${LLAMA_DIR}/src/grammar-parser.cpp
    ${LLAMA_DIR}/ggml/src/ggml.c
    ${LLAMA_DIR}/ggml/src/ggml-alloc.c
    ${LLAMA_DIR}/ggml/src/ggml-backend.cpp
    ${LLAMA_DIR}/ggml/src/ggml-quants.c
    ${LLAMA_DIR}/ggml/src/ggml-threading.cpp
    ${LLAMA_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${LLAMA_DIR}/ggml/src/ggml-cpu/ggml-cpu-quants.c
    ${LLAMA_DIR}/common/common.cpp
    ${LLAMA_DIR}/common/log.cpp
    ${LLAMA_DIR}/common/sampling.cpp
    ${LLAMA_DIR}/common/build-info.cpp
    ${LLAMA_DIR}/common/ngram-cache.cpp
    ${LLAMA_DIR}/common/arg.cpp
)

target_include_directories(llama PUBLIC
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/common
    ${LLAMA_DIR}/ggml/include
    ${LLAMA_DIR}/ggml/src
    ${LLAMA_DIR}/src
)

target_compile_definitions(llama PUBLIC
    GGML_USE_CPU
    LLAMA_BUILD
)

# JNI library
add_library(llama_jni SHARED llama_jni.cpp)
target_link_libraries(llama_jni llama android log)
