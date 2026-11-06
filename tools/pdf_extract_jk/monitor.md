Communication protocol betweenmonitoringplatformand BMS
Version information
version The date of describe The author
V20191124 The first draft
V20200325 Update some description information, change 0xA10 to 0xD2, dedicated charger switch refers to
make
V20200325 Determine the transmitted data baud rate of 115200
V20200329 Update the optimization instruction table and redefine the data
identifier
V20200329 Added instruction to read all data at once. V20200427 Write ID write factory date for description
V20200429 Add 0xB7 address software version number
V20200429 The address 0x8B 0x8C is described in detail
V20200508 Modify 0x84 address from 0.1A to 0.01A
V20200512 The 0x81 address name is used to redefine the temperature in
the battery box
V20200512 The 0 xa0 The 0xA1 name is redefined
V20200512 Alarm bit added for address 0x8B
V20200526 Add the restart system ID 0XBB
V20200615 Adding 0xB8 indicates a version change to V2.0
V20200713 Add 0xBC flag to restore factory initialization version change
V2.1
Add 309 failure information
V20200825 Add 0 0 XBF xbe
V2.4 20201204 Add 0xC0 to redefine current field data echo
V2.5 20201217 Add necessary field reporting description echo

directory
1 An overview of the.......................................................................................................................................................................32 Citation standard 1......................................................................................................................................................................33 Network topology.........................................................................................................................................................................34 Specifications content..................................................................................................................................................................34.1 Communication rules....................................................................................................................................................34.2 The frame format...........................................................................................................................................................34.2.1 The start of the frame....................................................................................................................................................34.2.2 The length of the domain..............................................................................................................................................34.2.3 Terminal no..................................................................................................................................................................34.2.5 Command word specification........................................................................................................................................34.2.6 Frame source description..............................................................................................................................................44.2.7 Transport type...............................................................................................................................................................44.2.10 End code domain..........................................................................................................................................................44.2.11 Check code domain.......................................................................................................................................................44.3 Communication data format..........................................................................................................................................4

1 is described
This protocol defines the communication protocol between the monitoring platform and the battery terminal, as well as themessageformat, transmission mode and communication mode, etc. 2. Citation Criteria
Communication using 2G GPRS in TCP transmission, 4G in GAT1, Socket interface mode,RS232TTL serial port, content customcommunication format, baud rate 115200. 3. Network topology
This protocol is BMS,GPS, Bluetooth,PC PC and endpoint-to-point or bus mode. 4. Content of Statute
4.1 Communication Rules
During the communication process, the device has active reporting frame and passive response frame. Please refer to the communicationdataformat for details. The interval of each packet number is at least 100MS, and the longest reply packet is not more than 5S. Timedbroadcast.Ifdormant, the control terminal willsend the activation information, activate the BMS, and then communicate. 4.2 The frame format
A frame is the basic unit of information transmission. These include the start character, length, command word, transmissiontype,information field, end identifier, and checksum. The specific format is shown in Table 1. The low byte is to the right andthehighbyteistothe left of the data unit if it is not mandatory.Sending is sent high first and then low. Table 1 Frame Format
The serial
number
The frame unit The
length of
the
note
1 STX 2 Start frame: 0x4E(78"N") 0x57(87"W")
2 LENGTH 2 The length of the frame
3 BMS terminal number 4 4 bytes ID
4 Command word 1 Refer to the command word description, 5 The frame source 1 BMS,1. Bluetooth,2.GPS,3. PC PC
6 Transport type 1 1. Response frame 2.BMS active upload
7 Frame information unit N The information domain BMS sets the data identifier
code
8 Record number 4 High 1 byte is random code meaningless (reserved for
encryption), low 3 bytes is
Record the serial number
9 End of identity 1 0X68
10 The checksum 4 Accumulated checksum (high 2 bytesfor CRC not yet
enabled to fill 0, low 2
Byte for cumulative check)
4.2.1 The start of the frame
Two bytes. The first byte is 0x4E, the second byte is 0x57. 4.2.2 The length of the domain
L: Two bytes, all data bytes except the first two characters including the checksum and the length field itself. 4.2.3 BMS Terminal No. ()
Total four bytes: FF FF FF FF FF FF FF FF FF FF FF Maximum 8 bits management standby number, low 24 bits is the terminal number(The highest byte is the default 00, the lowest three bytes is the dimension one ID number)
4.2.4 Command word specification

A byte that defines the transmission function of this frame. The
command
code
Ordered item note
0x01 Activation instruction, When the BMS is dormant, the controller to communicate withtheBMS will issue an activation command.After receiving the reply, proceed to any other action. 0X02 Written instructions
Configure the BMS parameter instruction,

0X03 Read the instructions
Read the BMS identifier data, 0x05 Pair code instruction To modify the parameters, the first commandiscorrect before the change
0x06 Read all the data Read all the data in the ID code table at onetime4.2.5 Frame source description
1 byte. Relative to both receiver and receiver, 0.BMS,1. Bluetooth,2.GPS,3. PC PC
4.2.6 Transport type
1 byte: 0 for request frame, 1 for reply frame. 2 is for active reporting. As long as 5-Bluetooth,2-GPS,3-PC PC and 4-BMS are initiated first, the reply will use 1. 4.2.7 record number
High 1 byte is the random code, low 3 bytes is the recording code
4.2.8 End code domain
One byte 0x68
4.2.9 Check code domain
The second byte CRC16 is not used for the time being. The checksum identifies the sum of all data from start to end. 4.3 Communication data format
Example: GPS reads (whole, single) data reference
The
serial
numb
er
The frame
unit
The length
of the byte
1 STX 2
Start frame: 0x4E(78"N") 0x57(87"W")
2 LENGTH 2 The length of the frame
3 BMS
terminal
number
4 4 bytes ID
4 Command
word
1 Refer to the command to write the instructions
5 The frame
source
1 Data box,1. Bluetooth,2.GPS,3
6 Transport
type
1
1. Response frame 2. Active upload of data box
7 Data
identification
code
1 Read individual data references (Table 5.1);Read all data andfill in0x008 Record
number
4 The high 1 byte is the random code meaningless (reserved for encryption), thelow3bytesisthe record sequence number
9 End of 1 0x68

identity
10 The
checksum
4
BMS reply
The
serial
numb
er
The frame
unit
The
length
of the
1
STX 2
Start frame: 0x4E(78"N") 0x57(87"W")

2 LENGTH
2
3 Terminal
no. 4
4 Command
word
1
5 The frame
source
1
Data box,1. Bluetooth,2.GPS,3
6 Transport
type
1
1. Response frame 2. Active upload of data
box
7 Identifier +
data
1+N
Identifier + data
8 Record number 4 The high 1 byte is the random code meaningless (reserved for encryption), thelow3bytesisthe record sequence number
9 End of
identity
1
0X68
10 The
checksum
4
Example: GPS write data reference
The
serial
numb
er
The frame
unit
The length
of the byte
1 STX 2
Start frame: 0x4E(78"N") 0x57(87"W")
2 LENGTH 2 The length of the frame
3 BMS
terminal
number
4 4 bytes ID
4 Command
word
1 Refer to the command to write the instructions
5 The frame
source
1 Data box,1. Bluetooth,2.GPS,3
6 Transport
type
1
1. Response frame 2. Active upload of data box
7 Identifier +
data
1+N Identifier + data
8 Record
number
4 The high 1 byte is the random code meaningless (reserved for encryption), thelow3bytesisthe record sequence number
9 End of
identity
1 0x68

10 The
checksum
4
BMS reply
The
serial
numb
er
The frame
unit
The
length
of the
1
STX 2
Start frame: 0x4E(78"N") 0x57(87"W")
2 LENGTH
2
3 Terminal
no. 4

4 Command
word
1
5 The frame
source
1
BMS,1. Bluetooth,2.GPS,3. PC PC
6 Transport
type
1
1. Response frame 2.BMS active upload
7 Identificati
on code
1 Write a single data reference (Table 5.1);
8 Record number 4 The high 1 byte is the random code meaningless (reserved for encryption), thelow3bytesisthe record sequence number
9 End of
identity
1
0X68
10 The
checksum
4
Note :(The background data identification code is 0x00 when reading all data)
5.1 The BMS sets the data identification code
Use
instru
ction Data
identifi cation
code The name of the byte type R 0x79 Single battery voltage 3 *n HEX
T h e f irst b y t e is t h e b a t t e r y n u m b e r, a n d t h e s e c o ndist hev o l t a g e v a l u e m V. W h e n r e a d i n g a l l t h e d a t a , 0 x 7 9is
f o l l owe d b y a b y t e l e n g t h d a t a , a n d t h e n e v e r y t h r e e byt esinag r o u p r e p r e s e n ts t h e e l e c tri c i t y
Pool voltage. R 0x80 Read power tube
temperature 2 HEX
0 -- 140 (-40 to 100 ° C) parts over 100 are negative temperatures, suchas10, 1isnegative 1 ° C (100
Benchmark)
R 0x81 Read the temperature
in the battery box 2 HEX
0 -- 140 (-40 to 100 ℃) The part over 100 is negative temperature, as above(100reference)
R 0x82 Read battery
temperature 2 HEX
0 -- 140 (-40 to 100 ℃) The part over 100 is negative temperature, as above(100reference)
R 0 X 83 Total battery voltage 2 HEX 0. 01 V 3500 * 0 . 01 = 35 . 0 0 v The minimumunit is10mVR 0 X 84 The current data 2 Hex 10000 (10000-11000) * 0.01 = 10.00), a (discharge)
(10000-9500) *0.01= 5.00A (charging) P r e c i s i o n 1 0 mAunit:0 1 A
Note: C 0:0 x 01 redefines the 0 x 84 current data in 10 mA, with the highest bit being0"1" means "discharge" and "1" means charge
If the discharge is 20 A, the transmitted data is 2000 (0x 07 D0)
If the charge is 20 A, the transmission data is 34768 (0x 87 D0)
R 0 X 85 Battery residual capacity
1 HEX SOC, 0-100%,

R 0 X 86 Number of battery
temperature sensors 1 Hex Two battery temperature sensors, R 0 X 87 Cycle times of battery use 2 Hex R 0 X 89 Total capacity of
battery cycle 4 HEX When Ann R 0x8a Total number of battery
strings 2 HEX

Bit 0: Low capacity alarm
Bit 1: MOS tube overtemperature alarm
Position 2: Charge over voltage
alarm
Position 3: Discharge undervoltage alarm
Position 4: Battery
overtemperature alarm
Position 5: Charge overcurrent alarm
Position 6: discharge over current alarm
Position 7: core differential
pressure alarm
1 call
the police 1 call
the police 1 call
the police 1 call
the police 1 call
the police 1 call
the police 1 call
the police 1 call
the police
0 normal. Warning only
0 normal, alarm0 normal, alarm0 normal, alarm0 normal, alarm0 normal, alarm0 normal, alarm0 normal, alarmR 0 X 8 b Battery Warning
Message 2 hex
8 bit: overtemperature alarm in the battery box Position 9: Battery low temperature
alarm
Position 10: Unit overvoltage
alarm 1
Position 11: Unit undervoltage
alarm 1
1 call
the police 1 alarm
alarm
alarm
0 normal, alarm0 normal, alarm0 normal, alarm0 normal, alarmBit 12:309_A protection Bit 13:309_B protection
1 call
the police 1 call
the police
0 normal, alarm0 normal, alarm14 bits: reserved
Position 15: Reserved
Ex. : 0x0001: Represents the low volume alarm value 0x0001 ----> Low Capacity
Alarm
R 0 X 8 c Battery status
information
2
0-bit charging MOS tube state 1 on 0 off T h i s i s f o r u p l o a d i ngpr ompts1 bit discharge MOS tube state 1 on 0 off T h i s i s f o r u p l o a d i ngp r o m p t s . 2 bit equalization switching state 1 on,0 off this is for the uploadprompt
The 3-bit battery is down 1 normal.0 dropped, this is the upload prompt, 4-15 bits: reserved
example: 00 01: The charging MOS tube is turned on RW 0x8e Total voltage overvoltage protection
2 HEX 1000-15000. (10mV)Minimumunit:10mV
RW 0x8f Total voltage under voltage protection
2 HEX 1000-15000. (10mV)Minimumunit:10mV
RW 0 X 90 Monomer overvoltage protection voltage 2 Hex 1000 -- 4500 MV, RW 0x91 Overvoltage recovery
voltage of monomer 2 HEX 1000 - 4500 MV
RW 0x92 Monomer overvoltage protection delay
2 HEX 1 to 60 seconds

RW 0x93 Unit undervoltage protection voltage 2 Hex 1000 -- 4500 MV
RW 0x94 Monomer undervoltage
recovery voltage 2 HEX 1000 - 4500 MV
RW 0x95 Monomer undervoltage protection delay
2 HEX 1-60 seconds RW 0x96 Core differential pressure protection value 2 Hex
0 - 1000 MV
RW 0x97 Discharge overcurrent
protection value 2 Hex 1 - 1000A
RW 0x98 Overcurrent delay of
discharge 2 Hex 1-60 seconds RW 0x99 Charge overcurrent
protection value 2 Hex 1 - 1000 A
RW 0 x9a Charge overcurrent
delay
2 HEX 1 - 60 S
RW 0x9b Equalizing starting
voltage 2 Hex 2000 - 4500 MV

RW 0x9c Equalize the opening
differential pressure 2 hex 10-1000. The MV, RW 0x9d Active equalizing
switch
1 Hex 0 off or 1 on RW 0x9e Power tube temperature protection value 2 Hex 0-100 ℃
RW 0x9f Power tube temperature
recovery value 2 Hex 0-100 ℃
RW 0 xa 0 Temperature protection
value in the battery box 2 Hex 40 -- 100 ℃, RW 0 xa 1 Temperature recovery
value in the battery box 2 Hex 40-100 ℃
RW 0 xa 2 Temperature difference protection value of
battery
2 Hex 5-20 ℃, RW 0 xa 3 Battery charging high
temperature protection
value 2 HEX 0-100 ℃
RW 0 xa 4 Battery discharge high
temperature protection
value 2 HEX 0-100 ℃
RW 0 xa 5 Charge low temperature protection value 2 Hex -45 ℃ /+ 25 ℃ (no reference - signed data)
RW 0 xa 6 Charge low temperature protection recovery value 2 Hex -45 ℃ /+ 25 ℃ (no reference - signed data)
RW 0 xa 7 Discharge low
temperature protection
value 2 Hex -45 ℃ /+ 25 ℃ (no reference - signed data)
RW 0 xa 8 Discharge cryogenic protection recovery value 2 Hex -45℃/+25℃(noreference-signeddata)
RW 0 xa 9 Battery string count
Settings 1 Hex 3-32
RW 0 xaa Battery capacity setting 4 Hex AH (Ann)
RW 0 xab Charge MOS tube switch 1 Hex 0 to shut down 1 open RW 0 xac Discharge MOS tube
switch
1 Hex 0 to shut down 1 open RW 0 xad Current calibration 2 Hex 100 MA- 20000 MA
RW 0 xae Guard plate address 1 Hex This one is reserved, used for cascading, RW 0 xaf The battery type 1 HEX 0: lithium iron phosphate, 1: ternary, 2: lithium titanate RW 0xb0 Dormancy waiting time 2 Hex Seconds of data, for reference, RW 0xb1 Low capacity alarm
value 1 Hex 0-80%
RW 0xb2 Change parameter password
10 hex J u s t f o r r e f e r e n c e , f i x a p a s s w o r d , RW 0xb3 Special charger switch 1 Hex 0 off or 1 on

RW 0 Xb 4 The device ID code 8 characte
r Example60300001(60-nominalvoltagegrade:definedaccordingtothevoltagegrade,suchas60forthe60Vseries48forthe48Vseries;3 - Material system: according to the battery material systemdefinition, suchaslithium iron code 1, manganese acid code 2, ternary code 3; 00001- Productionserial number: according to the manufacturer's productionof themonthof the manufacturer's production of the model group N, theserialnumber is N (example: type Group 1 of, then N is 00001)) character RW 0 Xb 5 Manufacture date 4 characte
r Example2004-Yearofproduction:takethelasttwodigitsaccordingtotheactualyearofproduction; The batteryproduced in 2020, year code 20; P r o d u c t i o n m o n t h : J a n u a r y - - D e c e m b e r ; c har act er

RW 0xb6 System working time 4 HEX Factory clearance, unit points R 0xb7 Software version
number 15 characte
r NW_ 1 _ 0 _ 0 _ 200428
RW 0xb8 Whether to start current calibration
1 HEX 1: Start calibration 0: Close calibration RW 0xb9 Actual battery capacity 4 HEX AH (Ann)
RW 0 x BA Name of Manufacturer
ID
24 characte
r
Column: "BT 3072020120000200521001" * Product name: battery BT
* Material system: lithium iron code 1; Manganese acid code 2; Ternarycode3* Voltage grade: 48V series 48; 60 for the 60 V series and 72 for the Vseries* Capacity grade: 20 Ah specification with 20
* Cycle life: 04 mark for 400 cycles and 12 mark for 1200 cycles * Manufacturer's code: English code of low-speed car battery manufacturer. IftheEnglish code of the manufacturer is less than four digits, the character 0is usedtofillin the digits * Year of production: take the last two digits according to the actual year of
production; List2019batteryyearcode"19" * Production months: January to December * Production date: 01-31
* Production serial number: according to the production date of the manufacturer, theserial number is N(for example: the first group of a certain model, it is 001)
W 0 x BB Restart the system 1 HEX 1: Restart the system W 0 x BC factory data reset 1 He X 1: Restore (only restore the factory base parameters)
W 0 x BD Remote Upgrade
Identification
1 He X 1 Start (wait for identification reply when sending the file)
W 0 x BE The cell turns off GPS with low voltage 2 Hex Unit: MV (turn off the power supply to GPS when low voltage is detected)
W 0 x BF Cell low voltage
recovery GPS
2 Hex U n i t : M V ( t u r n o n t h e p o w e r t o G P S w h e n t h e r ecoveryv o l t a g e v a l u e i s d e t e c t e d )
R 0 x C 0 Agreement version
number 1 Hex D e f a u l t : 0 x 0 0
0 x 01: Redefine the 0 x 84 current data as 10 mA, with the highest bit being0fordischarge and 1 for charge
If the discharge is 20A, the transmitted data is 2000 (0 x 07 D0)
If the charge is 20A, then the transfer data is 34768 (0 x 87 D0)
【 note 】
1. All fields marked with R or RW in 0x79 ~ 0XB9 should be reported. For those not reported in the shipped old version, trytoupgrade.If it is not convenient to upgrade, please contact our technicalsupport at 13755639263/13480924112
2. Name 0xBA manufacturer ID. This field is mainly used for the electric changing cabinet. If there is a demand for theelectricchanging cabinet, this field must be added.