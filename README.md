# msa
Spectrum analyzer control GUI, port from Basic to Java+JNA

<img src="/img/1-IMG_20200611_004908.jpg" alt="Kitten"
	title="A cute kitten" width="640" />
  
Original control software from the author of the project is presented in https://scottyspectrumanalyzer.us/control.html. It's written in Basic and it's crashy when not utilized with care.

The software uses a hardware-dedicated broker library, Win32 compatible. It calls another library from Cypress which talks via USB with a dedicated microcontroller codenamed CY7C68013A. It's actually a '51 with a much bigger, than usually, USB peripheral part. Input and outputs from this guy wire back-end CMOS registers of the analyzer. There's custom data exhange protocol happening via USB, and it's not a COM or HID over USB. This project is an alternative client of that MSA Win32 dll. Java calls it using JNA framework.
Assumption is that majority of the code base is auto translatable and performance critical functions are prone to enhancements just by being expressed by a different, more mature programming language.

Phase one is to obtain base functionality for analyzer with focus on reliable path filter switching. This isn't working reliably no matter how strong your PC is.
