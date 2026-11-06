# NASTuner

Afinador Android propio, sin anuncios, sin servicios externos y sin permiso de Internet.

## Cambios de precisión

- Detector YIN implementado en Kotlin, independiente de Android. Sustituye el producto de espectros armónicos (HPS); se eliminó JTransforms, que ya no se utiliza.
- Captura mono de 44.1 kHz, filtro antialias y reducción a 11.025 kHz. Ventanas de unos 186 ms, actualizadas cada 46 ms, para observar suficientes ciclos de las cuerdas graves sin esperar una ventana completa entre lecturas. La confirmación de nota agrega hasta 250 ms según el ajuste.
- Interpolación sobre varios períodos para reducir el error de estimación en los agudos. Se rechazan ventanas sin periodicidad suficiente; la confianza expresa periodicidad, no cercanía a una nota afinada.
- Captura `UNPROCESSED` si Android declara que la admite; `VOICE_RECOGNITION` como alternativa. Grabación ligada al ciclo de vida de la pantalla.
- Cada afinación define nota **y octava** por cuerda. Mi2 y Mi4 tienen referencias distintas; también se distinguen el ukelele estándar y Low G. Se ampliaron los rangos para Drop C, bajos y ukelele barítono.
- El modo automático detecta libremente y compara con la cuerda más cercana de la afinación elegida, no con cualquier nota cromática. Seleccionar una cuerda cambia su referencia visual, sin dividir o multiplicar artificialmente la frecuencia medida para hacerla coincidir.
- Indicación de afinación en cents: ±5 inicial, ±3 estándar y ±1 profesional, independientemente de la altura de la nota. El selector Hz/cents cambia la escala visual, no el criterio de afinación.
- Se eliminaron la desviación forzada a cero durante el decaimiento, la zona muerta de cuatro cents y las capas de suavizado que retrasaban la indicación. Sin señal válida no se muestra éxito ni se dispara el sonido de afinación.

El detector monofónico espera una cuerda a la vez. Un armónico aislado y una fundamental realmente ausente sin otros armónicos suficientes pueden resultar ambiguos; ningún algoritmo puede reconstruir información que el micrófono no recibe.

## Compilar y probar

### Sin Android Studio en Windows

Ejecutar `compilar-apk.bat` con doble clic. Usa Python 3, descarga Java 17 y las herramientas oficiales de Android dentro de `.tuner-tools`, ejecuta las pruebas y genera el APK. En este equipo Python ya está instalado. La primera preparación necesita Internet y puede pedir aceptar las licencias de los paquetes del SDK.

El archivo resultante es `NASTuner-prueba.apk` en la carpeta principal; también se conserva en `app/build/outputs/apk/debug/app-debug.apk`. Se puede copiar al teléfono e instalar dando permiso a la aplicación desde la que se abre. La aplicación requiere Android 15 o superior. El APK de depuración queda firmado automáticamente y sirve para pruebas personales.

Los archivos temporales de Gradle se guardan en `%TEMP%/NASTuner-build` para evitar problemas de bloqueo dentro de OneDrive. Las herramientas locales no se incorporan al APK.

### Con Android Studio

Abrir esta carpeta como proyecto en Android Studio, con JDK 17, Android SDK 36 y las herramientas de compilación correspondientes. El proyecto mantiene su requisito original de Android 15 o superior (`minSdk 35`).

En Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

El APK de depuración se genera en `app/build/outputs/apk/debug/app-debug.apk`.

Las pruebas en `YinPitchDetectorTest.kt` ejercitan las clases de producción: cuerdas abiertas, Drop C, graves de bajo, desviaciones pequeñas de Mi2, armónicos dominantes, fundamental ausente con armónicos segundo y tercero, decaimiento, ruido, offset DC, filtrado de entrada a 44.1 kHz, barrido cromático y octavas/referencia de las cuerdas.

En esta revisión se compilaron y ejecutaron esas pruebas con Kotlin 2.0.21/JUnit 4.13.2. También se completaron `:app:testDebugUnitTest` y `:app:assembleDebug` con Gradle, se generó el APK completo y se verificó su firma. **Falta validar la interfaz y el micrófono en un dispositivo Android.** Las pruebas sintéticas no constituyen una certificación de precisión con una guitarra real.

## Comprobar con la guitarra

1. Mantener La4 en 440 Hz salvo que se necesite otra referencia conocida. La autocalibración requiere un La de referencia externa; una cuerda desafinada no sirve para calibrar.
2. Elegir instrumento y afinación. Para comenzar, usar precisión estándar y escala en cents.
3. Calibrar el ambiente sin tocar. Si una cuerda suave deja de detectarse demasiado pronto, aumentar la sensibilidad o repetir la calibración en un lugar más silencioso.
4. Apagar las otras cuerdas con la mano y pulsar suavemente la que se afina. Esperar a que pase el ataque inicial; acercarse a cero ajustando desde abajo.
5. Verificar primero Mi grave y Mi agudo en selección manual: cerca de 82.41 Hz y 329.63 Hz respectivamente. Repetir en automático y comprobar el cambio entre cuerdas.
6. Comparar con una referencia independiente en el mismo teléfono; comprobar ±5 y ±10 cents, silencio, cambios rápidos de cuerda y regreso desde segundo plano. Repetir con el sonido de confirmación desactivado si el micrófono capta el altavoz.

## Referencias

- [YIN: artículo original de de Cheveigné y Kawahara (2002)](https://pubmed.ncbi.nlm.nih.gov/12002874/).
- [Captura de audio y selección de fuente sin procesamiento en Android](https://developer.android.com/media/platform/mediarecorder).

La mejora principal está en el método de detección, las octavas y la indicación de afinación; no requiere agregar una biblioteca comercial ni un servicio de pago.
