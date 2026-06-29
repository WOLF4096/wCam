cmake_minimum_required(VERSION 3.22.1)

project("wcam")

add_library(wcam SHARED
        native-lib.cpp)

find_library(log-lib
        log)

target_link_libraries(wcam
        ${log-lib})
