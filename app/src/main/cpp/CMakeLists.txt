cmake_minimum_required(VERSION 3.22.1)
project("bitperfect_usb" LANGUAGES CXX)

add_library(bitperfect_usb SHARED
    native_jni.cpp
    usb_audio_driver.cpp
)

find_library(log-lib log)

target_link_libraries(bitperfect_usb
    ${log-lib}
)
