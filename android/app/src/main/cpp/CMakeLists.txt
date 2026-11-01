cmake_minimum_required(VERSION 3.22)
project(pbcore CXX)

add_library(pbcore SHARED jni_stub.cpp)
target_compile_features(pbcore PRIVATE cxx_std_17)
target_compile_options(pbcore PRIVATE -Wall)
target_link_libraries(pbcore log)
