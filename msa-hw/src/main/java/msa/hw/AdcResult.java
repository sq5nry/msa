package msa.hw;

/**
 * Result of one ADC read, the BASIC "USBrBuf" struct.
 *
 * @param readsDone MSA_RXBUFFER.ADC_reads_done. Firmware v21 and later put their version number here
 *                  (BASIC "fwver", change note 44); a value of 2 means firmware older than v21.
 * @param adc1      first data word: magnitude ADC, 0..65535 (12-bit ADCs are read as 16-bit)
 * @param adc2      second data word: phase ADC (0 when the firmware returned only one word)
 * @param status    MSA_RXBUFFER.return_status
 */
public record AdcResult(int readsDone, long adc1, long adc2, int status) {}
