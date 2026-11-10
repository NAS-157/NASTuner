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
