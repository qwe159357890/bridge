/*
 * JK_BMS_Comm.ino -- BLE Communication with JK-B2A20S20P
 * Version:   1.2.0  (2026-04-06)
 *
 * Target:    ESP32-C3 Super Mini (single-core RISC-V, 160 MHz)
 * BMS:       JK-B2A20S20P (20S NMC lithium-ion, HW v19H, SW 19.16)
 * Protocol:  JK02 BLE (Format B)
 * Transport: BLE via NimBLE + CAN via TWAI (SN65HVD230)
 *
 * Based on: peff74/Arduino-jk-bms (confirmed working, NimBLE)
 * Cross-ref: syssi/esphome-jk-bms, mintnguyenn/esp32-jkbms-ble
 *
 * ============================================================
 *  BLUEPRINT
 * ============================================================
 *
 *  BMS connects via BLE (no wiring to BMS needed).
 *  WiFi AP: SSID "JK_BMS" / password "bms12345"
 *           Browse to 192.168.4.1 for web status + BLE control
 *
 *  Web Page: 5 tabs (Status, Cells, Protection, Control, System)
 *    - Status:     Pack V/I/W, SOC, SOH, capacity, temps, uptime
 *    - Cells:      20 cell voltages, avg/min/max/delta, resistances
 *    - Protection: Editable settings (OVP/UVP/OTP/UTP, password)
 *    - Control:    MOS on/off, BLE disconnect/reconnect
 *    - System:     Device info, CAN stats, ESP32 health
 *    - AJAX live updates every 5 seconds (no page flicker)
 *
 *  CAN transceiver wiring:
 *    ESP32-C3 GPIO5 (TWAI TX) --> SN65HVD230 TXD
 *    ESP32-C3 GPIO6 (TWAI RX) <-- SN65HVD230 RXD
 *    SN65HVD230 RS --> GND (high-speed mode)
 *    CAN bus: 250 kbps, shared with charger/VESC/lighting
 *
 *  USB cable from ESP32-C3 to PC for PuTTY (115200 baud).
 *
 *  Arduino IDE Settings:
 *    Board:            ESP32C3 Dev Module
 *    USB CDC On Boot:  Enabled
 *    Partition Scheme: Minimal SPIFFS (1.9MB app)
 *    Upload Speed:     921600
 *
 *  Required Library:
 *    NimBLE-Arduino 2.5.0 (install via Library Manager)
 *
 *  PuTTY: Serial, COMx, 115200
 *
 *  Commands:
 *    p  Print full status report
 *    d  Dump last raw BLE frame (hex)
 *    i  Request device info
 *    t  Test 3rd command candidates
 *    x  Disconnect BLE (release for phone app)
 *    c  Reconnect BLE
 *    r  Reset counters
 *    h  Show help
 *
 * ============================================================
 *  BLE PROTOCOL (JK-B2A20S20P v19H, confirmed working)
 * ============================================================
 *
 *  Service: 0xFFE0, Characteristic: 0xFFE1 (write + notify)
 *  Command header: AA 55 90 EB, Response header: 55 AA EB 90
 *  Frame: 20 bytes out, ~300 bytes back (fragmented over BLE)
 *  CRC: 8-bit additive sum of bytes 0..N-1
 *
 *  Commands:
 *    0x97 = request device info (frame type 0x03)
 *    0x96 = request cell info (triggers auto-stream of 0x01+0x03+0x02)
 *    0x05 = send password (123456 = 0x0001E240, 4 bytes)
 *    0x1D = charge MOS control (0=off, 1=on, requires password)
 *    0x1E = discharge MOS control (0=off, 1=on, requires password)
 *
 *  Frame types (byte 4 of response):
 *    0x01 = settings/protection (sent once in burst after 0x96)
 *    0x02 = cell data (continuous stream after 0x96)
 *    0x03 = device info (sent once in burst after 0x96)
 *
 *  Connection sequence (proven working):
 *    1. NimBLE scan for MAC c8:47:80:47:c2:79
 *    2. Connect with params (24, 40, 0, 400)
 *    3. Subscribe to notifications on 0xFFE1
 *    4. delay(500), send 0x97 (device info request)
 *    5. delay(500), send 0x96 (cell info, triggers streaming)
 *    6. BMS streams 0x01 + 0x03 + 0x02 burst, then continuous 0x02
 *
 *  V19H Specifics:
 *    - UART/RS485 ports are dead (0V output), BLE is the only transport
 *    - Sends AT\r\n keepalive packets between data frames (filtered)
 *    - Sends 0xC8 ACK after commands (informational, no response needed)
 *    - Must send 0x96 to start streaming (does NOT auto-stream)
 *    - Settings frame (0x01) only sent once per 0x96 request
 *    - Must flush frame buffer before 0x96 or settings frame can be
 *      swallowed by in-progress 0x02 frame reassembly
 *    - BLE pairing code: 1234
 *    - Settings password: 123456 (required for MOS control and writes)
 *    - NMC pouch cells (NOT LiFePO4), 3.0-4.2V per cell
 *
 *  MOS Control (confirmed working):
 *    - Discharge MOS: toggle on/off verified with audible relay click
 *    - Charge MOS: command sent, not yet tested under charge conditions
 *    - Password 0x05 must be sent before any MOS or settings write
 *    - Local state updated immediately; BMS confirms in next 0x02 frame
 *
 *  Hardware Reset:
 *    - EN pin pulled LOW for 100ms = hard reset
 *    - Wire momentary button between EN and GND for manual reset
 *    - Wire to main brain GPIO for remote reset via CAN command
 *
 * ============================================================
 *  CAN BUS (250 kbps, Pylontech-compatible IDs)
 * ============================================================
 *
 *  Published every 1 second:
 *    0x351  Pack voltage, current, charge/discharge limits
 *    0x355  SOC, SOH, temperature
 *    0x356  MOS status, balance action, cycle count
 *    0x370-0x374  Cell voltages (4 per frame, 5 frames)
 *    0x375  Cell stats (min/max/delta/avg)
 *
 *  Received:
 *    0x1A0  BLE commands (0x01=disconnect, 0x02=reconnect, 0x03=status)
 *    0x1A1  BLE status reply
 *
 * ============================================================
 *  KNOWN LIMITATIONS
 * ============================================================
 *
 *  - Settings write via web page: untested (form exists, commands sent,
 *    but values not verified against BMS after write)
 *  - Settings frame (0x01) must be explicitly requested; not streamed
 *  - CAN TX errors when no transceiver wired (suppressed after 10)
 *  - Two beeps on connect (0x97 + 0x96 commands)
 *  - JK phone app sends 3 commands (3 beeps); 3rd command unknown
 */

#include <NimBLEDevice.h>
#include <WiFi.h>
#include <WebServer.h>
#include "driver/twai.h"

// ---Firmware Version -----------------------------------------

#define FW_VERSION "1.2.0"

// ---WiFi AP Settings -----------------------------------------

#define WIFI_SSID     "JK_BMS"
#define WIFI_PASS     "bms12345"
#define WIFI_CHANNEL  1

static WebServer webServer(80);
static bool wifiStarted = false;

// ---BMS MAC Address ------------------------------------------

static const char* BMS_MAC = "c8:47:80:47:c2:79";

// ---Protocol Constants ---------------------------------------

#define BLE_CMD_LEN       20
#define FRAME_BUF_SIZE   400
#define FRAME_COMPLETE   300
#define POLL_INTERVAL   5000    // ms -- re-request if no auto-stream
#define STATUS_INTERVAL 5000    // ms -- status line to serial
#define CAN_PUBLISH_MS  1000    // ms -- CAN publish interval

// ---CAN Pin Definitions -------------------------------------

#define CAN_TX_PIN  5   // GPIO5 --> SN65HVD230 TXD
#define CAN_RX_PIN  6   // GPIO6 <-- SN65HVD230 RXD

// ---CAN Message IDs (Pylontech-compatible + custom) ----------

#define CAN_ID_PACK_VI       0x351   // Pack voltage, current, limits
#define CAN_ID_SOC_SOH       0x355   // SOC, SOH, temp
#define CAN_ID_STATUS        0x356   // MOS status, balancing, cycles
#define CAN_ID_ALARMS        0x359   // Alarm flags
#define CAN_ID_CELL_1_4      0x370   // Cells 1-4 (mV, 16-bit each)
#define CAN_ID_CELL_5_8      0x371   // Cells 5-8
#define CAN_ID_CELL_9_12     0x372   // Cells 9-12
#define CAN_ID_CELL_13_16    0x373   // Cells 13-16
#define CAN_ID_CELL_17_20    0x374   // Cells 17-20
#define CAN_ID_CELL_STATS    0x375   // Min/max/delta/avg

// CAN command IDs (received from other modules)
#define CAN_ID_BMS_CMD       0x1A0   // Commands TO the BMS node
// Command bytes in data[0]:
#define CAN_CMD_BLE_DISCONNECT  0x01  // Release BLE for phone app
#define CAN_CMD_BLE_RECONNECT   0x02  // Reconnect BLE to BMS
#define CAN_CMD_BLE_STATUS      0x03  // Request BLE status reply

// ---Data Storage ---------------------------------------------

#define MAX_CELLS 24

struct BMSData {
  // Pack
  float    voltage;
  float    current;
  float    power;
  uint8_t  soc;

  // Cells
  uint8_t  cellCount;
  float    cellVoltage[MAX_CELLS];
  float    cellMin;
  float    cellMax;
  float    cellDelta;
  float    cellAvg;

  // Temps (Celsius from BMS, displayed as F)
  float    tempMos;
  float    tempBat1;
  float    tempBat2;

  // Cell resistances (milliohms)
  float    cellResistance[MAX_CELLS];

  // Capacity
  float    capacityRemainAh;
  float    capacityNominalAh;
  uint32_t cycleCount;
  float    totalCycleCapAh;
  uint8_t  soh;

  // Status
  bool     chargeMos;
  bool     dischargeMos;
  bool     balancing;
  uint8_t  balanceAction;
  float    balanceCurrent;

  // Uptime
  uint32_t uptimeSec;

  // Protection settings (from type 0x01 frame)
  float    cellOVP;             // Cell overvoltage protection (V)
  float    cellOVPRecovery;     // Cell OVP recovery (V)
  float    cellUVP;             // Cell undervoltage protection (V)
  float    cellUVPRecovery;     // Cell UVP recovery (V)
  float    maxChargeCurrent;    // Max charge current (A)
  float    maxDischargeCurrent; // Max discharge current (A)
  float    balanceTriggerV;     // Balance trigger voltage delta (V)
  float    balanceStartV;       // Balance starting voltage (V)
  float    maxBalanceCurrent;   // Max balance current (A)
  float    powerOffV;           // Pack power-off voltage (V)
  float    chargeOTP;           // Charge over-temp (C)
  float    chargeOTPRecovery;   // Charge over-temp recovery (C)
  float    dischargeOTP;        // Discharge over-temp (C)
  float    dischargeOTPRecovery;// Discharge over-temp recovery (C)
  float    chargeUTP;           // Charge under-temp (C, signed)
  float    chargeUTPRecovery;   // Charge under-temp recovery (C, signed)
  float    mosOTP;              // MOS over-temp (C)
  float    mosOTPRecovery;      // MOS over-temp recovery (C)
  uint8_t  cellCountSetting;    // Configured cell count
  float    nominalCapSetting;   // Configured capacity (Ah)
  bool     settingsValid;

  // Device info
  char     hwVersion[16];
  char     swVersion[16];
  char     deviceName[20];
  bool     deviceInfoValid;

  // Comm
  bool     valid;
  uint32_t lastSuccessMs;
  uint32_t successCount;
  uint32_t failCount;
  uint32_t atCount;
};

static BMSData bms;

// ---BLE State ------------------------------------------------

static NimBLEScan* pScan = NULL;
static NimBLERemoteCharacteristic* pChr = NULL;
static bool bleConnected = false;
static bool doConnect = false;
static bool doScan = true;
static const NimBLEAdvertisedDevice* advDevice = NULL;
static unsigned long lastNotifyMs = 0;

// ---Frame Reassembly ----------------------------------------

static uint8_t frameBuf[FRAME_BUF_SIZE];
static uint16_t framePos = 0;
static bool receivedStart = false;
static bool newData = false;
static uint8_t lastRawFrame[FRAME_BUF_SIZE];
static uint16_t lastRawLen = 0;
static int ignoreNotifyCount = 0;

// ---Timing ---------------------------------------------------

static unsigned long pollMs = 0;
static unsigned long statusMs = 0;
static unsigned long lastScanMs = 0;

// ---CAN State ------------------------------------------------

static bool canInitialized = false;
static uint32_t canTxCount = 0;
static uint32_t canTxErrors = 0;
static unsigned long canPublishMs = 0;

// ---BLE Hold-Off (user disconnect) ---------------------------

static bool bleHoldOff = false;

// ---System Health --------------------------------------------

static unsigned long loopCount = 0;
static unsigned long loopRateMs = 0;
static unsigned long loopsPerSec = 0;

// ---CRC ------------------------------------------------------

uint8_t calcCRC(const uint8_t *data, uint16_t len) {
  uint8_t crc = 0;
  for (uint16_t i = 0; i < len; i++) crc += data[i];
  return crc;
}

// ---Timestamp Print ------------------------------------------
// Prints [HH:MM:SS.mmm] prefix for all log lines

void ts() {
  unsigned long ms = millis();
  unsigned long sec = ms / 1000;
  unsigned long m = (sec / 60) % 60;
  unsigned long h = sec / 3600;
  unsigned long s = sec % 60;
  unsigned long frac = ms % 1000;
  char buf[16];
  sprintf(buf, "[%02lu:%02lu:%02lu.%03lu] ", h, m, s, frac);
  Serial.print(buf);
}

// ---Temperature Convert (0.1C to whole F) --------------------

int16_t toF(float celsius) {
  return (int16_t)(celsius * 9.0f / 5.0f + 32.0f);
}

// ---Write Register (from peff74, exact frame format) ---------

void writeRegister(uint8_t address, uint32_t value, uint8_t length) {
  if (!pChr) return;
  uint8_t frame[BLE_CMD_LEN];
  memset(frame, 0, BLE_CMD_LEN);
  frame[0] = 0xAA;
  frame[1] = 0x55;
  frame[2] = 0x90;
  frame[3] = 0xEB;
  frame[4] = address;
  frame[5] = length;
  frame[6] = (uint8_t)(value >> 0);
  frame[7] = (uint8_t)(value >> 8);
  frame[8] = (uint8_t)(value >> 16);
  frame[9] = (uint8_t)(value >> 24);
  frame[19] = calcCRC(frame, 19);

  pChr->writeValue(frame, BLE_CMD_LEN, false);
}

// ---Parse Cell Data (frame type 0x02) ------------------------
// Offsets from peff74/Arduino-jk-bms (JK02_32S style)
// Cell voltages at offset 6, 2 bytes each, little-endian, mV

void parseCellData() {
  newData = false;
  // V19H: no ignoreNotifyCount -- process every frame to catch
  // the single type 0x01 settings frame the BMS sends after 0x96

  // Cell voltages: offset 6, 2 bytes each LE
  bms.cellCount = 0;
  for (int i = 0; i < MAX_CELLS; i++) {
    uint16_t off = 6 + i * 2;
    uint16_t mv = (uint16_t)frameBuf[off] | ((uint16_t)frameBuf[off + 1] << 8);
    bms.cellVoltage[i] = mv * 0.001f;
    if (mv > 0) bms.cellCount = i + 1;
  }

  // Average and delta cell voltage
  uint16_t avgOff = 74;  // peff74: bytes 74-75
  bms.cellAvg = ((frameBuf[avgOff + 1] << 8) | frameBuf[avgOff]) * 0.001f;
  bms.cellDelta = ((frameBuf[avgOff + 3] << 8) | frameBuf[avgOff + 2]) * 0.001f;

  // Cell min/max from actual readings
  bms.cellMin = 99.0f;
  bms.cellMax = 0.0f;
  for (int i = 0; i < bms.cellCount; i++) {
    if (bms.cellVoltage[i] < bms.cellMin) bms.cellMin = bms.cellVoltage[i];
    if (bms.cellVoltage[i] > bms.cellMax) bms.cellMax = bms.cellVoltage[i];
  }

  // Cell resistances: offset 80+, 2 bytes each LE, milliohms
  // peff74: offset 81, step 2, for 20 cells
  for (int i = 0; i < bms.cellCount; i++) {
    uint16_t rOff = 80 + i * 2;
    uint16_t mOhm = (uint16_t)frameBuf[rOff] | ((uint16_t)frameBuf[rOff + 1] << 8);
    bms.cellResistance[i] = mOhm * 0.001f;
  }

  // MOS temperature: offset 144-145, signed, 0.1C
  int16_t rawT = (int16_t)((frameBuf[145] << 8) | frameBuf[144]);
  bms.tempMos = rawT * 0.1f;

  // Battery voltage: offset 150-153, uint32 LE, 0.001V
  uint32_t rawV = (uint32_t)frameBuf[150] | ((uint32_t)frameBuf[151] << 8) |
                  ((uint32_t)frameBuf[152] << 16) | ((uint32_t)frameBuf[153] << 24);
  bms.voltage = rawV * 0.001f;

  // Current: offset 158-161, int32 LE, 0.001A (positive = charging)
  int32_t rawI = (int32_t)((uint32_t)frameBuf[158] | ((uint32_t)frameBuf[159] << 8) |
                 ((uint32_t)frameBuf[160] << 16) | ((uint32_t)frameBuf[161] << 24));
  bms.current = rawI * 0.001f;
  bms.power = bms.voltage * bms.current;

  // Battery temp 1: offset 162-163, signed, 0.1C
  rawT = (int16_t)((frameBuf[163] << 8) | frameBuf[162]);
  bms.tempBat1 = rawT * 0.1f;

  // Battery temp 2: offset 164-165, signed, 0.1C
  rawT = (int16_t)((frameBuf[165] << 8) | frameBuf[164]);
  bms.tempBat2 = rawT * 0.1f;

  // Balance
  bms.balanceAction = frameBuf[172];
  bms.balancing = (bms.balanceAction > 0);

  // SOC: offset 173
  bms.soc = frameBuf[173];

  // Capacity remaining: offset 174-177, uint32 LE, 0.001Ah
  uint32_t rawCap = (uint32_t)frameBuf[174] | ((uint32_t)frameBuf[175] << 8) |
                    ((uint32_t)frameBuf[176] << 16) | ((uint32_t)frameBuf[177] << 24);
  bms.capacityRemainAh = rawCap * 0.001f;

  // Nominal capacity: offset 178-181
  rawCap = (uint32_t)frameBuf[178] | ((uint32_t)frameBuf[179] << 8) |
           ((uint32_t)frameBuf[180] << 16) | ((uint32_t)frameBuf[181] << 24);
  bms.capacityNominalAh = rawCap * 0.001f;

  // Cycle count: offset 182-185
  bms.cycleCount = (uint32_t)frameBuf[182] | ((uint32_t)frameBuf[183] << 8) |
                   ((uint32_t)frameBuf[184] << 16) | ((uint32_t)frameBuf[185] << 24);

  // Total cycle capacity: offset 186-189, uint32 LE, 0.001Ah
  uint32_t rawTotCap = (uint32_t)frameBuf[186] | ((uint32_t)frameBuf[187] << 8) |
                       ((uint32_t)frameBuf[188] << 16) | ((uint32_t)frameBuf[189] << 24);
  bms.totalCycleCapAh = rawTotCap * 0.001f;

  // SOH: offset 190
  bms.soh = frameBuf[190];

  // Uptime: offset 194-196, 3 bytes LE, seconds
  bms.uptimeSec = (uint32_t)frameBuf[194] | ((uint32_t)frameBuf[195] << 8) |
                  ((uint32_t)frameBuf[196] << 16);

  // Balance current: offset 170-171, int16 LE, 0.001A
  int16_t rawBal = (int16_t)((frameBuf[171] << 8) | frameBuf[170]);
  bms.balanceCurrent = rawBal * 0.001f;

  // MOS status: offset 198 charge, 199 discharge
  bms.chargeMos = (frameBuf[198] > 0);
  bms.dischargeMos = (frameBuf[199] > 0);

  bms.valid = true;
  bms.successCount++;
  bms.lastSuccessMs = millis();
}

// ---Parse Settings (frame type 0x01) -------------------------
// Offsets from syssi/esphome-jk-bms and peff74/Arduino-jk-bms

uint32_t readSettingsU32(uint16_t off) {
  return (uint32_t)frameBuf[off] | ((uint32_t)frameBuf[off+1] << 8) |
         ((uint32_t)frameBuf[off+2] << 16) | ((uint32_t)frameBuf[off+3] << 24);
}

int32_t readSettingsS32(uint16_t off) {
  return (int32_t)readSettingsU32(off);
}

void parseSettings() {
  // Voltage thresholds (V, scale 0.001)
  bms.cellUVP            = readSettingsU32(10) * 0.001f;
  bms.cellUVPRecovery    = readSettingsU32(14) * 0.001f;
  bms.cellOVP            = readSettingsU32(18) * 0.001f;
  bms.cellOVPRecovery    = readSettingsU32(22) * 0.001f;
  bms.balanceTriggerV    = readSettingsU32(26) * 0.001f;
  bms.powerOffV          = readSettingsU32(46) * 0.001f;
  bms.balanceStartV      = readSettingsU32(138) * 0.001f;

  // Current thresholds (A, scale 0.001)
  bms.maxChargeCurrent    = readSettingsU32(50) * 0.001f;
  bms.maxDischargeCurrent = readSettingsU32(62) * 0.001f;
  bms.maxBalanceCurrent   = readSettingsU32(78) * 0.001f;

  // Temperature thresholds (C, scale 0.1)
  bms.chargeOTP          = readSettingsU32(82) * 0.1f;
  bms.chargeOTPRecovery  = readSettingsU32(86) * 0.1f;
  bms.dischargeOTP       = readSettingsU32(90) * 0.1f;
  bms.dischargeOTPRecovery = readSettingsU32(94) * 0.1f;
  bms.chargeUTP          = readSettingsS32(98) * 0.1f;
  bms.chargeUTPRecovery  = readSettingsS32(102) * 0.1f;
  bms.mosOTP             = readSettingsU32(106) * 0.1f;
  bms.mosOTPRecovery     = readSettingsU32(110) * 0.1f;

  // Config
  bms.cellCountSetting   = (uint8_t)readSettingsU32(114);
  bms.nominalCapSetting  = readSettingsU32(130) * 0.001f;

  bms.settingsValid = true;
}

// ---Parse Device Info (frame type 0x03) ----------------------

void parseDeviceInfo() {
  memset(bms.hwVersion, 0, sizeof(bms.hwVersion));
  memcpy(bms.hwVersion, &frameBuf[22], 8);

  memset(bms.swVersion, 0, sizeof(bms.swVersion));
  memcpy(bms.swVersion, &frameBuf[30], 8);

  memset(bms.deviceName, 0, sizeof(bms.deviceName));
  memcpy(bms.deviceName, &frameBuf[46], 16);

  // Print only on first receive
  if (!bms.deviceInfoValid) {
    Serial.println();
    Serial.println("------------ Device Info ------------------");
    Serial.print("  Name:     "); Serial.println(bms.deviceName);
    Serial.print("  HW Ver:   "); Serial.println(bms.hwVersion);
    Serial.print("  SW Ver:   "); Serial.println(bms.swVersion);
    Serial.println("-------------------------------------------");
    Serial.println();
  }
  bms.deviceInfoValid = true;
}

// ---Handle Notification (from peff74, exact logic) -----------

static uint32_t totalNotifications = 0;

void handleNotification(uint8_t* pData, size_t length) {
  lastNotifyMs = millis();
  totalNotifications++;

  // Filter AT keepalives
  bool isAT = (length == 4 && pData[0] == 0x41 && pData[1] == 0x54 &&
               pData[2] == 0x0D && pData[3] == 0x0A);
  if (isAT) return;

  // Skip notifications after a cell data parse (peff74 pattern: skip leftover chunks)
  if (ignoreNotifyCount > 0) {
    ignoreNotifyCount--;
    return;
  }

  // Detect frame start: 55 AA EB 90
  if (length >= 4 && pData[0] == 0x55 && pData[1] == 0xAA &&
      pData[2] == 0xEB && pData[3] == 0x90) {
    framePos = 0;
    receivedStart = true;
    for (size_t i = 0; i < length; i++) {
      frameBuf[framePos++] = pData[i];
    }
    return;
  }

  // Continue assembling frame
  if (receivedStart) {
    for (size_t i = 0; i < length; i++) {
      frameBuf[framePos++] = pData[i];
      if (framePos >= FRAME_COMPLETE) {
        receivedStart = false;
        newData = true;

        // Save raw frame
        memcpy(lastRawFrame, frameBuf, framePos);
        lastRawLen = framePos;

        // Dispatch by frame type
        uint8_t frameType = frameBuf[4];
        switch (frameType) {
          case 0x01:
            parseSettings();
            ts(); Serial.println("[BLE] Settings frame parsed!");
            break;
          case 0x02:
            parseCellData();
            break;
          case 0x03:
            parseDeviceInfo();
            break;
          default:
            Serial.print("[BLE] Unknown frame type: 0x");
            Serial.println(frameType, HEX);
            break;
        }
        break;
      }
    }
  }
}

// ---NimBLE Notification Callback -----------------------------

void notifyCB(NimBLERemoteCharacteristic* pRemChr, uint8_t* pData,
              size_t length, bool isNotify) {
  handleNotification(pData, length);
}

// ---NimBLE Client Callbacks ----------------------------------

class ClientCB : public NimBLEClientCallbacks {
  void onConnect(NimBLEClient* pClient) override {
    bleConnected = true;
    ts(); Serial.println("[BLE] Connected!");
  }

  void onDisconnect(NimBLEClient* pClient, int reason) override {
    bleConnected = false;
    pChr = NULL;
    ts(); Serial.print("[BLE] Disconnected, reason: ");
    Serial.println(reason);
    doScan = true;
  }
};

// ---NimBLE Scan Callbacks ------------------------------------

class ScanCB : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice* device) override {
    std::string addr = device->getAddress().toString();
    std::string name = device->getName();

    // Only log BMS match, not every device
    if (addr == BMS_MAC) {
      ts(); Serial.print("[SCAN] BMS found: ");
      Serial.print(name.length() > 0 ? name.c_str() : addr.c_str());
      Serial.print(" (RSSI:");
      Serial.print(device->getRSSI());
      Serial.println(")");
      advDevice = device;
      doConnect = true;
      NimBLEDevice::getScan()->stop();
    }
  }
} scanCB;

// ---Connect to BMS (from peff74, exact sequence) -------------

bool connectToServer() {
  ts(); Serial.print("[BLE] Connecting to ");
  Serial.print(BMS_MAC);
  Serial.println("...");

  NimBLEClient* pClient = NimBLEDevice::getClientByPeerAddress(advDevice->getAddress());

  if (!pClient) {
    pClient = NimBLEDevice::createClient();
    pClient->setClientCallbacks(new ClientCB(), true);
    // Relaxed connection params for v19 BMS: 30ms interval, 4s timeout
    pClient->setConnectionParams(24, 40, 0, 400);
    pClient->setConnectTimeout(10000);
  }

  if (!pClient->connect(advDevice)) {
    ts(); Serial.println("[BLE] Connection failed");
    return false;
  }

  ts(); Serial.print("[BLE] Connected (RSSI:");
  Serial.print(pClient->getRssi());
  Serial.println(")");

  NimBLERemoteService* pSvc = pClient->getService("ffe0");
  if (!pSvc) {
    ts(); Serial.println("[BLE] Service 0xFFE0 not found");
    pClient->disconnect();
    return false;
  }

  pChr = pSvc->getCharacteristic("ffe1");
  if (!pChr || !pChr->canNotify()) {
    ts(); Serial.println("[BLE] Char 0xFFE1 not found");
    pClient->disconnect();
    return false;
  }

  if (!pChr->subscribe(true, notifyCB)) {
    ts(); Serial.println("[BLE] Subscribe failed");
    pClient->disconnect();
    return false;
  }

  ts(); Serial.println("[BLE] Subscribed, sending init commands...");

  // V19H needs 0x97 + 0x96 to start streaming.
  // Pairing code: 1234 | Settings password: 123456
  delay(500);
  writeRegister(0x97, 0x00000000, 0x00);
  delay(500);
  writeRegister(0x96, 0x00000000, 0x00);
  ts(); Serial.println("[BLE] Sent 0x97 + 0x96 -- streaming");

  return true;
}

// ---Display: Status Line (every 5s) -------------------------

void printStatusLine() {
  ts();
  if (!bleConnected) {
    Serial.println("[STATUS] BLE DISCONNECTED");
    return;
  }

  if (!bms.valid) {
    Serial.print("[STATUS] NO DATA | OK:");
    Serial.print(bms.successCount);
    Serial.print(" Fail:");
    Serial.println(bms.failCount);
    return;
  }

  Serial.println("[STATUS] ---- JK-B2A20S20P ----");

  Serial.print("  Pack Voltage:    ");
  Serial.print(bms.voltage, 2);
  Serial.println(" V");

  Serial.print("  Pack Current:    ");
  Serial.print(bms.current, 2);
  Serial.print(" A  (");
  Serial.print(bms.current > 0.01f ? "Charging" : (bms.current < -0.01f ? "Discharging" : "Idle"));
  Serial.println(")");

  Serial.print("  Pack Power:      ");
  Serial.print(bms.power, 1);
  Serial.println(" W");

  Serial.print("  State of Charge: ");
  Serial.print(bms.soc);
  Serial.println(" %");

  Serial.print("  State of Health: ");
  Serial.print(bms.soh);
  Serial.println(" %");

  Serial.print("  Remaining:       ");
  Serial.print(bms.capacityRemainAh, 1);
  Serial.print(" Ah / ");
  Serial.print(bms.capacityNominalAh, 1);
  Serial.println(" Ah");

  Serial.print("  Charge MOS:      ");
  Serial.println(bms.chargeMos ? "ON" : "OFF");

  Serial.print("  Discharge MOS:   ");
  Serial.println(bms.dischargeMos ? "ON" : "OFF");

  Serial.print("  Balancing:       ");
  Serial.print(bms.balancing ? "ACTIVE" : "OFF");
  if (bms.balancing) {
    Serial.print("  ");
    Serial.print(bms.balanceCurrent, 3);
    Serial.print(" A");
  }
  Serial.println();

  Serial.print("  MOS Temp:        ");
  Serial.print(bms.tempMos * 9.0f / 5.0f + 32.0f, 1);
  Serial.println(" F");

  Serial.print("  Battery Temp 1:  ");
  Serial.print(bms.tempBat1 * 9.0f / 5.0f + 32.0f, 1);
  Serial.println(" F");

  Serial.print("  Battery Temp 2:  ");
  Serial.print(bms.tempBat2 * 9.0f / 5.0f + 32.0f, 1);
  Serial.println(" F");

  Serial.print("  Cell Count:      ");
  Serial.print(bms.cellCount);
  Serial.println("S");

  // Individual cell voltages -- 4 per row
  for (uint8_t c = 0; c < bms.cellCount; c++) {
    if (c % 4 == 0) Serial.print("  ");
    Serial.print("C");
    if (c + 1 < 10) Serial.print("0");
    Serial.print(c + 1);
    Serial.print(":");
    Serial.print(bms.cellVoltage[c], 3);
    Serial.print("V");
    if ((c + 1) % 4 == 0 || c + 1 == bms.cellCount) {
      Serial.println();
    } else {
      Serial.print("  ");
    }
  }

  Serial.print("  Avg Cell:        ");
  Serial.print(bms.cellAvg, 3);
  Serial.println(" V");

  Serial.print("  Cell Min:        ");
  Serial.print(bms.cellMin, 3);
  Serial.println(" V");

  Serial.print("  Cell Max:        ");
  Serial.print(bms.cellMax, 3);
  Serial.println(" V");

  Serial.print("  Cell Delta:      ");
  Serial.print(bms.cellDelta * 1000, 1);
  Serial.println(" mV");

  // Cell resistances -- 4 per row (milliohms)
  Serial.println("  Cell Resistance (mOhm):");
  for (uint8_t c = 0; c < bms.cellCount; c++) {
    if (c % 4 == 0) Serial.print("  ");
    Serial.print("R");
    if (c + 1 < 10) Serial.print("0");
    Serial.print(c + 1);
    Serial.print(":");
    Serial.print(bms.cellResistance[c] * 1000, 1);
    if ((c + 1) % 4 == 0 || c + 1 == bms.cellCount) {
      Serial.println();
    } else {
      Serial.print("  ");
    }
  }

  Serial.print("  Cycle Count:     ");
  Serial.println(bms.cycleCount);

  Serial.print("  Total Cycle Cap: ");
  Serial.print(bms.totalCycleCapAh, 1);
  Serial.println(" Ah");

  // Uptime formatted
  Serial.print("  BMS Uptime:      ");
  uint32_t ut = bms.uptimeSec;
  uint32_t days = ut / 86400; ut %= 86400;
  uint32_t hrs  = ut / 3600;  ut %= 3600;
  uint32_t mins = ut / 60;    ut %= 60;
  if (days > 0) { Serial.print(days); Serial.print("d "); }
  Serial.print(hrs); Serial.print("h ");
  Serial.print(mins); Serial.print("m ");
  Serial.print(ut); Serial.println("s");

  // Protection settings
  if (bms.settingsValid) {
    Serial.println("  ---- Protection Settings ----");

    Serial.print("  Cell Over-Voltage:     ");
    Serial.print(bms.cellOVP, 3);
    Serial.print(" V  (recover ");
    Serial.print(bms.cellOVPRecovery, 3);
    Serial.println(" V)");

    Serial.print("  Cell Under-Voltage:    ");
    Serial.print(bms.cellUVP, 3);
    Serial.print(" V  (recover ");
    Serial.print(bms.cellUVPRecovery, 3);
    Serial.println(" V)");

    Serial.print("  Pack Power-Off:        ");
    Serial.print(bms.powerOffV, 2);
    Serial.println(" V");

    Serial.print("  Max Charge Current:    ");
    Serial.print(bms.maxChargeCurrent, 1);
    Serial.println(" A");

    Serial.print("  Max Discharge Current: ");
    Serial.print(bms.maxDischargeCurrent, 1);
    Serial.println(" A");

    Serial.print("  Balance Trigger:       ");
    Serial.print(bms.balanceTriggerV, 3);
    Serial.print(" V  Start:");
    Serial.print(bms.balanceStartV, 3);
    Serial.print(" V  Max:");
    Serial.print(bms.maxBalanceCurrent, 3);
    Serial.println(" A");

    Serial.print("  Charge Over-Temp:      ");
    Serial.print(bms.chargeOTP * 9.0f / 5.0f + 32.0f, 1);
    Serial.print(" F  (recover ");
    Serial.print(bms.chargeOTPRecovery * 9.0f / 5.0f + 32.0f, 1);
    Serial.println(" F)");

    Serial.print("  Discharge Over-Temp:   ");
    Serial.print(bms.dischargeOTP * 9.0f / 5.0f + 32.0f, 1);
    Serial.print(" F  (recover ");
    Serial.print(bms.dischargeOTPRecovery * 9.0f / 5.0f + 32.0f, 1);
    Serial.println(" F)");

    Serial.print("  Charge Under-Temp:     ");
    Serial.print(bms.chargeUTP * 9.0f / 5.0f + 32.0f, 1);
    Serial.print(" F  (recover ");
    Serial.print(bms.chargeUTPRecovery * 9.0f / 5.0f + 32.0f, 1);
    Serial.println(" F)");

    Serial.print("  MOS Over-Temp:         ");
    Serial.print(bms.mosOTP * 9.0f / 5.0f + 32.0f, 1);
    Serial.print(" F  (recover ");
    Serial.print(bms.mosOTPRecovery * 9.0f / 5.0f + 32.0f, 1);
    Serial.println(" F)");

    Serial.print("  Config Cells:    ");
    Serial.println(bms.cellCountSetting);

    Serial.print("  Config Capacity: ");
    Serial.print(bms.nominalCapSetting, 1);
    Serial.println(" Ah");
  }

  Serial.print("  BLE:             OK:");
  Serial.print(bms.successCount);
  Serial.print("  Fail:");
  Serial.println(bms.failCount);

  Serial.print("  CAN:             TX:");
  Serial.print(canTxCount);
  Serial.print("  Err:");
  Serial.print(canTxErrors);
  Serial.print(canInitialized ? "  (active)" : "  (no bus)");
  Serial.println();

  uint32_t freeK = ESP.getFreeHeap() / 1024;
  uint32_t minK = ESP.getMinFreeHeap() / 1024;
  float espF = temperatureRead() * 9.0f / 5.0f + 32.0f;

  Serial.print("  ESP32 Free RAM:  ");
  Serial.print(freeK);
  Serial.print(" KB (min:");
  Serial.print(minK);
  Serial.print(" KB) ");
  Serial.println(freeK > 60 ? "[OK]" : (freeK > 30 ? "[CAUTION]" : "[CRITICAL]"));

  Serial.print("  ESP32 Temp:      ");
  Serial.print(espF, 1);
  Serial.print(" F ");
  Serial.println(espF < 140 ? "[OK]" : (espF < 176 ? "[WARM]" : "[OVERHEAT]"));

  Serial.print("  Loop Rate:       ");
  Serial.print(loopsPerSec);
  Serial.print(" /sec ");
  Serial.println(loopsPerSec > 50 ? "[OK]" : (loopsPerSec > 10 ? "[SLOW]" : "[FROZEN]"));

  Serial.println("  -----------------------------");
}

// ---Display: Full Report -------------------------------------

void printFullReport() {
  Serial.println();
  Serial.println("------------ BMS Status Report ------------");

  if (!bms.valid) {
    Serial.println("  No valid data received yet.");
    Serial.print("  BLE: ");
    Serial.println(bleConnected ? "Connected" : "Disconnected");
    Serial.println("--------------------------------------------");
    return;
  }

  Serial.print("  Pack:       ");
  Serial.print(bms.voltage, 2);
  Serial.print("V  ");
  Serial.print(bms.current, 2);
  Serial.print("A  ");
  Serial.print(bms.power, 1);
  Serial.println("W");

  Serial.print("  SOC:        ");
  Serial.print(bms.soc);
  Serial.println("%");

  Serial.print("  Capacity:   ");
  Serial.print(bms.capacityRemainAh, 1);
  Serial.print("Ah / ");
  Serial.print(bms.capacityNominalAh, 1);
  Serial.print("Ah  Cycles:");
  Serial.println(bms.cycleCount);

  Serial.print("  MOS:        Charge:");
  Serial.print(bms.chargeMos ? "ON" : "OFF");
  Serial.print("  Discharge:");
  Serial.println(bms.dischargeMos ? "ON" : "OFF");

  Serial.print("  Balance:    ");
  Serial.println(bms.balancing ? "ACTIVE" : "OFF");

  Serial.print("  Temps:      MOS:");
  Serial.print(toF(bms.tempMos));
  Serial.print("F  Bat1:");
  Serial.print(toF(bms.tempBat1));
  Serial.print("F  Bat2:");
  Serial.print(toF(bms.tempBat2));
  Serial.println("F");

  if (bms.deviceInfoValid) {
    Serial.print("  Device:     ");
    Serial.print(bms.deviceName);
    Serial.print("  HW:");
    Serial.print(bms.hwVersion);
    Serial.print("  SW:");
    Serial.println(bms.swVersion);
  }

  // Cell voltages -- 4 columns
  Serial.println("  ---- Cells ----");
  for (uint8_t c = 0; c < bms.cellCount; c++) {
    Serial.print("  C");
    if (c + 1 < 10) Serial.print("0");
    Serial.print(c + 1);
    Serial.print(":");
    Serial.print((int)(bms.cellVoltage[c] * 1000));
    Serial.print("mV");

    if ((c + 1) % 4 == 0 || c + 1 == bms.cellCount) {
      Serial.println();
    } else {
      Serial.print("   ");
    }
  }

  Serial.print("  Min:");
  Serial.print((int)(bms.cellMin * 1000));
  Serial.print("mV  Max:");
  Serial.print((int)(bms.cellMax * 1000));
  Serial.print("mV  Delta:");
  Serial.print((int)(bms.cellDelta * 1000));
  Serial.println("mV");

  Serial.println("  ---- Comm ----");
  Serial.print("  BLE: ");
  Serial.print(bleConnected ? "Connected" : "Disconnected");
  Serial.print("  OK:");
  Serial.print(bms.successCount);
  Serial.print("  Fail:");
  Serial.println(bms.failCount);
  Serial.println("--------------------------------------------");
  Serial.println();
}

// ---Hex Dump -------------------------------------------------

void hexDump(const uint8_t *buf, uint16_t len) {
  Serial.print("[HEX] ");
  Serial.print(len);
  Serial.println(" bytes:");
  for (uint16_t i = 0; i < len; i++) {
    if (buf[i] < 0x10) Serial.print("0");
    Serial.print(buf[i], HEX);
    Serial.print(" ");
    if ((i + 1) % 16 == 0) {
      Serial.println();
      if (i + 1 < len) Serial.print("      ");
    }
  }
  if (len % 16 != 0) Serial.println();
}

// ---CAN Bus --------------------------------------------------

bool canInit() {
  twai_general_config_t g_config = TWAI_GENERAL_CONFIG_DEFAULT(
      (gpio_num_t)CAN_TX_PIN, (gpio_num_t)CAN_RX_PIN, TWAI_MODE_NORMAL);
  g_config.tx_queue_len = 10;

  twai_timing_config_t t_config = TWAI_TIMING_CONFIG_250KBITS();
  twai_filter_config_t f_config = TWAI_FILTER_CONFIG_ACCEPT_ALL();

  if (twai_driver_install(&g_config, &t_config, &f_config) != ESP_OK) {
    ts(); Serial.println("[CAN] Driver install failed");
    return false;
  }
  if (twai_start() != ESP_OK) {
    ts(); Serial.println("[CAN] Start failed");
    return false;
  }

  canInitialized = true;
  ts(); Serial.println("[CAN] Bus started (250kbps, GPIO5/6)");
  return true;
}

bool canSend(uint32_t id, const uint8_t *data, uint8_t len) {
  if (!canInitialized) return false;

  // If too many errors, bus is likely disconnected -- stop trying
  if (canTxErrors > 10 && canTxCount == 0) return false;

  twai_message_t msg;
  memset(&msg, 0, sizeof(msg));
  msg.identifier = id;
  msg.data_length_code = len;
  msg.extd = 0;  // Standard 11-bit IDs
  memcpy(msg.data, data, len);

  if (twai_transmit(&msg, pdMS_TO_TICKS(5)) == ESP_OK) {
    canTxCount++;
    return true;
  }
  canTxErrors++;
  return false;
}

void canPublishBMS() {
  if (!canInitialized || !bms.valid) return;

  uint8_t d[8];

  // 0x351: Pack voltage (mV uint16), current (10mA int16), max charge I, max discharge I
  uint16_t packMv = (uint16_t)(bms.voltage * 100);        // 0.01V units
  int16_t  packI  = (int16_t)(bms.current * 10);           // 0.1A units
  int16_t  maxChgI = (int16_t)(bms.maxChargeCurrent * 10);
  int16_t  maxDscI = (int16_t)(bms.maxDischargeCurrent * 10);
  d[0] = packMv & 0xFF;       d[1] = packMv >> 8;
  d[2] = packI & 0xFF;        d[3] = packI >> 8;
  d[4] = maxChgI & 0xFF;      d[5] = maxChgI >> 8;
  d[6] = maxDscI & 0xFF;      d[7] = maxDscI >> 8;
  canSend(CAN_ID_PACK_VI, d, 8);

  // 0x355: SOC (uint16 0.1%), SOH (uint16 0.1%), temp (int16 0.1C)
  uint16_t soc10 = (uint16_t)bms.soc * 10;
  uint16_t soh10 = (uint16_t)bms.soh * 10;
  int16_t  temp10 = (int16_t)(bms.tempBat1 * 10);
  d[0] = soc10 & 0xFF;        d[1] = soc10 >> 8;
  d[2] = soh10 & 0xFF;        d[3] = soh10 >> 8;
  d[4] = temp10 & 0xFF;       d[5] = temp10 >> 8;
  d[6] = 0; d[7] = 0;
  canSend(CAN_ID_SOC_SOH, d, 8);

  // 0x356: Status byte, balance action, cycle count
  d[0] = (bms.chargeMos ? 0x01 : 0) | (bms.dischargeMos ? 0x02 : 0) |
         (bms.balancing ? 0x04 : 0);
  d[1] = bms.balanceAction;
  d[2] = bms.cycleCount & 0xFF;       d[3] = (bms.cycleCount >> 8) & 0xFF;
  d[4] = bms.cellCount;
  d[5] = 0; d[6] = 0; d[7] = 0;
  canSend(CAN_ID_STATUS, d, 8);

  // 0x370-0x374: Cell voltages (4 cells per frame, mV uint16 LE)
  for (int grp = 0; grp < 5; grp++) {
    for (int c = 0; c < 4; c++) {
      int idx = grp * 4 + c;
      uint16_t mv = (idx < bms.cellCount) ? (uint16_t)(bms.cellVoltage[idx] * 1000) : 0;
      d[c * 2]     = mv & 0xFF;
      d[c * 2 + 1] = mv >> 8;
    }
    canSend(CAN_ID_CELL_1_4 + grp, d, 8);
  }

  // 0x375: Cell stats (min, max, delta, avg in mV)
  uint16_t cMin = (uint16_t)(bms.cellMin * 1000);
  uint16_t cMax = (uint16_t)(bms.cellMax * 1000);
  uint16_t cDel = (uint16_t)(bms.cellDelta * 1000);
  uint16_t cAvg = (uint16_t)(bms.cellAvg * 1000);
  d[0] = cMin & 0xFF; d[1] = cMin >> 8;
  d[2] = cMax & 0xFF; d[3] = cMax >> 8;
  d[4] = cDel & 0xFF; d[5] = cDel >> 8;
  d[6] = cAvg & 0xFF; d[7] = cAvg >> 8;
  canSend(CAN_ID_CELL_STATS, d, 8);
}

// ---CAN Receive (commands from other modules) ----------------

void canReceive() {
  if (!canInitialized) return;

  twai_message_t msg;
  // Non-blocking receive -- check for any pending frames
  while (twai_receive(&msg, 0) == ESP_OK) {
    if (msg.identifier == CAN_ID_BMS_CMD && msg.data_length_code >= 1) {
      switch (msg.data[0]) {
        case CAN_CMD_BLE_DISCONNECT:
          bleDisconnect();
          break;
        case CAN_CMD_BLE_RECONNECT:
          bleReconnect();
          break;
        case CAN_CMD_BLE_STATUS: {
          // Reply with BLE status on CAN
          uint8_t reply[8];
          reply[0] = bleConnected ? 1 : 0;
          reply[1] = bms.valid ? 1 : 0;
          reply[2] = bms.soc;
          reply[3] = bleHoldOff ? 1 : 0;
          reply[4] = 0; reply[5] = 0; reply[6] = 0; reply[7] = 0;
          canSend(CAN_ID_BMS_CMD + 1, reply, 8);  // Reply on 0x1A1
          break;
        }
        default:
          break;
      }
    }
  }
}

// ---BLE Disconnect / Reconnect --------------------------------

void bleDisconnect() {
  if (bleConnected) {
    NimBLEClient* pClient = NimBLEDevice::getClientByPeerAddress(advDevice->getAddress());
    if (pClient) pClient->disconnect();
  }
  bleHoldOff = true;
  doScan = false;
  ts(); Serial.println("[BLE] Disconnected -- use 'c' to reconnect");
}

void bleReconnect() {
  bleHoldOff = false;
  doScan = true;
  ts(); Serial.println("[BLE] Reconnect requested -- scanning...");
}

// ---WiFi Web Server ------------------------------------------

void sendChunk(const String &s) {
  webServer.sendContent(s);
}

void handleWebRoot() {
  webServer.setContentLength(CONTENT_LENGTH_UNKNOWN);
  webServer.send(200, "text/html", "");

  String s;

  // --- HEAD: CSS + tab styles ---
  s = "<!DOCTYPE html><html><head><title>JK BMS</title>"
    "<meta name='viewport' content='width=device-width,initial-scale=1'>"
    "<style>"
    "body{font-family:monospace;background:#111;color:#fff;padding:10px;font-size:14px;margin:0;}"
    "h1{text-align:center;margin:8px 0;font-size:18px;}"
    "h2{background:#1a2744;color:#fff;padding:6px 10px;margin:12px 0 4px 0;border-radius:4px;}"
    "table{border-collapse:collapse;width:100%;}"
    "td{padding:3px 8px;}td:first-child{color:#ccc;}td:last-child{color:#0f0;}"
    ".btn{padding:10px 20px;margin:5px;font-size:16px;border:none;border-radius:5px;cursor:pointer;}"
    ".red{background:#c00;color:#fff;}.grn{background:#0a0;color:#fff;}"
    ".cell{display:inline-block;width:120px;padding:2px;color:#0f0;}"
    ".clbl{color:#ccc;}.cval{color:#0f0;}"
    "input[type=number]{width:70px;background:#222;color:#0f0;border:1px solid #444;padding:3px;font-family:monospace;}"
    "input[type=password]{background:#222;color:#0f0;border:1px solid #444;padding:5px;font-family:monospace;width:120px;}"
    ".tabs{display:flex;flex-wrap:wrap;gap:2px;margin:8px 0;}"
    ".tab{padding:8px 12px;background:#1a2744;color:#aaa;border:none;border-radius:5px 5px 0 0;"
    "cursor:pointer;font-family:monospace;font-size:14px;}"
    ".tab.act{background:#2a4a7a;color:#fff;}"
    ".tp{display:none;}.tp.act{display:block;}"
    "</style></head><body>";
  sendChunk(s);

  // --- Title + connection summary bar ---
  s = "<h1>JK-B2A20S20P <span style='font-size:12px;color:#888;'>v" + String(FW_VERSION) + "</span></h1>"
    "<div style='text-align:center;color:#888;font-size:12px;'>"
    "BLE: <span id='hble' style='color:" + String(bleConnected ? "#0f0" : "#f00") + "'>"
    + String(bleConnected ? "Connected" : "Disconnected") + "</span>"
    " | SOC: <span id='hsoc' style='color:#0f0'>" + String(bms.soc) + "%</span>"
    " | <span id='upd'>Live</span></div>";
  sendChunk(s);

  // --- Tab buttons ---
  s = "<div class='tabs'>"
    "<button class='tab act' onclick='T(0)'>Status</button>"
    "<button class='tab' onclick='T(1)'>Cells</button>"
    "<button class='tab' onclick='T(2)'>Protection</button>"
    "<button class='tab' onclick='T(3)'>Control</button>"
    "<button class='tab' onclick='T(4)'>System</button>"
    "</div>";
  sendChunk(s);

  // ===================== TAB 0: STATUS ========================
  s = "<div class='tp act' id='t0'>";
  if (!bms.valid) {
    s += "<p>No BMS data yet.</p>";
  } else {
    s += "<h2>Pack</h2><table>"
      "<tr><td>Voltage</td><td><span id='dv'>" + String(bms.voltage, 2) + "</span> V</td></tr>"
      "<tr><td>Current</td><td><span id='di'>" + String(bms.current, 2) + "</span> A</td></tr>"
      "<tr><td>Power</td><td><span id='dw'>" + String(bms.power, 1) + "</span> W</td></tr>"
      "<tr><td>SOC</td><td><span id='dsoc'>" + String(bms.soc) + "</span> %</td></tr>"
      "<tr><td>SOH</td><td><span id='dsoh'>" + String(bms.soh) + "</span> %</td></tr>";
    sendChunk(s);

    s = "<tr><td>Remaining</td><td><span id='dcap'>" + String(bms.capacityRemainAh, 1) + " / " + String(bms.capacityNominalAh, 1) + "</span> Ah</td></tr>"
      "<tr><td>Charge MOS</td><td><span id='dcmos'>" + String(bms.chargeMos ? "ON" : "OFF") + "</span></td></tr>"
      "<tr><td>Discharge MOS</td><td><span id='ddmos'>" + String(bms.dischargeMos ? "ON" : "OFF") + "</span></td></tr>"
      "<tr><td>Balancing</td><td><span id='dbal'>" + String(bms.balancing ? "ACTIVE" : "OFF");
    if (bms.balancing) s += "  " + String(bms.balanceCurrent, 3) + " A";
    s += "</span></td></tr></table>";
    sendChunk(s);

    float mosF = bms.tempMos * 9.0f / 5.0f + 32.0f;
    float b1F = bms.tempBat1 * 9.0f / 5.0f + 32.0f;
    float b2F = bms.tempBat2 * 9.0f / 5.0f + 32.0f;
    s = "<h2>Temperatures</h2><table>"
      "<tr><td>MOS Temp</td><td><span id='dtm'>" + String(mosF, 1) + "</span> F</td></tr>"
      "<tr><td>Battery Temp 1</td><td><span id='dt1'>" + String(b1F, 1) + "</span> F</td></tr>"
      "<tr><td>Battery Temp 2</td><td><span id='dt2'>" + String(b2F, 1) + "</span> F</td></tr>"
      "</table>";
    sendChunk(s);

    uint32_t ut = bms.uptimeSec;
    s = "<h2>Lifetime</h2><table>"
      "<tr><td>Cycle Count</td><td><span id='dcyc'>" + String(bms.cycleCount) + "</span></td></tr>"
      "<tr><td>Total Cycle Cap</td><td><span id='dtcap'>" + String(bms.totalCycleCapAh, 1) + "</span> Ah</td></tr>"
      "<tr><td>BMS Uptime</td><td><span id='dup'>" + String(ut / 86400) + "d " + String((ut % 86400) / 3600) + "h " + String((ut % 3600) / 60) + "m</span></td></tr>"
      "</table>";
  }
  s += "</div>";
  sendChunk(s);

  // ===================== TAB 1: CELLS =========================
  s = "<div class='tp' id='t1'>";
  if (bms.valid) {
    s += "<h2>Cell Voltages (" + String(bms.cellCount) + "S)</h2><div>";
    sendChunk(s);

    s = "";
    for (int c = 0; c < bms.cellCount; c++) {
      s += "<span class='cell'><span class='clbl'>C" + String(c + 1 < 10 ? "0" : "") + String(c + 1) +
           ":</span><span class='cval' id='cv" + String(c) + "'>" + String(bms.cellVoltage[c], 3) + "V</span></span>";
      if ((c + 1) % 10 == 0) { sendChunk(s); s = ""; }
    }
    s += "</div><table>"
      "<tr><td>Avg</td><td><span id='cavg'>" + String(bms.cellAvg, 3) + "</span> V</td></tr>"
      "<tr><td>Min</td><td><span id='cmin'>" + String(bms.cellMin, 3) + "</span> V</td></tr>"
      "<tr><td>Max</td><td><span id='cmax'>" + String(bms.cellMax, 3) + "</span> V</td></tr>"
      "<tr><td>Delta</td><td><span id='cdel'>" + String(bms.cellDelta * 1000, 1) + "</span> mV</td></tr></table>";
    sendChunk(s);

    s = "<h2>Cell Resistance (mOhm)</h2><div>";
    for (int c = 0; c < bms.cellCount; c++) {
      s += "<span class='cell'><span class='clbl'>R" + String(c + 1 < 10 ? "0" : "") + String(c + 1) +
           ":</span><span class='cval' id='rv" + String(c) + "'>" + String(bms.cellResistance[c] * 1000, 1) + "</span></span>";
    }
    s += "</div>";
  } else {
    s += "<p>No cell data yet.</p>";
  }
  s += "</div>";
  sendChunk(s);

  // ===================== TAB 2: PROTECTION ====================
  s = "<div class='tp' id='t2'>";
  if (bms.settingsValid) {
    float cotpF = bms.chargeOTP * 9.0f / 5.0f + 32.0f;
    float cotprF = bms.chargeOTPRecovery * 9.0f / 5.0f + 32.0f;
    float dotpF = bms.dischargeOTP * 9.0f / 5.0f + 32.0f;
    float dotprF = bms.dischargeOTPRecovery * 9.0f / 5.0f + 32.0f;
    float cutpF = bms.chargeUTP * 9.0f / 5.0f + 32.0f;
    float cutprF = bms.chargeUTPRecovery * 9.0f / 5.0f + 32.0f;
    float motpF = bms.mosOTP * 9.0f / 5.0f + 32.0f;
    float motprF = bms.mosOTPRecovery * 9.0f / 5.0f + 32.0f;

    s += "<h2>Voltage Protection</h2><form action='/settings' method='POST'><table>"
      "<tr><td>Cell Over-Voltage</td><td><input name='ovp' type='number' step='0.001' value='" + String(bms.cellOVP, 3) + "'> V</td>"
      "<td>Recover</td><td><input name='ovpr' type='number' step='0.001' value='" + String(bms.cellOVPRecovery, 3) + "'> V</td></tr>"
      "<tr><td>Cell Under-Voltage</td><td><input name='uvp' type='number' step='0.001' value='" + String(bms.cellUVP, 3) + "'> V</td>"
      "<td>Recover</td><td><input name='uvpr' type='number' step='0.001' value='" + String(bms.cellUVPRecovery, 3) + "'> V</td></tr>"
      "<tr><td>Pack Power-Off Voltage</td><td><input name='poff' type='number' step='0.01' value='" + String(bms.powerOffV, 2) + "'> V</td><td></td><td></td></tr></table>";
    sendChunk(s);

    s = "<h2>Current Limits</h2><table>"
      "<tr><td>Max Charge Current</td><td><input name='mci' type='number' step='0.1' value='" + String(bms.maxChargeCurrent, 1) + "'> A</td>"
      "<td>Max Discharge Current</td><td><input name='mdi' type='number' step='0.1' value='" + String(bms.maxDischargeCurrent, 1) + "'> A</td></tr></table>";
    sendChunk(s);

    s = "<h2>Balance</h2><table>"
      "<tr><td>Balance Trigger Delta</td><td><input name='btv' type='number' step='0.001' value='" + String(bms.balanceTriggerV, 3) + "'> V</td>"
      "<td>Max Balance Current</td><td><input name='mbi' type='number' step='0.001' value='" + String(bms.maxBalanceCurrent, 3) + "'> A</td></tr>"
      "<tr><td>Start Voltage</td><td>" + String(bms.balanceStartV, 3) + " V</td><td></td><td></td></tr></table>";
    sendChunk(s);

    s = "<h2>Temperature Protection</h2><table>"
      "<tr><td>Charge Over-Temp<br><span style='color:#888;font-size:11px;'>Stops charging above</span></td>"
      "<td><input name='cotp' type='number' step='0.1' value='" + String(cotpF, 1) + "'> F</td>"
      "<td>Resume at</td><td><input name='cotpr' type='number' step='0.1' value='" + String(cotprF, 1) + "'> F</td></tr>"
      "<tr><td>Discharge Over-Temp<br><span style='color:#888;font-size:11px;'>Stops output above</span></td>"
      "<td><input name='dotp' type='number' step='0.1' value='" + String(dotpF, 1) + "'> F</td>"
      "<td>Resume at</td><td><input name='dotpr' type='number' step='0.1' value='" + String(dotprF, 1) + "'> F</td></tr>";
    sendChunk(s);

    s = "<tr><td>Charge Under-Temp<br><span style='color:#888;font-size:11px;'>Stops charging below</span></td>"
      "<td><input name='cutp' type='number' step='0.1' value='" + String(cutpF, 1) + "'> F</td>"
      "<td>Resume at</td><td><input name='cutpr' type='number' step='0.1' value='" + String(cutprF, 1) + "'> F</td></tr>"
      "<tr><td>MOS Over-Temp<br><span style='color:#888;font-size:11px;'>Stops all output above</span></td>"
      "<td><input name='motp' type='number' step='0.1' value='" + String(motpF, 1) + "'> F</td>"
      "<td>Resume at</td><td><input name='motpr' type='number' step='0.1' value='" + String(motprF, 1) + "'> F</td></tr></table>";
    sendChunk(s);

    s = "<h2>Configuration</h2><table>"
      "<tr><td>Capacity</td><td><input name='cap' type='number' step='0.1' value='" + String(bms.nominalCapSetting, 1) + "'> Ah</td>"
      "<td>Cells</td><td>" + String(bms.cellCountSetting) + " (read only)</td></tr></table>"
      "<p><input name='pwd' type='password' placeholder='Settings password'> "
      "<button type='submit' class='btn grn'>Save Settings</button></p></form>";
    s += "<p><a href='/reqsettings'><button class='btn' style='background:#1a2744;'>Refresh Settings</button></a></p>";
  } else {
    s += "<p>Protection settings not received yet. BMS sends type 0x01 frame once after connection.</p>"
      "<p><a href='/reqsettings'><button class='btn grn'>Request Settings</button></a></p>";
  }
  s += "</div>";
  sendChunk(s);

  // ===================== TAB 3: CONTROL =======================
  s = "<div class='tp' id='t3'>";
  if (bms.valid && bleConnected) {
    s += "<h2>MOS Control</h2><p>"
      "Charge: <b><span id='mcmos'>" + String(bms.chargeMos ? "ON" : "OFF") + "</span></b> "
      "<a href='/mos?sw=charge&v=" + String(bms.chargeMos ? "0" : "1") + "'>"
      "<button class='btn " + String(bms.chargeMos ? "red" : "grn") + "'>" + String(bms.chargeMos ? "Turn OFF" : "Turn ON") + "</button></a></p><p>"
      "Discharge: <b><span id='mdmos'>" + String(bms.dischargeMos ? "ON" : "OFF") + "</span></b> "
      "<a href='/mos?sw=discharge&v=" + String(bms.dischargeMos ? "0" : "1") + "'>"
      "<button class='btn " + String(bms.dischargeMos ? "red" : "grn") + "'>" + String(bms.dischargeMos ? "Turn OFF" : "Turn ON") + "</button></a></p>";
  } else {
    s += "<h2>MOS Control</h2><p style='color:#888;'>Requires BLE connection + valid data.</p>";
  }
  sendChunk(s);

  s = "<h2>BLE Connection</h2><p>Status: <b><span id='cble'>" + String(bleConnected ? "Connected" : "Disconnected") + "</span></b>";
  if (bleHoldOff) s += " (held off)";
  s += "</p><p>Frames OK: <span id='cok'>" + String(bms.successCount) + "</span> "
    "Fail: <span id='cfail'>" + String(bms.failCount) + "</span></p>"
    "<a href='/ble?cmd=disconnect'><button class='btn red'>Disconnect BLE</button></a> "
    "<a href='/ble?cmd=reconnect'><button class='btn grn'>Reconnect BLE</button></a>"
    "</div>";
  sendChunk(s);

  // ===================== TAB 4: SYSTEM ========================
  s = "<div class='tp' id='t4'>";
  if (bms.deviceInfoValid) {
    s += "<h2>Device Info</h2><table>"
      "<tr><td>Name</td><td>" + String(bms.deviceName) + "</td></tr>"
      "<tr><td>HW Version</td><td>" + String(bms.hwVersion) + "</td></tr>"
      "<tr><td>SW Version</td><td>" + String(bms.swVersion) + "</td></tr>"
      "<tr><td>MAC</td><td>" + String(BMS_MAC) + "</td></tr></table>";
  }
  sendChunk(s);

  s = "<h2>CAN Bus</h2><table>"
    "<tr><td>Status</td><td><span id='scan'>" + String(canInitialized ? "Active" : "No Bus") + "</span></td></tr>"
    "<tr><td>TX Count</td><td><span id='ctx'>" + String(canTxCount) + "</span></td></tr>"
    "<tr><td>TX Errors</td><td><span id='cerr'>" + String(canTxErrors) + "</span></td></tr></table>";
  sendChunk(s);

  uint32_t freeKB = ESP.getFreeHeap() / 1024;
  uint32_t minKB = ESP.getMinFreeHeap() / 1024;
  float espTempF = temperatureRead() * 9.0f / 5.0f + 32.0f;
  const char* ramColor = freeKB > 60 ? "#0f0" : (freeKB > 30 ? "#ff0" : "#f00");
  const char* ramLabel = freeKB > 60 ? "Healthy" : (freeKB > 30 ? "Caution" : "Critical");
  const char* tmpColor = espTempF < 140 ? "#0f0" : (espTempF < 176 ? "#ff0" : "#f00");
  const char* tmpLabel = espTempF < 140 ? "Normal" : (espTempF < 176 ? "Warm" : "Overheating");
  const char* lpColor = loopsPerSec > 50 ? "#0f0" : (loopsPerSec > 10 ? "#ff0" : "#f00");
  const char* lpLabel = loopsPerSec > 50 ? "Responsive" : (loopsPerSec > 10 ? "Sluggish" : "Frozen");

  s = "<h2>ESP32-C3 Health</h2><table>"
    "<tr><td>Free RAM</td><td style='color:" + String(ramColor) + "'><span id='sram'>" + String(freeKB) + " KB -- " + String(ramLabel) + "</span></td></tr>"
    "<tr><td>Min Free RAM</td><td><span id='sramm'>" + String(minKB) + " KB</span></td></tr>"
    "<tr><td>Die Temp</td><td style='color:" + String(tmpColor) + "'><span id='stmp'>" + String(espTempF, 1) + " F -- " + String(tmpLabel) + "</span></td></tr>"
    "<tr><td>Loop Rate</td><td style='color:" + String(lpColor) + "'><span id='slps'>" + String(loopsPerSec) + " /sec -- " + String(lpLabel) + "</span></td></tr></table>"
    "<p style='color:#555;font-size:11px;'>"
    "RAM: >60KB=green, 30-60=yellow, <30=red | Temp: <140F=green, 140-176=yellow, >176=red | Loop: >50=green, 10-50=yellow, <10=red</p>"
    "</div>";
  sendChunk(s);

  // --- JavaScript: tab switching + AJAX updates ---
  s = "<script>"
    "function T(n){var ts=document.querySelectorAll('.tp'),bs=document.querySelectorAll('.tab');"
    "for(var i=0;i<ts.length;i++){ts[i].className='tp';bs[i].className='tab';}"
    "ts[n].className='tp act';bs[n].className='tab act';}"
    "function S(id,v){var e=document.getElementById(id);if(e)e.textContent=v;}"
    "function u(){fetch('/data').then(r=>r.json()).then(d=>{"
    "if(!d.valid)return;"
    "S('dv',d.v.toFixed(2));S('di',d.i.toFixed(2));S('dw',d.w.toFixed(1));"
    "S('dsoc',d.soc);S('dsoh',d.soh);"
    "S('dcap',d.capR.toFixed(1)+' / '+d.capN.toFixed(1));"
    "S('dcmos',d.cmos?'ON':'OFF');S('ddmos',d.dmos?'ON':'OFF');"
    "S('mcmos',d.cmos?'ON':'OFF');S('mdmos',d.dmos?'ON':'OFF');"
    "S('dbal',d.bal?(d.balI?'ACTIVE  '+d.balI.toFixed(3)+' A':'ACTIVE'):'OFF');"
    "S('dtm',d.tM.toFixed(1));S('dt1',d.t1.toFixed(1));S('dt2',d.t2.toFixed(1));"
    "S('dcyc',d.cyc);S('dtcap',d.tcap.toFixed(1));"
    "var u=d.up,dy=Math.floor(u/86400),hr=Math.floor((u%86400)/3600),mn=Math.floor((u%3600)/60);"
    "S('dup',dy+'d '+hr+'h '+mn+'m');"
    "S('cavg',d.avg.toFixed(3));S('cmin',d.cmin.toFixed(3));"
    "S('cmax',d.cmax.toFixed(3));S('cdel',d.cdel.toFixed(1));"
    "for(var i=0;i<d.cells.length;i++){S('cv'+i,d.cells[i].toFixed(3)+'V');}"
    "for(var i=0;i<d.res.length;i++){S('rv'+i,d.res[i].toFixed(1));}"
    "S('hsoc',d.soc+'%');S('hble',d.ble?'Connected':'Disconnected');"
    "var hb=document.getElementById('hble');if(hb)hb.style.color=d.ble?'#0f0':'#f00';"
    "S('cble',d.ble?'Connected':'Disconnected');"
    "S('cok',d.ok);S('cfail',d.fail);"
    "S('ctx',d.canTx);S('cerr',d.canErr);"
    "var rk=d.ram,rc=rk>60?'#0f0':(rk>30?'#ff0':'#f00'),rl=rk>60?'Healthy':(rk>30?'Caution':'Critical');"
    "S('sram',rk+' KB -- '+rl);S('sramm',d.ramMin+' KB');"
    "var et=d.espT,tc=et<140?'#0f0':(et<176?'#ff0':'#f00'),tl=et<140?'Normal':(et<176?'Warm':'Overheating');"
    "S('stmp',et.toFixed(1)+' F -- '+tl);"
    "var lp=d.lps,lc=lp>50?'#0f0':(lp>10?'#ff0':'#f00'),ll=lp>50?'Responsive':(lp>10?'Sluggish':'Frozen');"
    "S('slps',lp+' /sec -- '+ll);"
    "S('upd','Updated '+new Date().toLocaleTimeString());"
    "}).catch(function(){S('upd','Update failed');});}"
    "setInterval(u,5000);"
    "</script></body></html>";
  sendChunk(s);

  webServer.sendContent("");
}

void handleWebData() {
  // JSON endpoint for AJAX updates -- no page reload needed
  String j = "{";
  j += "\"v\":" + String(bms.voltage, 2);
  j += ",\"i\":" + String(bms.current, 2);
  j += ",\"w\":" + String(bms.power, 1);
  j += ",\"soc\":" + String(bms.soc);
  j += ",\"soh\":" + String(bms.soh);
  j += ",\"capR\":" + String(bms.capacityRemainAh, 1);
  j += ",\"capN\":" + String(bms.capacityNominalAh, 1);
  j += ",\"cmos\":" + String(bms.chargeMos ? 1 : 0);
  j += ",\"dmos\":" + String(bms.dischargeMos ? 1 : 0);
  j += ",\"bal\":" + String(bms.balancing ? 1 : 0);
  j += ",\"tM\":" + String(bms.tempMos * 9.0f / 5.0f + 32.0f, 1);
  j += ",\"t1\":" + String(bms.tempBat1 * 9.0f / 5.0f + 32.0f, 1);
  j += ",\"t2\":" + String(bms.tempBat2 * 9.0f / 5.0f + 32.0f, 1);
  j += ",\"cc\":" + String(bms.cellCount);
  j += ",\"cyc\":" + String(bms.cycleCount);
  j += ",\"tcap\":" + String(bms.totalCycleCapAh, 1);
  j += ",\"up\":" + String(bms.uptimeSec);
  j += ",\"avg\":" + String(bms.cellAvg, 3);
  j += ",\"cmin\":" + String(bms.cellMin, 3);
  j += ",\"cmax\":" + String(bms.cellMax, 3);
  j += ",\"cdel\":" + String(bms.cellDelta * 1000, 1);
  j += ",\"ble\":" + String(bleConnected ? 1 : 0);
  j += ",\"ok\":" + String(bms.successCount);
  j += ",\"fail\":" + String(bms.failCount);
  j += ",\"canTx\":" + String(canTxCount);
  j += ",\"canErr\":" + String(canTxErrors);
  j += ",\"canOk\":" + String(canInitialized ? 1 : 0);
  j += ",\"ram\":" + String(ESP.getFreeHeap() / 1024);
  j += ",\"ramMin\":" + String(ESP.getMinFreeHeap() / 1024);
  j += ",\"espT\":" + String(temperatureRead() * 9.0f / 5.0f + 32.0f, 1);
  j += ",\"lps\":" + String(loopsPerSec);
  j += ",\"valid\":" + String(bms.valid ? 1 : 0);
  j += ",\"balI\":" + String(bms.balanceCurrent, 3);
  j += ",\"fw\":\"" + String(FW_VERSION) + "\"";

  // Cell voltages as array
  j += ",\"cells\":[";
  for (int c = 0; c < bms.cellCount; c++) {
    if (c > 0) j += ",";
    j += String(bms.cellVoltage[c], 3);
  }
  j += "]";

  // Cell resistances as array
  j += ",\"res\":[";
  for (int c = 0; c < bms.cellCount; c++) {
    if (c > 0) j += ",";
    j += String(bms.cellResistance[c] * 1000, 1);
  }
  j += "]";

  j += "}";
  webServer.send(200, "application/json", j);
}

void handleWebBLE() {
  String cmd = webServer.arg("cmd");
  if (cmd == "disconnect") {
    bleDisconnect();
  } else if (cmd == "reconnect") {
    bleReconnect();
  }
  // Redirect back to main page
  webServer.sendHeader("Location", "/");
  webServer.send(302, "text/plain", "Redirecting...");
}

// Helper: convert Fahrenheit input to Celsius, then to BMS register value (x10)
uint32_t fToReg(float fahrenheit) {
  float celsius = (fahrenheit - 32.0f) * 5.0f / 9.0f;
  return (uint32_t)(celsius * 10.0f);
}

// Helper: convert signed Fahrenheit to signed Celsius register (x10)
int32_t fToRegSigned(float fahrenheit) {
  float celsius = (fahrenheit - 32.0f) * 5.0f / 9.0f;
  return (int32_t)(celsius * 10.0f);
}

void handleWebSettings() {
  if (!bleConnected || !pChr) {
    webServer.send(400, "text/plain", "BLE not connected");
    return;
  }

  String pwd = webServer.arg("pwd");
  if (pwd != "123456") {
    webServer.send(403, "text/html",
      "<html><body style='background:#111;color:#f00;font-family:monospace;'>"
      "<h2>Wrong password</h2><a href='/'>Back</a></body></html>");
    return;
  }

  // Send password to unlock settings
  writeRegister(0x05, 0x0001E240, 0x04);  // 123456
  delay(300);

  // Write each setting if provided (values in V/A = x1000, temps in F converted to C x10)
  if (webServer.hasArg("ovp"))   writeRegister(0x04, (uint32_t)(webServer.arg("ovp").toFloat() * 1000), 0x04);
  if (webServer.hasArg("ovpr"))  writeRegister(0x05, (uint32_t)(webServer.arg("ovpr").toFloat() * 1000), 0x04);
  // Re-send password since 0x05 is also the password register
  delay(100);
  writeRegister(0x05, 0x0001E240, 0x04);
  delay(100);
  if (webServer.hasArg("uvp"))   writeRegister(0x02, (uint32_t)(webServer.arg("uvp").toFloat() * 1000), 0x04);
  if (webServer.hasArg("uvpr"))  writeRegister(0x03, (uint32_t)(webServer.arg("uvpr").toFloat() * 1000), 0x04);
  if (webServer.hasArg("poff"))  writeRegister(0x0B, (uint32_t)(webServer.arg("poff").toFloat() * 1000), 0x04);
  if (webServer.hasArg("mci"))   writeRegister(0x0C, (uint32_t)(webServer.arg("mci").toFloat() * 1000), 0x04);
  if (webServer.hasArg("mdi"))   writeRegister(0x0F, (uint32_t)(webServer.arg("mdi").toFloat() * 1000), 0x04);
  if (webServer.hasArg("btv"))   writeRegister(0x06, (uint32_t)(webServer.arg("btv").toFloat() * 1000), 0x04);
  if (webServer.hasArg("mbi"))   writeRegister(0x13, (uint32_t)(webServer.arg("mbi").toFloat() * 1000), 0x04);
  if (webServer.hasArg("cotp"))  writeRegister(0x14, fToReg(webServer.arg("cotp").toFloat()), 0x04);
  if (webServer.hasArg("cotpr")) writeRegister(0x15, fToReg(webServer.arg("cotpr").toFloat()), 0x04);
  if (webServer.hasArg("dotp"))  writeRegister(0x16, fToReg(webServer.arg("dotp").toFloat()), 0x04);
  if (webServer.hasArg("dotpr")) writeRegister(0x17, fToReg(webServer.arg("dotpr").toFloat()), 0x04);
  if (webServer.hasArg("cutp"))  writeRegister(0x18, (uint32_t)fToRegSigned(webServer.arg("cutp").toFloat()), 0x04);
  if (webServer.hasArg("cutpr")) writeRegister(0x19, (uint32_t)fToRegSigned(webServer.arg("cutpr").toFloat()), 0x04);
  if (webServer.hasArg("motp"))  writeRegister(0x1A, fToReg(webServer.arg("motp").toFloat()), 0x04);
  if (webServer.hasArg("motpr")) writeRegister(0x1B, fToReg(webServer.arg("motpr").toFloat()), 0x04);
  if (webServer.hasArg("cap"))   writeRegister(0x20, (uint32_t)(webServer.arg("cap").toFloat() * 1000), 0x04);

  delay(200);
  ts(); Serial.println("[WEB] Settings written to BMS");

  // Re-request settings to confirm written values
  bms.settingsValid = false;
  flushFrameBuffer();
  delay(300);
  writeRegister(0x96, 0x00000000, 0x00);
  ts(); Serial.println("[WEB] Re-requesting settings to confirm...");

  webServer.sendHeader("Location", "/");
  webServer.send(302, "text/plain", "Settings saved, redirecting...");
}

void handleWebMOS() {
  if (!bleConnected || !pChr) {
    webServer.send(400, "text/plain", "BLE not connected");
    return;
  }

  String sw = webServer.arg("sw");
  int val = webServer.arg("v").toInt();

  // Send password first
  writeRegister(0x05, 0x0001E240, 0x04);
  delay(200);

  if (sw == "charge") {
    writeRegister(0x1D, val ? 1 : 0, 0x04);
    bms.chargeMos = (val != 0);  // Update local state immediately
    ts(); Serial.print("[WEB] Charge MOS -> "); Serial.println(val ? "ON" : "OFF");
  } else if (sw == "discharge") {
    writeRegister(0x1E, val ? 1 : 0, 0x04);
    bms.dischargeMos = (val != 0);  // Update local state immediately
    ts(); Serial.print("[WEB] Discharge MOS -> "); Serial.println(val ? "ON" : "OFF");
  }

  // Wait for BMS to confirm with a fresh frame before redirecting
  delay(1500);
  webServer.sendHeader("Location", "/");
  webServer.send(302, "text/plain", "Redirecting...");
}

// Flush frame reassembly buffer so next frame header is cleanly detected.
// Without this, a 0x96 request can collide with an in-progress 0x02 frame
// and the 0x01 settings response header gets swallowed as data bytes.
void flushFrameBuffer() {
  framePos = 0;
  receivedStart = false;
  newData = false;
}

void handleWebReqSettings() {
  if (!bleConnected || !pChr) {
    webServer.send(400, "text/plain", "BLE not connected");
    return;
  }
  ts(); Serial.println("[WEB] Settings requested -- flushing buffer, sending 0x96");
  bms.settingsValid = false;
  flushFrameBuffer();
  delay(100);  // Let any in-flight notification finish
  writeRegister(0x96, 0x00000000, 0x00);
  webServer.sendHeader("Location", "/");
  webServer.send(302, "text/plain", "Requesting settings...");
}

void wifiInit() {
  WiFi.mode(WIFI_AP);
  WiFi.softAP(WIFI_SSID, WIFI_PASS, WIFI_CHANNEL, 0, 2);
  delay(100);

  webServer.on("/", handleWebRoot);
  webServer.on("/data", handleWebData);
  webServer.on("/ble", handleWebBLE);
  webServer.on("/settings", HTTP_POST, handleWebSettings);
  webServer.on("/reqsettings", handleWebReqSettings);
  webServer.on("/mos", handleWebMOS);
  webServer.begin();
  wifiStarted = true;

  ts(); Serial.print("[WIFI] AP started: ");
  Serial.print(WIFI_SSID);
  Serial.print(" / ");
  Serial.print(WIFI_PASS);
  Serial.print(" IP: ");
  Serial.println(WiFi.softAPIP());
}

// ---Serial Command Handler -----------------------------------

void handleSerialInput() {
  while (Serial.available()) {
    char c = Serial.read();
    switch (c) {
      case 'p': case 'P':
        printFullReport();
        break;
      case 'd': case 'D':
        if (lastRawLen > 0) {
          hexDump(lastRawFrame, lastRawLen);
        } else {
          Serial.println("[HEX] No frame captured yet");
        }
        break;
      case 'i': case 'I':
        if (pChr) {
          writeRegister(0x97, 0x00000000, 0x00);
          Serial.println("[BLE] Device info requested");
        } else {
          Serial.println("[BLE] Not connected");
        }
        break;
      case 't': case 'T':
        if (bleConnected && pChr) {
          ts(); Serial.println("[TEST] Sending known commands...");
          writeRegister(0x97, 0x00000000, 0x00);
          ts(); Serial.println("[TEST] 0x97 device info");
          delay(500);
          writeRegister(0x96, 0x00000000, 0x00);
          ts(); Serial.println("[TEST] 0x96 cell info");
          delay(500);
          // Password 123456 = 0x0001E240
          writeRegister(0x05, 0x0001E240, 0x04);
          ts(); Serial.println("[TEST] 0x05 password 123456");
          delay(500);
          ts(); Serial.println("[TEST] Done. Count the beeps!");
        } else {
          Serial.println("[TEST] Not connected");
        }
        break;
      case 'x': case 'X':
        bleDisconnect();
        break;
      case 'c':
        bleReconnect();
        break;
      case 'r':
        bms.successCount = 0;
        bms.failCount = 0;
        canTxCount = 0;
        canTxErrors = 0;
        Serial.println("[RESET] Counters cleared");
        break;
      case 'h': case 'H': case '?':
        Serial.println();
        Serial.println("------------ Commands ------------");
        Serial.println("  p  Print full status report");
        Serial.println("  d  Dump last raw BLE frame (hex)");
        Serial.println("  i  Request device info");
        Serial.println("  t  Test 3rd command candidates");
        Serial.println("  x  Disconnect BLE (free for app)");
        Serial.println("  c  Reconnect BLE");
        Serial.println("  r  Reset counters");
        Serial.println("  h  Show this help");
        Serial.println("----------------------------------");
        Serial.println();
        break;
      default:
        break;
    }
  }
}

// ---Setup ----------------------------------------------------

void setup() {
  Serial.begin(115200);
  delay(2000);

  Serial.println();
  Serial.println("========================================");
  Serial.print("  JK_BMS Communication v");
  Serial.println(FW_VERSION);
  Serial.println("  Device:   JK-B2A20S20P");
  Serial.println("  MAC:      c8:47:80:47:c2:79");
  Serial.println("  Protocol: JK02 BLE (Format B)");
  Serial.println("  Based on: peff74/Arduino-jk-bms");
  Serial.println("----------------------------------------");
  Serial.println("  BLE: auto-connect to BMS");
  Serial.println("  CAN: 250kbps on GPIO5/6");
  Serial.println("  Commands: p d i x c r h");
  Serial.println("========================================");
  Serial.println();

  memset(&bms, 0, sizeof(bms));

  // Init CAN bus
  canInit();

  // Init WiFi AP
  wifiInit();

  // Init BLE
  NimBLEDevice::init("JK_BMS_Reader");
  NimBLEDevice::setPower(3);

  pScan = NimBLEDevice::getScan();
  pScan->setScanCallbacks(&scanCB);
  pScan->setInterval(100);
  pScan->setWindow(100);
  pScan->setActiveScan(true);

  ts(); Serial.println("[SCAN] Scanning for JK BMS...");
  pScan->start(10000, false, true);
}

// ---Loop -----------------------------------------------------

void loop() {
  unsigned long now = millis();

  handleSerialInput();
  if (wifiStarted) webServer.handleClient();

  // Connect when device found
  if (doConnect) {
    doConnect = false;
    doScan = false;
    totalNotifications = 0;
    if (connectToServer()) {
      ts(); Serial.println("[BLE] Connection established!");
    } else {
      Serial.println("[BLE] Connection failed, will rescan...");
      doScan = true;
    }
    // Refresh now -- connectToServer() blocks for several seconds
    now = millis();
    lastNotifyMs = now;  // Reset timeout baseline
    pollMs = now;        // Reset poll baseline
  }

  // Rescan if disconnected (unless user requested disconnect)
  if (doScan && !bleConnected && !bleHoldOff && (now - lastScanMs >= 10000)) {
    lastScanMs = now;
    ts(); Serial.println("[SCAN] Scanning...");
    pScan->start(5000, false, true);
  }

  // Refresh now after any blocking operations
  now = millis();

  // Only timeout if truly dead (120s no notifications at all)
  // Use fresh millis() not stale 'now' to avoid false triggers after blocking calls
  if (bleConnected && lastNotifyMs > 0 && (millis() - lastNotifyMs > 120000)) {
    ts(); Serial.println("[BLE] Notification timeout (120s), disconnecting");
    NimBLEClient* pClient = NimBLEDevice::getClientByPeerAddress(
        advDevice->getAddress());
    if (pClient) pClient->disconnect();
  }

  // Print report once when first valid data arrives
  static bool firstReportPrinted = false;
  if (bms.valid && !firstReportPrinted) {
    firstReportPrinted = true;
    ts(); Serial.println("[BLE] Live data streaming");
    printFullReport();
  }

  // Auto-request settings if not received: retry up to 3 times at 10s intervals
  static uint8_t settingsRetryCount = 0;
  static unsigned long settingsRetryMs = 0;
  if (bms.valid && !bms.settingsValid && settingsRetryCount < 3 &&
      (millis() - settingsRetryMs > 10000)) {
    settingsRetryCount++;
    settingsRetryMs = millis();
    ts(); Serial.print("[BLE] Settings retry ");
    Serial.print(settingsRetryCount);
    Serial.println("/3 -- flushing buffer, sending 0x96...");
    flushFrameBuffer();
    writeRegister(0x96, 0x00000000, 0x00);
  }

  // CAN: publish BMS data every second, check for incoming commands
  canReceive();
  if (now - canPublishMs >= CAN_PUBLISH_MS) {
    canPublishMs = now;
    canPublishBMS();
  }

  // Status line
  if (now - statusMs >= STATUS_INTERVAL) {
    statusMs = now;
    printStatusLine();
  }

  // Track loop rate for system health
  loopCount++;
  if (now - loopRateMs >= 1000) {
    loopsPerSec = loopCount;
    loopCount = 0;
    loopRateMs = now;
  }

  delay(10);
}
