/*
 * Puente JNI para ejecutar gifsicle (giflossy) como si fuera la linea de comandos:
 *   GifLossy.nativeRun(arrayOf("-O3", "--lossy=80", "entrada.gif", "-o", "salida.gif"))
 *
 * gifsicle esta pensado para ejecutarse una vez por proceso (estado global, exit() ante errores), asi que cada llamada se hace en un
 * proceso hijo (fork) que termina con _exit: el estado nunca se arrastra entre llamadas y un fallo no puede tumbar la app.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

int gifsicle_main(int argc, char **argv);

JNIEXPORT jint JNICALL
Java_com_dskmusic_vixa_engine_GifLossy_nativeRun(JNIEnv *env, jclass clazz, jobjectArray args) {
    (void) clazz;
    int n = (*env)->GetArrayLength(env, args);
    char **argv = (char **) calloc((size_t) n + 2, sizeof(char *));
    if (!argv) return -4;
    argv[0] = strdup("gifsicle");
    for (int i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        const char *u = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i + 1] = strdup(u ? u : "");
        if (u) (*env)->ReleaseStringUTFChars(env, s, u);
        (*env)->DeleteLocalRef(env, s);
    }
    int argc = n + 1;

    int result;
    pid_t pid = fork();
    if (pid == 0) {
        int rc = gifsicle_main(argc, argv);
        _exit(rc);
    } else if (pid > 0) {
        int status = 0;
        while (waitpid(pid, &status, 0) < 0) { /* reintenta si una senal interrumpe la espera */ }
        result = WIFEXITED(status) ? WEXITSTATUS(status) : -2;
    } else {
        result = -3;
    }
    for (int i = 0; i <= n; i++) free(argv[i]);
    free(argv);
    return result;
}
