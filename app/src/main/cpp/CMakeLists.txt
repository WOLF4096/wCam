cmake_minimum_required(VERSION 3.22.1)

project("wcam")

add_library(wcam SHARED
        native-lib.cpp
        demosaic/demosaic_common.cpp
        demosaic/bilinear.cpp
        demosaic/ahd.cpp
        demosaic/vng.cpp
        demosaic/rcd.cpp
        demosaic/amaze.cpp
        demosaic/lmmse.cpp
        demosaic/dcb.cpp)

find_library(log-lib
        log)

target_link_libraries(wcam
        ${log-lib})
