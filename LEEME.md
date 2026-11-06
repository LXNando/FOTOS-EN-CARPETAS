# Fotos en Carpetas (Android)

App para celular Android que funciona **sin internet**:

- Crear carpetas y subcarpetas (sin límite de niveles).
- Entrar a una carpeta y tocar **📷 Tomar foto**: la foto se guarda directamente en esa carpeta.
- Ver las fotos a pantalla completa: deslizar para pasar, pellizcar o doble toque para hacer zoom.
- Mantener presionada una carpeta o foto para: renombrar, compartir, copiar a la Galería o eliminar.
- Opción **«Seguir tomando fotos»** (menú ⋮): después de cada foto se vuelve a abrir la cámara, ideal para tomar muchas seguidas.
- **«Copiar a la Galería»** (Android 10+): copia las fotos al álbum `Pictures/FotoCarpetas`, respetando las mismas carpetas.

No pide permiso de internet ni de cámara: usa la app de cámara del propio teléfono.
Requiere Android 7.0 o superior.

## Dónde quedan las fotos

En la memoria del teléfono, dentro del espacio de la app:
`Android/data/com.fotocarpetas.app/files/Pictures/FotoCarpetas/<tus carpetas>`

Se pueden copiar a una computadora por cable USB. **Si desinstalas la app, Android borra esas fotos**;
antes de desinstalar, usa «Copiar a la Galería» o «Compartir».

## Cómo obtener el APK (el archivo instalable)

### Opción A — Sin computadora, con GitHub (gratis)
1. Crea una cuenta en github.com y un repositorio nuevo.
2. Sube **todo el contenido** de esta carpeta (incluida la carpeta oculta `.github`).
   Si la carpeta `.github` no se sube, en GitHub ve a *Actions › set up a workflow yourself*,
   y pega el contenido de `.github/workflows/build-apk.yml`.
3. Ve a la pestaña **Actions** → «Generar APK». Se ejecuta solo al subir los archivos (o pulsa *Run workflow*).
4. Cuando termine (unos 3–5 min), abre la ejecución y descarga **FotosEnCarpetas-apk** (viene en un .zip con `app-debug.apk` dentro).

### Opción B — Con Android Studio (computadora)
1. Instala Android Studio (gratis) y abre esta carpeta con *File › Open*.
2. Espera a que termine de sincronizar.
3. Menú *Build › Build App Bundle(s) / APK(s) › Build APK(s)*.
4. El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`.

### Instalar en el celular
Copia `app-debug.apk` al teléfono, ábrelo y acepta «Permitir instalar apps de esta fuente» cuando lo pida.

## Estructura del código
- `MainActivity.java` — navegación por carpetas, crear/renombrar/eliminar, tomar fotos.
- `ViewerActivity.java` + `ZoomImageView.java` — visor con zoom y deslizamiento.
- `PhotoProvider.java` — permite que la cámara escriba la foto directo en la carpeta elegida.
- `ImageLoader.java` — miniaturas rápidas y corrección de rotación.
- `Actions.java` — compartir, copiar a la Galería, diálogos.
- `Storage.java` — manejo de archivos y carpetas.
