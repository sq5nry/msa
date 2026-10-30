# msa-java: Modular Spectrum Analyzer software in Java

This is a port of `spectrumanalyzer.bas` (MSA v118, Liberty BASIC) and its `msadll.dll` to Java. It talks to the MSA's Cypress FX2 USB interface (G8KBB firmware, SLIM control board, `cb=3`) through **libusb-1.0**, using the Java Foreign Function & Memory API. There is no JNI, no 32-bit JVM and no CyUSB driver. Requires **JDK 25**.

## Modules

| Module | Contents | Depends on |
|---|---|---|
| `msa-hw` | USB/libusb, wire protocol, BASIC command sequences, FX2 firmware loader, simulator | nothing |
| `msa-core` | `config.txt` / calibration / `Prefs.txt`, synthesizer planning, sweep engine, settings model, trace processing, record/replay | `msa-hw` |
| `msa-tools` | `msa-probe` command line (`smoke`, `plan`, `sweep`, `console`, `record`, ...), runnable jar | `msa-core` |
| `msa-gui` | The spectrum analyser window (JavaFX): SA and SA-with-TG modes, runnable jar | `msa-core`, JavaFX |
| `msa-gui-spike` | JavaFX vs Swing drawing trial; throw-away, not part of the product | `msa-core`, JavaFX |

Dependencies only point downwards. Keep it that way: the GUI depends on `msa-core` and uses the sweep engine and settings model, never `msa.hw.usb` directly.

### Main classes in `msa-hw`

| Class | Role |
|---|---|
| `msa.hw.MsaHardware` | What the BASIC code uses from the DLL: versions, write, read ADCs, close |
| `msa.hw.UsbMsa` | The DLL logic: the read-up-to-5-replies ADC loop, plus timeouts, retry and draining of stale replies |
| `msa.hw.MsaProtocol` | Wire format: `[code, count, mode, data…]` on EP2, `MSA_RXBUFFER` replies on EP6, VID/PID, command codes |
| `msa.hw.SlimCommands` | BASIC USB routines ported byte for byte: `[ResetEitherDDSserUSB]`, `[CommandPLLslimUSB]`, `[CommandAllSlimsUSB]` (A7 step), filter, switches, PDM, ADC mux |
| `msa.hw.LoggingTransport` | Logs every transfer in msadll's `LOG_COMMANDS` text format, so old and new logs can be diffed |
| `msa.hw.usb.LibUsb` | Hand-written FFM bindings for the 13 libusb functions it needs |
| `msa.hw.usb.LibUsbTransport` | The real device: 0547:1015 (SLIM) or 0547:1013 (original board) |
| `msa.hw.usb.Fx2Loader`, `IntelHex` | Firmware download for a board that enumerates as 0547:1014 / 1012 ("before download") |
| `msa.hw.sim.SimulatedTransport` | A board without hardware: decodes the latch and A7 commands and answers ADC reads, for developing the sweep engine and UI |
| `msa.tools.MsaProbe` (in `msa-tools`) | The command-line tool |

## Build

```
mvn verify                                   # all modules, all tests (no MSA needed)
java -jar msa-tools\target\msa-probe.jar info
java -jar msa-tools\target\msa-probe.jar --sim smoke
```
In IntelliJ, open the folder and it imports as a Maven project with five modules.

**`msa-info/`** in the repository is this MSA's `MSA_Info` folder: `config.txt`, `MSA_Cal/` (path and
frequency calibration) and `MSA_Prefs/Prefs.txt`, copied byte for byte from the Liberty BASIC installation
(`Z:\scotty_MSA\MSA_Software\MSA_Info`, 2026-10-25). The tools and the GUI use only this folder: they look
for `msa-info/config.txt` in the working directory and its parents, then next to the jar, so they work
from the repository root, a module folder, IntelliJ or `java -jar`. `--msa-info DIR` still points
elsewhere if needed; the `MSA_INFO` environment variable is no longer used (you can remove it with
`reg delete HKCU\Environment /v MSA_INFO`). After a new calibration in the old program, copy its
`MSA_Cal` files here again. `.gitattributes` keeps the folder byte-exact (CR line endings).

`msa-info/msa-java.properties` holds the Java port's own settings:
* `rememberSettings=true` (default): the GUI saves the sweep panel, Y axes, trace styles, markers (by
  frequency) and marker options to `msa-info/MSA_Prefs/java-session.properties` (git-ignored) every few
  seconds and at exit, and restores them at the next start.
* `rememberSettings=false`: every start uses `Prefs.txt`, like the old program, and nothing is saved.
  Delete `java-session.properties` for a one-off fresh start.

Put `libusb-1.0.dll` in the repository root (it is git-ignored) or pass `-Dmsa.libusb=PATH`.

CI: `.github/workflows/build.yml` runs `mvn verify` on Linux and Windows for every push, and publishes `msa-probe.jar` and `msa-gui.jar` (Windows build) as a build artifact.

**Line endings:** `.gitattributes` keeps everything under `src/test/resources` byte-exact. The `Prefs.txt` and `config.txt` copies there have the BASIC program's CR / CR+LF endings, and the tests check the round trip byte for byte.

## Spectrum analyser GUI (`msa-gui`)

```
java -jar msa-gui\target\msa-gui.jar                       # the MSA over USB
java -jar msa-gui\target\msa-gui.jar --demo                # built-in demo signal, no hardware
java -jar msa-gui\target\msa-gui.jar --replay rec.csv      # a recording (msa-probe record, or File > Record Sweeps)
```
`--msa-info DIR` overrides the `msa-info` folder. In IntelliJ run `msa.gui.MsaGui`. The jar carries JavaFX for the
OS it was built on.

It starts from `MSA_Prefs/Prefs.txt`, like the BASIC program, and follows the BASIC window's layout and
behaviour (labels in brackets are the BASIC routines):

* **Graph:** Y2 (right) is the primary axis, Y1 (left) the secondary; grid, setup info column ([DrawSetupInfo]),
  marker table ([gDrawMarkerInfo]). Erase traces show the new sweep over the old one; stick traces keep earlier
  sweeps until Redraw; histogram styles on the primary axis.
* **Mouse and keys:** hold the left button in the graph to read frequency and values ([gMouseQuery]);
  double-click in the graph puts the selected marker (or L) on the nearest step; keys L, R, 1-6 put that marker
  under the mouse. Double-click on the left or right axis labels opens that axis' dialog, on the frequency
  labels or the setup info puts the cursor in the sweep panel.
* **Lower panel, three sections:**
  * left, markers: select, Delete, Clear Marks, - / + one step, frequency box with Enter, Mark->Cent, Expand L-R;
  * middle, the BASIC "Sweep Parameters" window as a fixed panel: centre/span or start/stop, Steps (Auto),
    Wait, Samples, RBW (filter path), Video, Band, horizontal divisions, linear/log, direction, and with TG
    hardware the generator: SA (signal generator frequency) or SA with TG (offset, Normal/Reverse).
    Lists and buttons act at once; text boxes on Enter or when you leave them (yellow = edited, not applied;
    red = rejected, reason in the status line);
  * right, sweep buttons: Running/Restart, Halt/Continue, HaltAtEnd/One Step, Step Left, Redraw, labelled as in BASIC.
* **Automatic Steps/Sweep** (on by default; untick Auto to type your own, kept in java-prefs.properties): in
  SA mode the step is at most half the RBW, so a carrier between two steps still reads within about 1 dB;
  rounded up to a multiple of 10, at least 100. There is no cap: 0-1000 MHz with the 31 kHz filter gives
  64,520 steps. Counts above 5000 (`AutoSteps.RECOMMENDED_MAX`) are shown in red as a hint that sweeps will
  be slow, automatic or typed; nothing asks for confirmation. The only limit is technical (1,000,000 steps,
  `SweepSettings.MAX_STEPS`). With the tracking generator the RBW does not limit the step, so Auto gives
  400 steps.
* **Automatic Wait** (on by default; untick its Auto to type your own, kept like Auto steps): recalculated
  whenever the video filter or the RBW path changes. 5 video time constants (2.7 kOhm x the magnitude
  capacitor in config.txt, as the BASIC videoMagTC: Wide 0.0054 ms, Mid 0.54 ms, Narrow 27 ms, XNarrow
  270 ms) plus 2 / RBW for the resolution filter to build up, rounded up, at least 1 ms. With this MSA's
  31 kHz filter: Wide 1 ms, Mid 3 ms, Narrow 136 ms, XNarrow 1351 ms; the 0.4 kHz filter adds 5 ms. The
  setup info shows "Wait=... (auto)". The BASIC program had a different auto wait (re-read until stable)
  until v117c13 and then dropped it; this one is a fixed calculation (`msa.sweep.AutoWait`).
* **Automatic Samples** (on by default, its own Auto box; kept like the others): ADC readings the FX2
  averages per point. 16 with the Wide video filter, where consecutive readings carry different noise and
  averaging smooths the trace for microseconds per point; 1 with Mid and slower, which already smooth far
  longer than the readings take (`msa.sweep.AutoSamples`).
* **Sweep time** (bottom bar, right): "Sweep ≈ 23.6 s (10,331 points × 2.3 ms)", the last measured sweep, and
  while sweeping the progress and time left. Time per point = wait + 1 ms after each synthesiser step +
  USB/ADC time; the USB/ADC time starts as a 2 ms guess ("estimated") and is then measured on every
  completed sweep (per Samples setting); time left uses the current sweep's actual rate (`msa.sweep.SweepTime`).
* **Menus:** File (Save Image, Load/Save Prefs, Save Data, Record Sweeps), Edit (Copy Image), Setup (PDM
  Calibration; with the VNA hardware), Options (Markers, Sweep Parameters, Y1/Y2 axis, Show Variables, Reference
  Lines, Display Sweep Time, Refresh Screen Each Scan), Data (Graph Data, Input Data, S21 Parameters),
  Operating Cal and Mode (Spectrum Analyzer, Spectrum Analyzer with TG, VNA or SNA Transmission; shown when
  config.txt has a TG). See [VNA transmission](#vna-transmission) below.
* **Dialogs:** Y axis (range, autoscale,
  graph data dBm/W/V, trace width and style, divisions), Marker Options (L,R independent / P+,P- bounded /
  L,R -3 dB re P+ / L,R 3 dB re P-), Reference Lines (current data or fixed value per axis; data and ref,
  data+ref, data-ref, ref-data).

Not ported yet: colours and appearance presets, graph title, spur test, base frequency, Load Data and the BASIC
data-file format (Save Data writes a plain table), test setups, filter analysis, VNA reflection, the Smith chart,
the analysis functions (component meter, RLC, crystal, coax) and the hardware configuration and calibration managers. Autoscale is applied at the end of each sweep. The BASIC program's
"Refresh Screen Each Scan" is kept in Prefs but changes nothing here (every point is drawn as it arrives).

Pure rules (marker buttons, reference math, Sweep Parameters validation and log-sweep limits, automatic steps and wait) live in
`GraphState`, `SweepForm`, `msa.sweep.AutoSteps` and `msa.sweep.AutoWait`, without JavaFX, and have unit tests.

## VNA transmission

Mode > **VNA Transmission** (or the **VNA Trans** button in the sweep panel; **SNA Transmission**, magnitude only,
when config.txt has `hasVNA=0`). The TG tracks with no offset; connect it through the device under test to the
MSA input. As in BASIC the graph starts with S21 dB on Y2 (-100..0) and S21 phase on Y1 (-180..180) and
automatic steps give 400.

* **Phase measurement** ([ReadPhase], [ConvertPhadata], `msa.sweep.PhaseDetector`): phase ADC × 360 / maxpdmout,
  minus the inversion when the PDM was inverted (config.txt `invdeg`, scaled from 10.7 MHz to the path's final
  IF), minus the path calibration's phase-vs-level column, negated in the 3G band; 0.01 degree, -180..180.
  A reading in the lowest or highest 20 % of the detector range (the dead zone) inverts the PDM for that step
  and the rest of the sweep, waits `int(10 × phase video time constant)` ms (Wide 1 ms, Mid 20 ms, Narrow
  220 ms, XNarrow 1 s on this MSA) and reads again; the states carry over to the next sweep, as in BASIC.
  Automatic wait also covers the phase video filter (the slower of magnitude and phase; Mid becomes 11 ms).
* **Operating Cal > Perform Cal** ([RunVNACal]): with the TG connected straight through, **Perform Band Cal**
  restarts the sweep and stores the next complete sweep as the band calibration (the delay of the through
  connection, in ns, is removed from its phase). Reference To switches to Band Sweep Cal and the trace becomes
  0 dB / 0 degrees. **Save As Base** writes it to `msa-info/OperatingCal/BaseLineCal.txt` in the BASIC file
  format (your September baseline, 650-850 MHz on path 3, is in the project); the base calibration is
  interpolated to any sweep on the same path and fixture. Clear Band / Clear Base (deletes the file, asks first).
* **Operating Cal > Reference To**: Band Sweep Cal (falls back to Base if the band cal is not for this sweep), Baseline
  Cal, or No Reference. The setup info shows `Cal=Band/Base/None`, orange when it fell back. **Update Cal**
  re-measures a current band calibration.
* **Plane Ext** (sweep panel, ns): advances the phase by 360° × f × t, for the cable between the calibration plane
  and the device.
* **Graph data** (Y axis dialog): S21 dB, S21 phase, raw power (dBm), raw phase, insertion loss, group delay,
  S21 ratio. Group delay is −Δphase/Δf between neighbouring steps, so it needs small steps and a quiet trace.
* **Setup > PDM Calibration** ([menuCalPDM]): halted at zero span on a frequency where the phase is near ±90°,
  reads the phase with the PDM normal and inverted (5 s settling each) and saves the result as `invdeg` in
  config.txt (previous file kept as config.txt.bak).
* **Data:** Input Data adds the phase ADC, PDM state and phase; S21 Parameters lists S21 and the reference at each
  step; Save Data writes S21 dB/deg, raw power and phase, ADCs and PDM.

Changed on purpose: the reference and plane extension are applied when values are shown, not baked into the
measurement, so changing Reference To or Plane Ext updates the trace on the screen at once (BASIC needs a
restart or "Recalc"); a band calibration is used only for the same path (BASIC checks only steps and range);
a base calibration is not limited to 2,000 steps; after a calibration sweep the sweep keeps running.

`--demo` simulates a cable and a test fixture in the transmission modes (Mode > Demo Test Fixture: through,
200 MHz band-pass, 30 pF low-pass) with a phase detector that honours the PDM, so the calibration and the
dead-zone handling can be tried without the MSA.

## VNA reflection

Mode > **VNA Reflection** (or **VNA Refl** in the sweep panel; needs `hasVNA=1`). The TG drives a reflection
bridge (or a series/shunt fixture) whose output goes to the MSA input; the device is on the bridge's test port.
The graph starts with S11 dB on Y2 and S11 phase on Y1. Graph data (axis window): S11 magnitude and phase, rho
and theta, |Z| and its angle, series Rs, Xs, Cs, Ls, parallel Rp, Xp, Cp, Lp, return loss, reflected power,
component Q and VSWR (CalcReflectDerivedData). **R0** in the sweep panel is the impedance the graphs refer to
(S11GraphR0); it may differ from the bridge R0, the data are transformed (ApplyExtensionAndTransformR0). The
plane extension counts twice (round trip) and is not used with a series fixture.

**Operating Cal > Perform Cal** opens the reflection calibration ([PerformOSLCal], ProcessOSLCal, CalcOSLCoeff):
* **Full OSL**: attach the Open, Short and Load in turn and click their Perform buttons (each takes one complete
  sweep), then Done. The error model is S = (M - b) / (a - c M) per step, M being the reading minus the
  reference (the standard that reads highest). The standards need not be ideal: pick a set from
  `MSA_Info/OperatingCal/CalSets` or choose **Custom** and type RLC specifications, e.g. the load measured at
  48.68 ohms as `RLC[P, R48.68, C0]`; **Save Set As...** keeps them as a set file. `Load4868.txt` (ideal open and
  short, 48.68 ohm load) is included.
* **Reference Cal**: one standard, the others taken as ideal: Open or Short on a bridge, Short in a series
  fixture, Open in a shunt fixture (with the connection delay).
* Reference To, Update Cal (re-measures the reference standard) and Save Band As Base work as in transmission.
  Unlike BASIC, which keeps the base OSL calibration in memory only, it is also saved in
  `MSA_Info/OperatingCal/BaseOSLCal.txt` (a msa-java format).

`--demo` simulates a bridge with -35 dB directivity, source mismatch and 3 ns of cable; Mode > Demo Test Fixture
offers the open, short, the 48.68 ohm load, a series RLC resonant at 200 MHz and 75 ohms with 10 nH.

Not ported yet: the Smith chart, the reflection analysis tools (RLC, S11 to S21), the S11 data window, coax
specifications in the standards (`Coax[...]`).

## Autotest

Setup > Autotest runs self tests the MSA can do on itself, on request, with only two kinds of wiring: nothing
connected to the input, and the tracking generator looped to the input with a short cable. The run asks for
the wiring before the first test and whenever it changes ("Skip these tests" skips that group). The tests drive
the main window's sweep engine, so the main graph shows what is being measured; the main window is blocked
while the Autotest window is open, and the user's sweep and markers are restored afterwards.

| Test | Wiring | What it checks |
|---|---|---|
| Zero-frequency sweep | none | Each filter path centred at 0 Hz, span 10 x RBW (the documentation's health test): peak level, centre offset (config.txt filter frequency), -3 dB bandwidth against config.txt, skirt rejection, the shape inside a filter mask (>= -3 dB within +-0.3 RBW, <= -20 dB beyond +-2.5 RBW) and against the reference; level spread between paths |
| A-to-D converters alive | none | Magnitude ADC neither stuck nor at full scale, shows noise; phase ADC moves (VNA) |
| Noise floor | none | Median level 100-110 MHz on the narrowest and widest paths |
| USB and ADC speed | none | Time per point at zero span, 1 and 16 samples |
| Spur scan (off by default, ~1 min) | none | Responses above the floor from 1 to 1000 MHz |
| TG output and range | TG loop | Mean level, ripple 1-1000 MHz (Frequency Range Test), highest frequency within 3 dB |
| Path calibrations agree | TG loop | The same signal read through every path |
| Video filters | TG loop | Level and phase the same with each video filter |
| Short-term stability | TG loop | Spread of 100 readings, magnitude and phase |
| Phase linearity (VNA) | TG loop | Delay of the loop, deviation from a straight line (loose limits: an input switch's amplifier adds its own curve), the curve against the reference, steps where the PDM inverts |
| PDM inversion angle (VNA) | TG loop | Measures the inversion angle and compares it with config.txt invdeg (not saved) |
| Input switch isolation, T to R (switch) | TG loop | TG on T, read with the T/R line on T (transmission) and on R (reflection): isolation, and that the line reaches the switch |
| Reflection (R) input path (switch) | TG to R | Level and flatness through the R input in reflection mode, curve against the reference |
| Input switch isolation, R to T (switch) | TG to R | As above, the other way round |
| Phase linearity of the R path (switch) | TG to R | The phase test in reflection mode, through the R input |

With an SA/VNA input switch (Wetterlin's MSA Input Switch), set it to SA for the "nothing connected" tests,
and to VNA with the TG on the transmission (T) input for the TG tests (the run asks before each group). The
switch's amplifier and attenuators are then part of the loop, so its levels and phase curve are this MSA's own:
save a reference and judge later runs against it. Tick "MSA has an SA/VNA input switch" in the Autotest window
(remembered in MSA_Info/Autotest/autotest.properties) to add the switch tests; they need the TG moved to the R
input once. The T/R selection follows the mode (transmit/reflect latch bit); the SA/VNA position is still set by
hand, which the prompts ask for.

Each value is judged PASS / WARN / FAIL against design limits; INFO values are reported only. Hovering a test
shows what it checks; a result shows its values, curves (with limit and reference curves) and hints on where to
look when something fails, taken from the construction and verification chapters of the documentation.

**Reference.** Healthy MSAs differ from the design values by several dB but repeat well, so once the unit checks
out, Save as Reference keeps the values and curves in `MSA_Info/Autotest` (`reference.properties`,
`trace-*.csv`). Later runs show the reference beside each value and turn a value that moved beyond a set
tolerance into at least a WARN. Save Report writes the results as text.

`--demo` runs the whole set (the Spur Scan finds the demo's carriers, as it should).

## Windows application (portable, no Java needed)

```
packaging\windows\build-windows.cmd                 (runs mvn package, then jpackage)
packaging\windows\build-windows.cmd -SkipBuild      (use the jars already built)
```
Needs Windows and JDK 25. Maven is used from PATH or `MAVEN_HOME`; if there is none, the script downloads
Apache Maven 3.9.9 once from archive.apache.org into `.tools\` (git-ignored), checks its SHA-512 and reuses
it next time. Also needs the 64-bit `libusb-1.0.dll` (repository root, `MSA_LIBUSB`, or
`-LibUsb PATH`). It makes `target\windows\MSA\` and `target\windows\MSA-windows-<version>.zip`:
`MSA.exe` (the GUI, no console), `msa-probe.exe` (command line), a Java runtime trimmed by jlink to the
modules the jars need (found with jdeps), `libusb-1.0.dll` next to the jars (the launchers pass
`-Dmsa.libusb=$APPDIR\libusb-1.0.dll`), `msa-info\` (copied without the session file) and `README.txt`.
About 70-90 MB. Unzip anywhere writable (not Program Files: settings are saved in `msa-info`).

The WinUSB driver cannot be bundled: once per PC it has to be installed with Zadig (README.txt in the zip
explains). Without it the window says "no MSA found ..." and keeps retrying.

CI builds the same zip on its Windows runner, with libusb 1.0.27 downloaded from the libusb releases,
and attaches it to the run as the `MSA-windows` artifact.

## GUI trial (JavaFX vs Swing)

Both trials draw the same live sweep with the same engine, trace store and plot maths. Run them from IntelliJ or the command line:
```
msa.spike.SwingSpike       [START STOP STEPS] [--replay FILE | --usb] [--msa-info DIR] [--wait MS]
msa.spike.FxSpike          (same arguments; the JavaFX Application class is FxSpikeApp)
```
The default is 0–1000 MHz in 800 steps on a built-in demo signal: a drifting carrier with harmonics over noise. Use `--replay` with a recording or `--usb` with the MSA. Space halts and continues.

The top line shows:
* frames per second
* paint time per frame
* points per second and sweep time
* how long a measured point takes to reach the UI thread

Paint times are not directly comparable between the two. JavaFX only records drawing commands in that time and rasterises them on its own render thread; Swing's time includes the drawing. Judge by frame rate, smoothness while resizing, and CPU load.

Measured in a virtual X display (software rendering, so only indicative), 801 points, demo signal:

| | fps | points/s | UI delivery delay |
|---|---|---|---|
| Swing | 62 | ~800 | 0.5 ms avg |
| JavaFX | 61 | ~800 | 0.5 ms avg |

Try `--wait 0` with a large step count (for example `0 1000 4000`) to stress both.

## Setup per operating system

**Windows**
1. Get the 64-bit `libusb-1.0.dll` from the release archive at https://libusb.info (folder `VS20xx/MS64/dll`). Put it next to the jar, or pass `-Dmsa.libusb=C:\path\libusb-1.0.dll`.
2. Bind the **WinUSB** driver to the MSA with Zadig (Options → List All Devices → "G8KBB MSA USB via Slim-CB i/f").
   * While WinUSB is bound, the old Liberty BASIC program **will not see the board**.
   * To switch back, reinstall the CyUSB driver from `usb_configure 2.11 and signed drivers` (Device Manager → Update driver → Browse).

**Linux**
1. `sudo apt install libusb-1.0-0`
2. Add a udev rule so you don't need root:
   ```
   # /etc/udev/rules.d/60-msa.rules
   SUBSYSTEM=="usb", ATTR{idVendor}=="0547", ATTR{idProduct}=="101[2-5]", MODE="0666"
   ```

**macOS**
1. `brew install libusb`

## First hardware test

```
java -jar msa-probe.jar info                 # expect bcdDevice 0x1024 with firmware v33
java -jar msa-probe.jar --log new.txt smoke  # full no-signal check, see below
java -jar msa-probe.jar smoke --listen       # same, and asks whether each band relay clicked
java -jar msa-probe.jar switch 2 narrow      # latch 4 by hand: band 2, Narrow video filter
java -jar msa-probe.jar read-adc 100 8       # 100 reads, 8-sample averaging in the FX2
java -jar msa-probe.jar --dry-run reset-dds 1   # print the bytes without opening the device
```

If `info` reports the board as **0547:1014**, the firmware is not running from the EEPROM. Run
`java -jar msa-probe.jar load-firmware msa-fw-cb-v33.hex` first. If it reports 0547:1015, the EEPROM already boots the firmware and no loader is needed.

### What `smoke` checks

| # | Test | Pass criterion |
|---|---|---|
| 1 | Device and firmware | Firmware version (returned in `ADC_reads_done`) is 28 or higher; v118 expects 33 |
| 2 | Initialise | Filter path 1, DDS1 and DDS3 serial reset, and PDM normal are all accepted |
| 3 | Replies to latch writes | No IN data arrives after a latch write (what msadll assumed) |
| 4 | ADC timing | Median round trip under 5 ms |
| 5 | FX2 averaging | Noise at 64 samples is under half the noise at 1 sample |
| 6 | Video filters (latch 4) | XNarrow noise is under half the Wide noise. This shows the latch-4 relays really switch |
| 7 | Band switches 1G→2G→3G→1G | Commands accepted; with `--listen`, each change clicks |
| 8 | Restore | Leaves the board at band 1, video Wide, forward, transmission, PDM normal |

Options:
* `--settle MS`: wait after each relay change. The default is 300 ms, on top of the 500 ms pulse hold that v118 also uses.
* `--n N`: readings per measurement (default 200).

The run exits with code 2 if anything FAILs.

Without LO1 running, no signal passes through the band filters. Test 7 can therefore only show that the relays switch, not the filters' passbands. The noise floor printed per band is for information only. Passband checks come with the sweep engine.

The A7 sweep-step command is deliberately left out of the CLI. It also latches PLL1/PLL3, so it belongs in the sweep engine, with real PLL words.

## Synthesiser and sweep

These are ported from `[InitializeHardware]`, `[CalculateAllStepsForLO1Synth]`, `[CalculateAllStepsForLO3Synth]`, `[Command2326R]` / `[Create2326N]` and `[CreateBaseForDDSarray]`. They read `config.txt` and the path calibration from the project's `msa-info` folder (or `--msa-info DIR`).

```
java -jar msa-probe.jar plan 100                      # no hardware: Variables Window values + bytes
java -jar msa-probe.jar tune 100 --count 20           # init, sit on 100 MHz, read ADC / dBm
java -jar msa-probe.jar sweep 0 1000 200 --csv s.csv  # 0-1000 MHz in band 1, 201 points
java -jar msa-probe.jar sweep 1000 2000 200 --band 2 --wait 10
```

Options:

| Option | Meaning | Default |
|---|---|---|
| `--path P` | Resolution filter path | 1 |
| `--band 0..3` | Band; 0 = auto (1G up to 1000 MHz, 2G up to 2000 MHz, 3G above) | 1, as in the old program |
| `--video V` | Video filter: `wide`, `mid`, `narrow` or `xnarrow` | `wide` |
| `--wait MS` | Wait before each ADC read: the BASIC "Wait" box, plus 1 ms after a module change | 5 |
| `--samples N` | Sample averaging in the board | 1 |
| `--tg-offset MHZ` | Tracking generator offset | 0 |
| `--sg MHZ` | Signal generator at a fixed frequency instead of the tracking generator | off |
| `--csv FILE` | Write every point with LO1, DDS1, N1, LO3, DDS3, N3, TG output and the real final IF | off |

With a narrow video filter, raise `--wait` to a few time constants. XNarrow is about 270 ms with your 100 uF cap.

**Before trusting a sweep:** compare `plan FREQ` with the old program's Variables Window at the same frequency and path. These should match to the last digit:
* dds1output, LO1, ncounter1, Bcounter1, Acounter1, rcounter1
* LO2, ncounter2
* LO3, dds3output
* SG/TG Out

**What is ported, and what is refused:** only LMX2326 PLLs in integer-N mode, TGtop 1 or 2, spur test off and baseFrequency 0, which is what this MSA's `config.txt` uses. Other PLL chips and fractional-N raise an error instead of guessing.

**dBm and the frequency calibration:** dBm is the magnitude-table value plus the frequency correction from `MSA_CalFreq.txt`, looked up by true frequency, as in `CalcFreqCorrection`. That file holds 1G, 2G and 3G corrections on one frequency axis; yours switches from the 1G to the 2G section at 1050.1 MHz. A band-1 sweep forced above 1050 MHz therefore picks up the 2G correction. The CSV column `freqCorrDb` shows the correction applied at each point.

**Checking the band filters:** connect TG output to MSA input, then sweep each band. In bands 2 and 3, the planner reports TG output the way the BASIC code does (LO3 − LO2, the 1G-equivalent frequency).

**`--sim`:** `sweep` uses a simulator with an invented 1 GHz low-pass, so the plumbing can be tried without the board. Its numbers are not a model of the MSA.

## Sweep engine

`msa.sweep.SweepEngine` is the sweep loop of the old program as a state machine, running on its own thread. The GUI will drive it. Every call (`start`, `apply`, `halt`, `haltAtEnd`, `resume`, `stepRight`, `stepLeft`, `restart`, `reconnect`, `close`) only queues a command, so it is safe from the UI thread. Results come back as listener events (`stateChanged`, `sweepStarted`, `point`, `sweepCompleted`, `error`) through an `Executor` of your choice, e.g. `Platform::runLater`. `snapshot()` returns a copy of the current trace.

States: IDLE → RUNNING ⇄ HALTED, ERROR (USB failure; `reconnect()` reopens the device), CLOSED.

Behaviour matching the old program:
* **Halt / Continue:** Halt stops after the current step. Continue goes on with the next step, or starts a new sweep if the halt came at the end.
* **Halt at end:** finishes the current sweep, then halts.
* **Step right / Step left** (only while halted): measures one point with 100 ms extra settling. Step left turns the direction to right-to-left, as in the old program.
* **Changes that restart the sweep:** range, steps, band, path or generator. While halted, the change is applied but the engine stays halted, and Continue starts the new sweep.
* **Changes applied live:** wait, samples, video filter and direction take effect without a restart; direction applies from the next sweep.
* **Restart button:** re-initialises the hardware.

Changed on purpose:
* A Halt or Restart that arrives during a long settle wait (for example XNarrow) acts immediately. That step is measured again on Continue.
* Alternating sweeps measure the turning point again.
* A USB error closes the device and the engine waits for `reconnect()` instead of hanging.

Try it from the keyboard (`?` lists the commands):
```
java -jar msa-probe.jar console 0 1000 200 --wait 5
```

## Settings model

The GUI binds to `msa.model.MsaSettings`, which has two parts:

* **`SweepSettings`:** a `FrequencyRange` (start/stop, with a centre/span view), steps, linear or log spacing, direction (L-R, R-L or alternate), wait and samples, plus a `HardwareSetup`: path, video filter, band, and either the tracking generator (with offset) or the signal generator.
* **`DisplaySettings`:** graph data for Y1 and Y2, axis ranges, autoscale, linear/log axes, refresh each scan, grid divisions, background, grid and trace colours.

Persistence:
* **`MSA_Prefs\Prefs.txt`** has the old program's format (the copy in `msa-info` started as the old program's file). It is read losslessly, including its mixed line endings: CR inside a block, CR+LF after `EndContext`. A save rewrites only the lines whose value changed, keeps the previous file as `Prefs.txt.bak`, and never adds keys or blocks; the old program rejects unknown blocks.
* **`MSA_Prefs\java-prefs.properties`** holds what the old program doesn't save: samples, and the alternating direction.

Loading is tolerant. Invalid values, and values for features not ported yet (VNA reflection, base frequency offset), fall back to defaults with a warning. `msaMode`, `PlaneAdj`, `S21Jig` and `S11Bridge` are read from and written to Prefs.txt; the Reference To choice and the through delay go to java-prefs.properties.

`IsTG=0`, the old program's plain "Spectrum Analyzer" mode, loads as the signal generator at `SGPreset` (10 MHz by default). That matches the BASIC code, which still drives the TG hardware in that mode.

From another thread, change engine settings with `SweepEngine.update(s -> s.withSteps(200))`. Avoid `apply(settings().with…)`: it can lose an edit that is still queued.

`console` now starts from `Prefs.txt`; command-line values override it. `show` prints the current settings and `save` writes them back.

## Trace processing (`msa.trace`)

These classes are ported from `[ConvertMagPhaseData]`, `CalcGraphData` (the SA part), `mUpdateMarkerLocations` / `gFindPeaks`, and the reference-line math. None of them depend on a GUI.

* **Per-point power:** ADC → path magnitude table → plus the frequency correction at the true frequency → rounded to 5 decimals, half away from zero, as in the old program. This happens in `Sweeper.read`.
* **`GraphData`:** dBm, watts (`10^(dBm/10)/1000`) or volts (`sqrt(mW/20)`, RMS into 50 Ω), or no graph. It uses the old program's codes from `GraphData=` in Prefs.txt.
* **`SweepData`:** one sweep's frequencies, band, ADC readings and dBm. Values between steps, used by markers, are interpolated in graph units the way `CalcGraphData` does. Also converts frequency ↔ fractional step.
* **`TraceStore`:** an engine listener holding the sweep in progress, the last completed sweep, and a history of completed sweeps for "stick" display. The history is cleared when the frequency axis changes. `DisplayMode` covers erase/stick and normal/histogram, from `TraceModes=` in Prefs.txt (now part of `DisplaySettings` as well).
* **`Markers`:**
  * Markers 1–6, L, R and Halt sit at a step, which may be fractional.
  * P+ and P− follow the maximum and minimum, between L and R when they are set.
  * On a flat top, the peak marker goes to the middle of the plateau, as `gFindPeaks` does.
  * Readouts give frequency and value.
* **`Reference`:** a fixed line, or a stored trace interpolated to any frequency, plus the old program's math `y = A·ref + B·y`. `SUBTRACT_REFERENCE` gives "data minus reference".

The old program has no max hold or trace averaging, so none was ported; adding them later is straightforward. Not ported yet: the front-end correction file (File → Load Front End), which uses the Touchstone reader, and the filter-analysis −x dB markers.

## Record and replay

Recording sweeps from the real MSA lets you develop and test the rest of the program, including the GUI, without the hardware.

```
java -jar msa-probe.jar record 0 1000 200 --sweeps 10 --out band1.csv      # with TG looped, for example
java -jar msa-probe.jar console 0 1000 200 --replay band1.csv               # same engine, no MSA needed
java -jar msa-probe.jar sweep 900 1000 400 --replay band1.csv --csv zoom.csv
```
In the console, `rec FILE` starts recording what is being swept and `rec off` stops it.

**File format:** CSV with `#` comment lines. Columns: sweep, index, frequency, band, path, raw magnitude ADC, raw phase ADC, dBm for reading by eye, PDM state and phase (v2; v1 files without the last two still load). A `# settings` line is written whenever the settings change. Raw ADC values are stored, so a replay goes through the calibration again; phase readings are moved to the PDM state the replay commands, so VNA recordings replay through the dead-zone logic.

**How replay works:** `ReplayTransport` is the simulator with recorded data plugged in. It decodes the commands as the real board would: the A7 step command, the filter-bank bits in latch 1 and the band bits in latch 4. From those it works out the tuned frequency (LO1 = N1·DDS1/R1, then LO1 − LO2 + final IF, then the band's true frequency) and answers each ADC read with the recording at that frequency:
* **On a recorded frequency:** the recorded reading.
* **Between recorded frequencies:** interpolated from the two neighbours.
* **Outside the recorded span:** the noise floor, meaning the lowest recorded point.
* **Repeated sweeps** cycle through the recorded sweeps, so real noise is reproduced. Record several sweeps.
* **Samples > 1** averages consecutive recorded readings.
* **Paths:** a path that wasn't recorded falls back to one that was.

Not modelled: TG offset or SG frequency (you get what was recorded), video filter settling, and USB timing. A replay runs as fast as the wait setting allows.

## Comparing against the old program

There are two ways to get a reference log from the Liberty BASIC program.

* **USBPcap + Wireshark**, run while the old program sweeps. This needs no rebuild.
* **Rebuild `msadll.dll` with `#define LOG_COMMANDS`.** It writes the same `c:\vnalog.txt` format that `LoggingTransport` writes. One catch: in `MSADevice::Write()` the `LOG_COMMANDS` block sits inside the non-CyUSB `else` branch. With the CyUSB driver it would log reads only, so move that block above the `if (d->bUsingCyUSB)` test.

`LoggingTransport` marks its own extra lines (timeouts) with `#`. Run `grep -v '^#'` before diffing.

## What's verified and what isn't

**Verified here (83 unit tests across the modules, run on JDK 25).** The original hardware-layer checks:
* The command bytes match strings worked out by hand from the BASIC source, including the `message_size` the BASIC code passes for each.
* The ADC command is `B2 01 04 10 01` with v118's defaults.
* `MSA_RXBUFFER` parsing works.
* The firmware hex parses: it ends at 0x1F82, and its device descriptor reads VID 0547, PID 1015, bcdDevice 0x1024.
* The FX2 load sequence is correct: CPUCS hold, RAM writes, CPUCS release.
* The log format matches msadll's.
* The libusb FFM bindings load and call into a real libusb 1.0 (init, open, error names).

**Not yet verified on a real MSA:**
* **Reply behaviour.** Does the firmware send IN data only for B-commands? The DLL only reads after B-commands, and this code assumes the same. A stale reply is drained on open and before each retry.
* **`ADC_reads_done`.** It carries the firmware version (v21 and later, per change note 44 in the BASIC source). On your board `info` + `smoke` will show it.
* **The status byte.** The simulator returns 0x20 (data ready); what the real firmware returns is unknown.
* **Timing.** The BASIC code relied on DLL-call overhead for some pulse widths (it says "at least 75 us"). libusb on a fast PC may be quicker; check the filter and switch latching on a scope if anything misbehaves.

## Licence note

`msadll.h` says its device structure is taken from N5EG's VNAR, which is GPL-2.0-or-later. Because this code was written from that source, publish it under a GPL-compatible licence.
