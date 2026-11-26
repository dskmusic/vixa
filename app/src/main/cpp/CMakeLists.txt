# giflossy (gifsicle con --lossy, licencia GPL): se compila como libgiflossy.so y se usa para comprimir los GIF exportados.
cmake_minimum_required(VERSION 3.18.1)
project(giflossy C)

set(G ${CMAKE_CURRENT_SOURCE_DIR}/giflossy)
add_library(giflossy SHARED
    giflossy_jni.c
    ${G}/src/clp.c ${G}/src/fmalloc.c ${G}/src/giffunc.c ${G}/src/gifread.c ${G}/src/gifunopt.c ${G}/src/gifwrite.c
    ${G}/src/merge.c ${G}/src/optimize.c ${G}/src/quantize.c ${G}/src/support.c ${G}/src/xform.c ${G}/src/gifsicle.c)
# El main() de gifsicle pasa a llamarse gifsicle_main para poder llamarlo desde JNI.
set_source_files_properties(${G}/src/gifsicle.c PROPERTIES COMPILE_DEFINITIONS "main=gifsicle_main")
target_include_directories(giflossy PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${G}/include ${G}/src)
target_compile_definitions(giflossy PRIVATE HAVE_CONFIG_H=1)
target_compile_options(giflossy PRIVATE -O2 -w)
# Alineado a 16 KB (requisito de Google Play para Android 15+).
target_link_options(giflossy PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(giflossy m)
