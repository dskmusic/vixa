/* Configuracion a mano de giflossy (gifsicle con --lossy) para Android: lo que normalmente genera ./configure. */
#ifndef GIFSICLE_CONFIG_H
#define GIFSICLE_CONFIG_H

#define PACKAGE "gifsicle"
#define PACKAGE_NAME "LCDF Gifsicle"
#define PACKAGE_TARNAME "gifsicle"
#define PACKAGE_VERSION "1.92"
#define PACKAGE_STRING "LCDF Gifsicle 1.92"
#define PACKAGE_BUGREPORT "ekohler@gmail.com"
#define PACKAGE_URL ""
#define VERSION "1.92"

#define HAVE_INTTYPES_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_MEMORY_H 1
#define HAVE_UNISTD_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_SELECT_H 1
#define HAVE_INT64_T 1
#define HAVE_INT64_TYPES 1
#define HAVE_UINTPTR_T 1
#define HAVE_POW 1
#define HAVE_STRTOUL 1
#define HAVE_STRERROR 1
#define HAVE_MKSTEMP 1

#define GIF_ALLOCATOR_DEFINED 1
#define RANDOM random
#define SRANDOM srandom
#define PATHNAME_SEPARATOR '/'
#define SIZEOF_UNSIGNED_INT __SIZEOF_INT__
#define SIZEOF_UNSIGNED_LONG __SIZEOF_LONG__
#define SIZEOF_VOID_P __SIZEOF_POINTER__

/* Sin hilos ni SIMD: mas sencillo y suficiente para GIF cortos. */

/*
 * gifsicle llama a exit() cuando algo falla. Se ejecuta en un proceso hijo (ver giflossy_jni.c), donde lo correcto es salir
 * sin ejecutar los atexit del proceso de la app: exit() pasa a ser _exit().
 */
#include <stdlib.h>
#include <unistd.h>
#define exit(code) _exit(code)

#endif
