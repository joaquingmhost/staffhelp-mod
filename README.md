# StaffHelp (Fabric 1.21.1)

Los jugadores piden ayuda con `/ayuda <mensaje>` (alias `/sos`) y a cada staff conectado le llega:

    [AYUDA] Jugador: mensaje [TP]

`[TP]` es clicable y teletransporta al staff al jugador. Suena una campana al staff.

## Comandos
- `/ayuda <mensaje>` / `/sos <mensaje>`: cualquier jugador.
- `/staffhelp add <jugador>`: designa staff (jugador online u offline conocido por el server).
- `/staffhelp remove <nombre>`: quita a alguien del staff.
- `/staffhelp list`: lista el staff y quien esta conectado.
- `/staffhelp reload`: recarga `config/staffhelp.json`.
`/staffhelp` requiere OP nivel 3 o mas (configurable). Funciona desde la consola.

## Config: `config/staffhelp.json` (se crea sola)
- `cooldownSeconds`: espera entre pedidos (30 por defecto, el staff no tiene cooldown).
- `maxMessageLength`: largo maximo del mensaje (200).
- `adminPermissionLevel`: nivel OP para usar /staffhelp (3).
- `staff`: lista de {uuid, name}. Se guarda por UUID, asi que un cambio de nombre no rompe nada.

## Compilar
Requiere Java 21.

    ./gradlew build

El jar queda en `build/libs/staffhelp-1.0.0.jar` (NO uses el `-sources`).
Copialo a `mods/` del servidor junto con Fabric API 0.110.0+1.21.1.
