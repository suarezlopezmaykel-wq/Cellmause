# INFORME_AGENTE.md — Cellmause: estabilización inicial

## Objetivo y alcance
Primera intervención técnica autorizada sobre una rama independiente. Se limita a corregir el recurso XML de color, mejorar el control del ciclo de registro/conexión Bluetooth HID y añadir una compilación verificable mediante GitHub Actions.

- Repositorio: `suarezlopezmaykel-wq/Cellmause`
- Rama base: `main`
- Rama de trabajo: `fix/cellmause-hid-stability`
- No se modifica `main`.
- No se fusiona el Pull Request.
- La prueba Bluetooth física en un teléfono y un ordenador queda pendiente.

## Cambios realizados

### 1. Recurso de color
Archivo: `app/src/main/res/values/colors.xml`

- Se sustituyó el elemento raíz inválido `<values>` por `<resources>`.
- Se conservó el color `ic_launcher_background` (#007CFB).

### 2. Ciclo de vida Bluetooth HID
Archivo: `app/src/main/java/com/example/btmouse/MainActivity.kt`

- Se implementó `onAppStatusChanged` para conocer cuándo Android registra o desregistra la aplicación HID.
- Se comprueba el resultado inmediato de `registerApp()`.
- Se evita iniciar registros simultáneos mientras llega la respuesta asíncrona de Android; el estado compartido de registro es visible entre hilos.
- Se evita solicitar conexiones antes de que el registro HID esté confirmado.
- Se comprueba el resultado inmediato de `connect()`.
- Se comprueba el estado de registro antes de enviar reportes y se devuelve el resultado de `sendReport()`.
- Se intenta registrar el perfil al volver la actividad al primer plano si todavía no está registrado.
- Se actualiza el estado de conexión cuando el perfil se desconecta.
- Se cierra el proxy HID y el executor al destruir la actividad.
- Se añadió tratamiento de `ACTION_CANCEL` al panel táctil.
- Se conserva la interfaz y el comportamiento general previo; no se implementó todavía el arrastre/clic sostenido ni se rediseñaron los gestos.

### 3. Compilación automatizada
Archivo nuevo: `.github/workflows/build-apk.yml`

- Se ejecuta en Pull Requests que modifican el proyecto Android y manualmente.
- Configura Java 17 y Gradle 8.7.
- Ejecuta `gradle assembleDebug`.
- Publica `Cellmause-debug` como artefacto si la compilación termina correctamente.
- Permisos de GitHub Actions limitados a lectura del contenido.

## Validación y límites

- Revisión estática de los archivos antes de la modificación: realizada.
- Compilación de esta rama: pendiente de ejecución de GitHub Actions; el workflow incluye disparador de push en esta rama para que pueda ejecutarse sin modificar `main`.
- Prueba en Samsung Galaxy A03 Core u otro dispositivo Android: pendiente.
- Prueba de conexión HID real con PC: pendiente.
- No se afirma que la aplicación ya esté validada en ejecución.

## Riesgos pendientes
- El fabricante/dispositivo Android debe exponer el perfil Bluetooth HID Device; no todos los teléfonos lo soportan.
- El sistema puede desregistrar el perfil al pasar la aplicación a segundo plano. La nueva lógica intenta recuperarlo al volver al primer plano, pero requiere prueba física.
- La precisión del desplazamiento multitáctil, el arrastre y la adaptación de tamaños siguen pendientes para una fase posterior.

## Resultado
Esta rama contiene una primera corrección de recursos y una mejora del control HID, además de un workflow dedicado para verificar la compilación. No fusionar sin revisar el resultado de Actions y probar la conexión Bluetooth real.


---

## Segunda intervención: rediseño visual de la interfaz

- Rama: `design/cellmause-ui-refresh`
- Base: `fix/cellmause-hid-stability` (versión de referencia 0.1).
- Commit de interfaz: `d74924c327129a50c94603be132494ac2d19bcca`.

### Cambios visuales
- Se adopta una paleta oscura azul marino con contraste claro y acento azul.
- Se incorpora encabezado con nombre y descripción de la aplicación.
- El estado Bluetooth se presenta en una tarjeta diferenciada.
- Los botones principales y de clic reciben fondos redondeados, tipografía y tamaños coherentes.
- El panel táctil recibe fondo oscuro, borde redondeado y guías visuales discretas.
- Las medidas de la nueva interfaz se expresan en dp para adaptarse mejor a distintas densidades de pantalla.
- Se conserva una distribución vertical compacta con panel táctil flexible.

### Límites de alcance
- No se modifica el descriptor HID, el registro, la conexión, el envío de reportes, la selección de dispositivos ni la interpretación de gestos.
- El rediseño es exclusivamente visual y de disposición.
- Pendiente: compilación automatizada de esta propuesta y comprobación visual/funcional en el Samsung Galaxy A03 Core y con un ordenador.


### Resultado de compilación (26-11-2026)
- GitHub Actions workflow run #5: `45913343546`.
- Resultado: `success`; el job `Compile debug APK` y la subida del artefacto terminaron correctamente.
- Artefacto: `Cellmause-debug`, ID `15161133268`.
- Tamaño del artefacto ZIP: 1,032,358 bytes.
- SHA-256 del artefacto: `b5d520a4172e164fa4c2beac30787f1644f6e986076993a65a2c242c629a2cbc`.
- Commit de la rama: `2db803511dad49aab665b19892e61ca9ceea60f6`.
- Run: https://github.com/suarezlopezmaykel-wq/Cellmause/actions/runs/45913343546
- PR #3: https://github.com/suarezlopezmaykel-wq/Cellmause/pull/3
- La compilación está verificada por CI. No equivale a una prueba de instalación, renderizado o funcionamiento Bluetooth HID en un dispositivo físico; esas pruebas siguen pendientes.
