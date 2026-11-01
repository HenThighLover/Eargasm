cmake_minimum_required(VERSION 3.22)
project(rnnoise_jni C CXX)

include(FetchContent)
FetchContent_Declare(rnnoise
    GIT_REPOSITORY https://github.com/xiph/rnnoise.git
    GIT_TAG 70f1d256acd4b34a572f999a05c87bf00b67730d)
FetchContent_MakeAvailable(rnnoise)
set(RN ${rnnoise_SOURCE_DIR})

# The neural-net weights are not in git; RNNoise publishes them separately.
set(MODEL_HASH 0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37)
if(NOT EXISTS ${RN}/src/rnnoise_data.c)
    file(DOWNLOAD
        https://media.xiph.org/rnnoise/models/rnnoise_data-${MODEL_HASH}.tar.gz
        ${CMAKE_BINARY_DIR}/rnnoise_model.tar.gz
        EXPECTED_HASH SHA256=${MODEL_HASH}
        STATUS dl_status)
    list(GET dl_status 0 dl_code)
    if(NOT dl_code EQUAL 0)
        message(FATAL_ERROR "RNNoise model download failed: ${dl_status}")
    endif()
    file(ARCHIVE_EXTRACT INPUT ${CMAKE_BINARY_DIR}/rnnoise_model.tar.gz DESTINATION ${RN})
endif()
if(NOT EXISTS ${RN}/src/rnnoise_data.c)
    message(FATAL_ERROR "rnnoise_data.c missing after model extract")
endif()

add_library(rnnoise_jni SHARED
    rnnoise_jni.cpp
    ${RN}/src/denoise.c
    ${RN}/src/rnn.c
    ${RN}/src/pitch.c
    ${RN}/src/kiss_fft.c
    ${RN}/src/celt_lpc.c
    ${RN}/src/nnet.c
    ${RN}/src/nnet_default.c
    ${RN}/src/parse_lpcnet_weights.c
    ${RN}/src/rnnoise_data.c
    ${RN}/src/rnnoise_tables.c)

target_include_directories(rnnoise_jni PRIVATE ${RN}/include ${RN}/src)
target_compile_options(rnnoise_jni PRIVATE -O3 -DRNNOISE_BUILD)
target_link_libraries(rnnoise_jni log)