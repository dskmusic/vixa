# Arquitectura de Vixa

Un solo módulo `app`. Kotlin, corrutinas/Flow, Jetpack Compose (Material 3), Media3 1.11.1.
Inyección manual: `VixaApp.container` (`AppContainer`). Sin Hilt, sin Room: JSON (kotlinx.serialization) y DataStore.

```
com.dskmusic.vixa
├─ model/    Proyecto inmutable y serializable + operaciones puras + historial
├─ engine/   Shaders, registros, composición, vista previa, exportación, audio
├─ media/    Importación, miniaturas, ondas, proxies, GIF/WebP, análisis, inversa
├─ data/     Ajustes, registro de errores, proyectos (.vixa), recursos online
└─ ui/       Inicio, editor (línea de tiempo, vista previa, paneles), selector, ajustes, tutorial
```

## Modelo (`model/`)

- `Project → Track → Clip` (`Project.kt`). Un `Clip` puede ser VIDEO, IMAGE, COLOR, AUDIO, TEXT, STICKER o
  ADJUST y lleva transformación, color, filtro, efectos, croma, máscara, zona, keyframes, animaciones y
  transición. **Todos los tiempos son `Long` en microsegundos.**
- `Ops.kt`: operaciones puras `Project → Project` (dividir, recortar, mover, reordenar, agrupar, pegar
  atributos, reencuadrar, `slice`, `mapPaths`…). Todas terminan en `normalized()`, que ajusta duraciones a
  la velocidad y coloca la pista principal de forma magnética (las transiciones solapan clips).
- `History.kt`: pila de estados. Como el modelo es inmutable, cada paso comparte estructura con el anterior.
- `ExportSettings.kt`: ajustes de exportación (también serializables, para los preajustes).

## Motor (`engine/`)

### Una única función modelo → Composition

`CompositionBuilder.build()` es la única conversión y la usan `PreviewPlayer` y `Exporter`:

```
sec 0   base de tiempos: PNG transparente de toda la duración a los fps del proyecto (primaria)
sec 1…  pistas de superposición, de arriba abajo (una secuencia por pista)
sec …   pista principal: carril A y carril B (los clips con transición alternan de carril)
sec …   audio: los clips audibles repartidos en carriles solo-audio con huecos de silencio
efectos de composición: fondo + capa de ajuste → efectos de capas de ajuste → overlay Canvas
```

Hechos de Media3 en los que se apoya (comprobados en su código fuente): la secuencia 0 marca los tiempos
de salida y se dibuja encima; la mezcla es alfa recto; los huecos nativos de vídeo son negros opacos (por
eso se usa un PNG transparente); el tiempo que reciben los efectos es tiempo de composición.

### Parámetros en vivo

Los efectos no guardan valores: leen el proyecto actual a través de `RenderCtx.project()`. Mover un slider
solo repinta el fotograma (un `seekTo` a la posición actual). La composición se reconstruye únicamente
cuando cambia `CompositionBuilder.structureKey()` (clips, tiempos, velocidad, tipos de efecto…).
En la exportación el proveedor devuelve un proyecto congelado.

### Shaders (`Shaders.kt`, `GlEffects.kt`)

- `ClipProgram`: **una pasada por clip**. Para cada píxel del lienzo calcula la coordenada del medio con
  una matriz inversa y aplica recorte, corrección de color parametrizada (+LUT, +curvas), croma, máscara,
  zona desenfocada/mosaico, opacidad, fusión, fondo desenfocado y la transición.
- `FxProgram`: pasada de un efecto del registro (cuerpo GLSL + hasta 8 parámetros).
- `VixaOverlay`: texto, stickers, subtítulos y marca de agua dibujados con Canvas (`OverlayRender`). Solo
  redibuja y vuelve a subir la textura cuando cambia lo que hay que mostrar.

### Registros de datos (`Registry.kt`, `Anim.kt`, `Templates.kt`, `Shapes.kt`, `Fonts.kt`)

Añadir un elemento es añadir una entrada:

| Qué | Dónde | Entrada |
|---|---|---|
| Transición | `Registry.transitions` | cuerpo GLSL de `tr(p, uv, role)` |
| Efecto | `Registry.effects` | parámetros + cuerpo GLSL de `fx(uv)` |
| Filtro | `Registry.filters` | preajuste de `ColorAdjust` (+ tintes, + LUT) |
| Animación | `Anims` | lambda sobre `AnimState` |
| Plantilla de texto | `Templates.all` | lista de capas con sus animaciones |
| Forma | `Shapes.all` | trazado en el cuadrado unidad |

### Audio (`AudioEngine.kt`)

`VixaAudioProcessor` (uno por clip audible): volumen con keyframes, fundidos, fundido cruzado en
transiciones, atenuación automática, ecualizador de 3 bandas, puerta de ruido y efectos de voz. El cambio
de tono usa `SonicAudioProcessor`; la velocidad, `EditedMediaItem.setSpeed`.

### Exportación (`Exporter.kt`, `ExportService.kt`)

Transformer escribe en un archivo temporal que solo se renombra al terminar. Si falla, se reintenta con
H.264, luego a 720p y por último con codificador por software. GIF y fotograma se obtienen de un MP4
intermedio. El servicio en primer plano muestra progreso, tiempo restante y permite cancelar.

## Media (`media/`)

`Importer` (sondeo con media3-inspector, normalización de imágenes, extracción de fotogramas),
`Thumbs`/`Waveforms` (caché en memoria y disco, trabajo en segundo plano), `Proxies` (540p para medios
pesados, solo vista previa), `ImageCache` (GIF/WebP animados), `Analysis` (ritmo, silencios, escenas,
estabilización, reproducción inversa).

## Datos (`data/`)

- `SettingsStore`: `AppSettings` como JSON en DataStore.
- `ProjectStore`: `files/projects/<id>/{project.json, meta.json, thumb.jpg, snapshots/}`. Guardado atómico.
  `.vixa` = ZIP con `project.json` y, opcionalmente, `media/`. Migraciones en `ProjectStore.MIGRATIONS`
  (sube `FORMAT_VERSION` y añade el paso).
- `online/`: `ApiConfig` (claves), `Providers` (una implementación de `Provider` por fuente) y
  `OnlineRepository` (búsqueda en paralelo e intercalado, caché de respuestas, pausa de fuentes con cuota
  agotada, favoritos, descargas).
- `ErrorLog`: excepciones y cierres en `files/errors.log`.

## IA en el dispositivo (`ai/`)

- `Models.kt`: registro de modelos como datos (`Models.all`: id, archivos, tamaño, URL, SHA-256, RAM mínima) y
  `ModelStore` (carpeta `files/ai/models/<id>`, descarga reanudable con OkHttp, verificación, descompresión de
  `.tar.bz2`, solo Wi-Fi, URLs editables en Ajustes). Un modelo está listo cuando existe su marca `.ok`.
- `AiJobs.kt`: cola de trabajos (uno a la vez) y `AiService` (servicio en primer plano con progreso, tiempo
  restante y cancelar). El editor sigue usable; los errores van a `ErrorLog` y a `AiJobs.messages`.
- `AiAudio.kt`: decodificación a PCM por bloques con remuestreo (nunca el audio entero en memoria), `WavWriter`
  y `AiCache` (clave = medio + parámetros + modelo).
- `Speech.kt` (sherpa-onnx 1.13.8, con ONNX Runtime enlazado estáticamente; la 2.x quitó espeak-ng y las voces
  Piper dejan de funcionar): VAD, Whisper, mejora de voz y texto a voz. `Separation.kt`: port a Kotlin de la
  separación UVR de sherpa-onnx sobre ONNX Runtime (su AAR de Android no la expone).
- `AiTasks.kt`: tareas con caché; cada resultado tiene ruta determinista y se regenera si falta.
- Integración no destructiva: `Clip.aiAudio` (`AiAudioRef`) apunta al archivo de caché y
  `CompositionBuilder.audioPath` elige entre él y el medio original; `Clip.voiceRangesUs` guarda los tramos
  hablados para bajar la música; `TextSpec.wordsUs` y `Project.karaokeColor` dan el karaoke. Formato `.vixa` v2.
- Visión (`Vision.kt`, MediaPipe Tasks 1.0.0): máscara de personas por muestreo (10/s, 192 px, 8 bits) en un archivo de caché
  que `engine/PersonMask.kt` lee y el shader (`uPersonTex`) aplica según el tiempo, igual en vista previa y exportación; pista de
  cara (FaceLandmarker) convertida en keyframes del sticker; segmentación interactiva para recortar fotos (`Cutout.kt`).
  `FrameStrip` decodifica el vídeo en una pasada (MediaCodec) para alimentar los modelos.
- UI: `ui/AiUi.kt` (descarga de modelos, ajustes, banner), `ui/editor/AiPanel.kt` (panel y páginas),
  `ui/editor/AiActions.kt` (acciones sobre el proyecto) y `ui/AiQuick.kt` (herramientas rápidas).

## UI (`ui/`)

- `MainActivity`: permisos, navegación (`home`, `editor/{id}`, `settings`, `tutorial`), intents de
  compartir/abrir y restauración de la sesión.
- `editor/EditorViewModel`: estado del editor, historial, autoguardado y trabajos en segundo plano.
- `editor/Timeline`: Canvas con gestos; cabezal fijo en el centro; solo dibuja lo visible.
- `editor/Preview`: `SurfaceView` del reproductor más una capa Compose con guías, selección y gestos.
- `editor/EditorScreen`: tres diseños (vertical, horizontal/tablet y pantalla completa).
- `editor/Panels*.kt`: paneles de herramientas. `Props.kt` da acceso por nombre a las propiedades animables.
- `picker/`: selector de medios (dispositivo, online, color), explorador de archivos y cámara.
