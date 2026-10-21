# TV Recovery Pro — All-in-One Recovery Project

Recovery-focused Android project for TV service/recovery work over USB-UART.

## Included
- USB serial support: PL2303, CH340/CH341, CP210x, FTDI/CDC where supported by the serial library
- TV/SoC profiles:
  - MStar / MSD
  - MediaTek / MTK
  - Realtek
  - HiSilicon
  - Amlogic
  - Panasonic
  - Nugget-MStar
  - Amazon Fire TV
  - Generic UART
- UART console
- ASCII / HEX transmit
- RX/TX counters
- Passive baud scan
- UART log saving
- BIN selection
- BIN header analysis
- Recovery workflow UI and progress display
- No activation-code/server dependency

## Important recovery limitation
There is no single universal recovery protocol for every TV chipset. The app therefore does not guess undocumented erase/write commands. A real firmware-write engine must be tied to the exact TV model, board number, SoC, bootloader/protocol, memory map, and firmware format.

This project is organized so verified protocol adapters can be added per profile without sending unsafe guessed commands.

## Recommended workflow
1. Connect the TV board's documented service UART using USB OTG + the correct USB-UART adapter.
2. Select the correct SoC/profile.
3. Detect/open the serial port.
4. Confirm baud rate from the board/service documentation.
5. Run the safe probe.
6. Select and inspect the correct firmware BIN.
7. Only use a verified recovery adapter for the exact board/model.
