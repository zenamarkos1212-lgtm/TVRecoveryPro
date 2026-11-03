# Recovery Protocol Adapter Specification

Each real recovery implementation should provide:

- `detect()` — identify the bootloader/SoC using documented, non-destructive commands.
- `handshake()` — establish the documented recovery session.
- `getInfo()` — read chip/board/bootloader information.
- `validateFirmware()` — verify BIN/header/size/model compatibility.
- `write()` — program only the documented address/range.
- `verify()` — read back or checksum according to the protocol.
- `reboot()` — exit recovery safely.

Required inputs before enabling write/erase:
- exact TV model
- mainboard/PCB number
- SoC/chip marking
- flash/eMMC type
- verified firmware file
- documented protocol or a known-good protocol implementation

Never substitute a generic erase/write packet for an unknown board.
