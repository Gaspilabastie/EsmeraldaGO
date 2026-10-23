# EsmeraldaGO

App personal para Android que acompaña a Pokémon GO, con estética de Pokémon Esmeralda (GBA).

- Rankings PvE, PvP y Dinamax (oscuros, megas, ataques de legado) con tiers relativos al #1.
- Escaneo de tus Pokémon por OCR: capturas de pantalla o en vivo con una burbuja sobre el juego.
- Equipos, qué subir de nivel y qué atrapar, a partir de tu colección.
- Pokédex e incursiones actuales.

## Instalar / actualizar

Descargá el APK de la última [release](../../releases/latest). Las versiones siguientes se instalan desde la
app: **Opciones → Buscar actualización** (se instalan encima, sin perder datos).

## Desarrollo

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\DevTools\jdk17"
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleRelease
.\release.ps1 -Notes "Qué cambió"   # publica una versión nueva
```

Datos: [PvPoke](https://github.com/pvpoke/pvpoke) (MIT), [PokeMiners](https://github.com/PokeMiners/game_masters),
[ScrapedDuck](https://github.com/bigfoott/ScrapedDuck), sprites de [PokeAPI](https://github.com/PokeAPI/sprites).
Fuente Pixelify Sans (OFL).

No afiliada a Niantic, Nintendo ni The Pokémon Company.
