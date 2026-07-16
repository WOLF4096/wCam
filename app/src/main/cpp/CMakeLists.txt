cmake_minimum_required(VERSION 3.22.1)

project("wcam")

# Force high optimization level even in debug builds
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3 -ffast-math")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math")

find_package(OpenMP REQUIRED)

add_library(wcam SHARED
        native-lib.cpp
        histogram.cpp
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
        ${log-lib}
        OpenMP::OpenMP_CXX)

target_compile_options(wcam PRIVATE -fopenmp)
