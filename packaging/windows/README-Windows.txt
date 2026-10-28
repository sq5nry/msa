MSA - Modular Spectrum Analyzer (Java port), portable Windows version
======================================================================

No installation and no Java needed: unzip anywhere you can write to (not under
"Program Files"), then double-click MSA.exe.

  MSA.exe          the spectrum analyser window
  msa-probe.exe    command-line tool (smoke test, single frequencies, recordings);
                   run it from a Command Prompt: msa-probe info, msa-probe smoke, ...
  msa-info\        this MSA's config.txt, calibration (MSA_Cal) and preferences
                   (MSA_Prefs). After a new calibration with the old program, copy
                   its MSA_Cal files here. msa-java.properties: rememberSettings.
  app\, runtime\   program, libusb-1.0.dll and the Java runtime

Without hardware:  MSA.exe --demo   (or --replay recording.csv)

USB driver - once per PC
------------------------
Windows must use the WinUSB driver for the MSA. If the window says
"no MSA found", install it with Zadig (https://zadig.akeo.ie):
  1. Plug in and power the MSA.
  2. Zadig: Options > List All Devices; pick the MSA (USB ID 0547 1015,
     "G8KBB MSA USB via Slim-CB i/f").
  3. Driver: WinUSB, then "Replace Driver" (or "Install Driver").

Licences: libusb-1.0.dll is LGPL-2.1 (https://libusb.info), the Java runtime
is OpenJDK (GPL-2.0 with Classpath Exception), JavaFX is OpenJFX (same).
