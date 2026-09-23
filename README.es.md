# SideSubs

[English](README.md) · **Español**

## ¿Por qué SideSubs?

Creé SideSubs por una razón muy sencilla: normalmente veo películas y series en versión original con subtítulos en inglés. La mayor parte del tiempo los sigo sin problema, pero de vez en cuando aparece una frase o expresión que no entiendo del todo.

Quería poder mantener los subtítulos en inglés en la televisión y, al mismo tiempo, tener los subtítulos en español —mi idioma nativo— disponibles en el móvil o la tablet como una segunda pista sincronizada. Eso es exactamente lo que hace SideSubs.

**SideSubs** es una aplicación Android de segunda pantalla para Plex y Jellyfin, pensada para usar subtítulos bilingües sincronizados. Funciona con cualquier idioma de subtítulos compatible, no sólo con inglés y español.

Puedes seguir viendo contenido de Plex o Jellyfin en la televisión con un idioma de subtítulos y consultar simultáneamente otro idioma en el móvil o la tablet. Los subtítulos que muestra SideSubs son independientes de los seleccionados en el reproductor, por lo que ambos pueden utilizarse al mismo tiempo.

La aplicación nativa para Android es la versión principal de SideSubs. También existe un cliente Docker/Web experimental.

## Captura de pantalla

![Aplicación Android de SideSubs mostrando subtítulos en español sincronizados con Plex en una Samsung TV](docs/images/sidesubs-android-screenshot.jpg)

## Características

- Conexión directa con Plex y Jellyfin.
- Inicio de sesión mediante el flujo oficial de Plex y Quick Connect de Jellyfin.
- Descubrimiento automático de servidores Plex y conexión a Jellyfin mediante URL.
- Seguimiento de sesiones de reproducción activas de Plex y Jellyfin.
- Subtítulos sincronizados en una segunda pantalla.
- Selección de subtítulos independiente de la utilizada en el reproductor de Plex.
- Compatibilidad con subtítulos de texto externos e integrados.
- Selección automática por idioma preferido y selección manual de pista.
- Compatibilidad con variantes regionales, como español de España y español de Latinoamérica.
- Ajuste del retraso de los subtítulos.
- Tamaño de subtítulos configurable.
- Visualización simultánea del subtítulo actual y el siguiente.
- Modo cine para visualización horizontal a pantalla completa.
- Gestión del ciclo de vida de Android y de la sincronización al entrar o salir de la aplicación.
- Registro local de diagnóstico sin tokens, credenciales ni texto de los subtítulos.

## Aplicación Android

La aplicación Android funciona de forma independiente y **no necesita el servicio Docker**.

En el primer inicio, SideSubs permite elegir entre Plex y Jellyfin. Plex utiliza su flujo de inicio de sesión y el descubrimiento automático de servidores. Jellyfin se conecta mediante la URL del servidor y autentica con Quick Connect. Los tokens se almacenan internamente y no es necesario copiarlos manualmente en la aplicación.

Una vez conectado, SideSubs detecta las sesiones de reproducción activas, obtiene las pistas de subtítulos del servidor seleccionado y sincroniza la pista elegida con la reproducción.

### Formatos de subtítulos

SideSubs puede utilizar pistas de subtítulos de texto expuestas por Plex o Jellyfin, entre ellas:

- SRT / SubRip
- ASS / SSA
- WebVTT
- mov_text

Los formatos de subtítulos basados en imágenes, como PGS, no se representan actualmente como texto.

Para los subtítulos de texto integrados, SideSubs obtiene el subtítulo a través de Plex, analiza la línea temporal completa y la mantiene localmente para sincronizarla con la reproducción.

### Selección de idioma

En Ajustes se puede configurar un idioma de subtítulos preferido.

Cuando los metadatos del servidor contienen suficiente información, SideSubs también distingue variantes regionales, por ejemplo:

- Español (España)
- Español (Latinoamérica)
- Inglés (Estados Unidos)
- Inglés (Reino Unido)
- Portugués (Portugal)
- Portugués (Brasil)

Si Plex sólo identifica el idioma base, SideSubs utiliza ese idioma como alternativa.

### Modo cine

El modo cine está pensado para utilizar SideSubs en horizontal como pantalla dedicada a los subtítulos.

La aplicación entra en pantalla completa, mantiene la pantalla encendida y oculta automáticamente las barras de estado y controles. Al tocar la pantalla, los controles vuelven a aparecer temporalmente.

Si SideSubs pasa a segundo plano y después se abre de nuevo mientras el modo cine está activo, recupera el estado horizontal y de pantalla completa y muestra brevemente los controles antes de volver a ocultarlos.

## Cómo funciona

1. SideSubs se conecta a Plex o Jellyfin.
2. Detecta las sesiones de reproducción activas.
3. Lee el contenido que se está reproduciendo y sus pistas de subtítulos.
4. Selecciona una pista mediante el idioma preferido o una elección manual.
5. Obtiene y analiza la línea temporal de los subtítulos.
6. Sigue la posición de reproducción del servidor y muestra sincronizados el subtítulo actual y el siguiente.
7. El retraso configurado se aplica localmente sin modificar la reproducción en Plex.

Si la sesión de Plex seleccionada desaparece, SideSubs no cambia silenciosamente a otro reproductor.

## Arquitectura

La aplicación Android utiliza una interfaz de proveedor de medios independiente de Plex:

```text
MediaProvider
    |
    +-- PlexClient          (implementado)
    +-- JellyfinClient      (implementado)
    +-- EmbyProvider        (futuro)
```

La interfaz trabaja con modelos genéricos de reproducción y subtítulos, mientras que los identificadores específicos de Plex permanecen dentro del proveedor de Plex.

Actualmente Plex y Jellyfin están implementados.

## Cliente Docker / Web — experimental

SideSubs también incluye un cliente Docker/Web para utilizarlo desde un navegador.

Funciona de forma independiente de la aplicación Android y puede ser útil en portátiles, tablets, ordenadores de sobremesa u otros dispositivos en los que no resulte práctico instalar la aplicación Android.

No se garantiza que tenga exactamente las mismas funciones que la aplicación Android.

### Imagen Docker

Cada push a `main` publica:

```text
ghcr.io/soyxan/sidesubs:latest
```

Cada compilación recibe además una etiqueta específica para el commit:

```text
ghcr.io/soyxan/sidesubs:sha-abc1234
```

### Docker Compose / Portainer

El archivo `docker-compose.yml` incluido utiliza directamente la imagen publicada en GHCR.

Configuración obligatoria:

```text
PLEX_TOKEN
```

Configuración habitual:

```text
MEDIA_PROVIDER=plex
PLEX_URL=http://host.docker.internal:32400
PLEX_CLIENT_FILTER=
PORT=8085
POLL_INTERVAL_MS=750
```

Ejemplo:

```yaml
services:
  sidesubs:
    image: ghcr.io/soyxan/sidesubs:latest
    container_name: sidesubs
    restart: unless-stopped

    ports:
      - "8085:8000"

    environment:
      MEDIA_PROVIDER: "plex"
      PLEX_URL: "http://host.docker.internal:32400"
      PLEX_TOKEN: "${PLEX_TOKEN}"
      POLL_INTERVAL_MS: "750"

    extra_hosts:
      - "host.docker.internal:host-gateway"
```

No es necesario montar la biblioteca multimedia como volumen.

Abre el cliente web en:

```text
http://IP-DE-TU-SERVIDOR:8085
```

No publiques nunca tu token de Plex en GitHub.

## Seguridad y privacidad

### Android

- La autorización de Plex y Jellyfin se almacena internamente en el dispositivo.
- Los tokens no se muestran en la interfaz.
- Los registros de diagnóstico no contienen tokens, credenciales ni texto de subtítulos.
- SideSubs no necesita acceso al sistema de archivos de la biblioteca multimedia de Plex.

### Docker/Web

El cliente Docker/Web está pensado principalmente para utilizarse en una red local de confianza.

- El token de Plex permanece en el servidor.
- El navegador no recibe el token de Plex.
- La interfaz web no dispone actualmente de una capa de autenticación propia.
- No se recomienda exponerla directamente a Internet sin añadir una capa de seguridad adecuada.

## Desarrollo

Android:

- Kotlin
- Interfaz Android nativa
- SDK mínimo 26
- SDK objetivo 36

Docker/Web:

- Python 3.12
- FastAPI
- Uvicorn

## Versionado

SideSubs utiliza etiquetas Git para las versiones oficiales.

- Las versiones Android generadas desde una etiqueta utilizan la misma versión semántica como `versionName`.
- Los pushes a `main` publican Docker `latest` y una etiqueta específica `sha-...`.
- Etiquetas como `v0.9.1` publican la versión Docker correspondiente.

## Licencia

SideSubs se distribuye bajo la [licencia MIT](LICENSE).
