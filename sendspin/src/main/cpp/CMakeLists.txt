# Sendspin player: sendspin-cpp (the protocol reference implementation, Apache-2.0) plus a small
# JNI bridge. sendspin-cpp fetches its own dependencies (ArduinoJson, micro-flac, micro-opus,
# IXWebSocket, noise-c) during configure, so the first native build needs git and network access.
cmake_minimum_required(VERSION 3.22.1)
project(vaca_sendspin LANGUAGES C CXX ASM)

include(FetchContent)

# v0.9.5. Odysseus fork: v0.9 encrypts every connection (Noise KKpsk2, X25519 + ChaChaPoly, from
# noise-c's pure-C reference backend) and adds pairing; v0.8.0 spoke the plaintext protocol that
# Music Assistant reports as "legacy mode". See ../../../README.md "Encryption and pairing".
FetchContent_Declare(
    sendspin_cpp
    GIT_REPOSITORY https://github.com/Sendspin/sendspin-cpp.git
    GIT_TAG        b95cf854a784ed308b1f8fe9697f414b02768ddd
)

# Player plays the audio; metadata (track info, progress), controller (transport, shuffle, repeat)
# and colour (the track's palette) feed the on-screen now-playing overlay. Odysseus fork: the
# artwork role is on so the server pushes the album art over the Sendspin connection itself; the
# artwork URL in the metadata points at the server's LAN address, which a device reaching it over
# Tailscale can't load. The visualizer and source (microphone capture) roles stay out of the binary.
set(SENDSPIN_ENABLE_PLAYER ON CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_CONTROLLER ON CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_METADATA ON CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_COLOR ON CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_ARTWORK ON CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_VISUALIZER OFF CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_SOURCE OFF CACHE BOOL "" FORCE)
set(SENDSPIN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(sendspin_cpp)

add_library(sendspin_jni SHARED sendspin_jni.cpp)
target_compile_features(sendspin_jni PRIVATE cxx_std_20)
target_compile_options(sendspin_jni PRIVATE -Wall -Wextra)
find_library(log-lib log)
target_link_libraries(sendspin_jni PRIVATE sendspin ${log-lib})
