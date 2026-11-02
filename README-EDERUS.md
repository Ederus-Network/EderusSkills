# AuraSkills para Ederus

Fork de [AuraSkills](https://github.com/Archy-X/AuraSkills) (GPL-3.0) con los arreglos que usamos en los servidores de Ederus. El código original no se toca salvo en los cambios listados abajo.

## Ramas y remotos

- `ederus`: nuestra rama, sale de la etiqueta oficial de la versión que usamos (ahora `2.3.12`).
- `upstream`: el repositorio original de Archy-X.
- `origin`: https://github.com/Ederus-Network/AuraSkills

La versión del jar lleva el sufijo `-ederusN` (`projectVersion` en `gradle.properties`), así que en consola sale como `Enabling AuraSkills v2.3.12-ederus1`.

## Traer una versión nueva del original

```bash
git fetch upstream --tags
git checkout ederus
git merge 2.3.13          # la etiqueta de la versión nueva
```

Después de resolver conflictos, poner `projectVersion=<versión>-ederus1` en `gradle.properties`, compilar y probar en el SurvivalTest. Si el original ya arregla alguno de nuestros cambios, quitarlo de la lista de abajo.

## Compilar

Hace falta un JDK 21 (Gradle 8.14 no arranca con Java 25):

```bash
export JAVA_HOME=/c/Users/Dosa/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2
./gradlew build
```

El jar para el servidor queda en `build/libs/AuraSkills-<versión>.jar` (copia de `bukkit/build/libs/`).

## Cambios propios

1. **2.3.12-ederus1 — las columnas de plantas cuentan entero.** AuraSkills deja de contar el XP de una columna de algas, caña de azúcar, bambú o cactus al llegar a un bloque marcado como colocado por un jugador. Al romper un bloque solo borraba las marcas de la columna de encima mientras fueran seguidas (paraba en el primer bloque crecido) y nunca las de las algas, así que quedaban marcas huérfanas en huecos vacíos que cortaban el conteo de la siguiente planta que creciera por ahí. Ahora se borra toda la columna al romper (algas incluidas) y cualquier marca de un hueco donde crece una de esas plantas (`RegionBlockListener`). Sustituye al arreglo `PlantPlacedMarkFix` de EderFixes 1.0.0.
