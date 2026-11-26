package com.dskmusic.vixa.engine

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.model.sourceOffsetAt
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Archivo de "parches" de la IA para quitar una marca de agua: por cada muestra del vídeo, el contenido que debe ir dentro de la zona
 * (ya rellenado por el modelo). Cabecera de 32 bytes (WMP1, nº de fotogramas, ancho, alto) y después RGB sin comprimir, fila a fila de arriba abajo.
 */
object WmPatch {
    const val HEADER = 32L

    fun writeHeader(f: RandomAccessFile, count: Int, w: Int, h: Int) {
        f.seek(0)
        f.write("WMP1".toByteArray(Charsets.US_ASCII))
        f.writeInt(count); f.writeInt(w); f.writeInt(h)
        f.write(ByteArray((HEADER - 16).toInt()))
    }

    class Reader(path: String) : AutoCloseable {
        private val raf = RandomAccessFile(File(path), "r")
        val count: Int
        val w: Int
        val h: Int

        init {
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "WMP1") throw java.io.IOException("bad patch file")
            count = raf.readInt(); w = raf.readInt(); h = raf.readInt()
        }

        /** Lee el fotograma [idx] en [out] (w*h*3 bytes). */
        fun frame(idx: Int, out: ByteArray) {
            raf.seek(HEADER + idx.toLong() * w * h * 3)
            raf.readFully(out, 0, w * h * 3)
        }

        override fun close() { try { raf.close() } catch (e: Throwable) { } }
    }
}

/**
 * Quitar marca de agua sobre el fotograma original (antes de recortes y transformaciones): rellena la zona del clip con el color de
 * sus bordes, con un trozo clonado de al lado o con el parche que generó la IA. Lee el proyecto en vivo, así que mover la zona o
 * cambiar el modo se ve al instante.
 */
class WmRemoveEffect(private val ctx: RenderCtx, private val clipId: String) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = try {
        WmRemoveProgram(ctx, clipId)
    } catch (e: Throwable) {
        ErrorLog.log("WmRemove", e)
        androidx.media3.effect.PassthroughShaderProgram()
    }
}

private class WmRemoveProgram(private val ctx: RenderCtx, private val clipId: String) : BaseGlShaderProgram(false, 1) {

    private val program: GlProgram
    private val dummyTex: Int
    private var patchTex = 0
    private var reader: WmPatch.Reader? = null
    private var readerPath = ""
    private var shownIdx = -1
    private var bytes = ByteArray(0)
    private var buf: ByteBuffer? = null
    private val r = FloatArray(4)
    private val a = FloatArray(4)
    private val b = FloatArray(4)

    init {
        try {
            program = GlProgram(Shaders.VERTEX, FRAG)
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            dummyTex = GlUtil.createTexture(1, 1, false)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    /** Sube a la textura el fotograma [idx] del parche (solo si cambió). Devuelve false si no hay parche utilizable. */
    private fun uploadPatch(path: String, idx: Int): Boolean {
        try {
            if (readerPath != path) {
                reader?.close(); reader = null; shownIdx = -1
                readerPath = path
                if (path.isNotEmpty() && File(path).exists()) reader = WmPatch.Reader(path)
            }
            val rd = reader ?: return false
            if (rd.count <= 0) return false
            val i = idx.coerceIn(0, rd.count - 1)
            if (i != shownIdx) {
                val n = rd.w * rd.h * 3
                if (bytes.size != n) { bytes = ByteArray(n); buf = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder()) }
                rd.frame(i, bytes)
                val bb = buf!!
                bb.clear(); bb.put(bytes); bb.position(0)
                if (patchTex == 0) {
                    val ids = IntArray(1)
                    GLES20.glGenTextures(1, ids, 0)
                    patchTex = ids[0]
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, patchTex)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, patchTex)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, rd.w, rd.h, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, bb)
                shownIdx = i
            }
            return patchTex != 0
        } catch (e: Throwable) {
            ErrorLog.log("WmRemove/patch", e)
            readerPath = path
            reader = null
            return false
        }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val p = ctx.project()
            val c = p.clip(clipId)
            val wr = c?.wmRemove
            val t = presentationTimeUs
            val active = c != null && wr != null && t >= c.startUs && t < c.endUs
            var hasPatch = false
            if (active && wr != null && c != null && wr.mode == 2 && wr.patchCount > 0) {
                val local = (t - c.startUs).coerceAtLeast(0)
                val srcT = c.srcInUs + c.sourceOffsetAt(local)
                val idx = ((srcT - wr.patchStartUs).coerceAtLeast(0) * wr.patchFps / 1_000_000L).toInt()
                hasPatch = uploadPatch(wr.patchPath, idx)
            }
            program.use()
            if (active && wr != null) {
                // El origen del fotograma está abajo a la izquierda; los datos del clip van con el origen arriba a la izquierda.
                r[0] = wr.x0.coerceIn(0f, 1f); r[1] = 1f - wr.y1.coerceIn(0f, 1f); r[2] = wr.x1.coerceIn(0f, 1f); r[3] = 1f - wr.y0.coerceIn(0f, 1f)
                a[0] = wr.feather; a[1] = wr.mode.toFloat(); a[2] = if (hasPatch) 1f else 0f; a[3] = 1f
                b[0] = wr.offX; b[1] = -wr.offY
            } else {
                a[3] = 0f
            }
            program.setFloatsUniformIfPresent("uR", r)
            program.setFloatsUniformIfPresent("uA", a)
            program.setFloatsUniformIfPresent("uB", b)
            program.setSamplerTexIdUniform("uTex", inputTexId, 0)
            program.setSamplerTexIdUniform("uPatch", if (hasPatch) patchTex else dummyTex, 1)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            reader?.close()
            if (patchTex != 0) GLES20.glDeleteTextures(1, intArrayOf(patchTex), 0)
            GlUtil.deleteTexture(dummyTex)
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val FRAG = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform sampler2D uTex;
uniform sampler2D uPatch;
uniform vec4 uR;
uniform vec4 uA;
uniform vec4 uB;
vec3 S(vec2 p) { return texture2D(uTex, clamp(p, 0.0, 1.0)).rgb; }
vec3 band(vec2 a, vec2 d) { return (S(a - d) + S(a - d * 0.5) + S(a) + S(a + d * 0.5) + S(a + d)) * 0.2; }
vec3 edgeFill(vec2 p) {
  float w = uR.z - uR.x;
  float h = uR.w - uR.y;
  float e = 0.004;
  float k = 0.012;
  vec3 L = band(vec2(uR.x - e, p.y), vec2(0.0, k));
  vec3 R = band(vec2(uR.z + e, p.y), vec2(0.0, k));
  vec3 B = band(vec2(p.x, uR.y - e), vec2(k, 0.0));
  vec3 T = band(vec2(p.x, uR.w + e), vec2(k, 0.0));
  float tx = (p.x - uR.x) / max(w, 1e-5);
  float ty = (p.y - uR.y) / max(h, 1e-5);
  vec3 hz = mix(L, R, tx);
  vec3 vt = mix(B, T, ty);
  float dH = min(tx, 1.0 - tx) * w;
  float dV = min(ty, 1.0 - ty) * h;
  return (hz * dV + vt * dH) / max(dH + dV, 1e-5);
}
void main() {
  vec4 col = texture2D(uTex, vUv);
  if (uA.w < 0.5) { gl_FragColor = col; return; }
  vec2 p = vUv;
  float dx = min(p.x - uR.x, uR.z - p.x);
  float dy = min(p.y - uR.y, uR.w - p.y);
  if (dx < 0.0 || dy < 0.0) { gl_FragColor = col; return; }
  float fe = uA.x * min(uR.z - uR.x, uR.w - uR.y) * 0.5 + 1e-5;
  float m = smoothstep(0.0, fe, min(dx, dy));
  vec3 fill;
  if (uA.y > 1.5 && uA.z > 0.5) {
    fill = texture2D(uPatch, vec2((p.x - uR.x) / (uR.z - uR.x), (uR.w - p.y) / (uR.w - uR.y))).rgb;
  } else if (uA.y > 0.5 && uA.y < 1.5) {
    fill = S(p + uB.xy);
  } else {
    fill = edgeFill(p);
  }
  gl_FragColor = vec4(mix(col.rgb, fill, m), col.a);
}
"""
    }
}
