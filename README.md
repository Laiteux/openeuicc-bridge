# openeuicc-bridge

An Android ContentProvider that exposes [OpenEUICC/EasyEUICC](https://gitea.angry.im/PeterCxy/OpenEUICC) LPA functionality via ADB, enabling programmatic eSIM profile management.

## Build & Install

```bash
./build+install.sh
```

This script:
1. Compiles [`LpaProvider.java`](https://github.com/Laiteux/openeuicc-bridge/blob/main/src/im/angry/openeuicc/bridge/LpaProvider.java) to `.class` files
2. Converts to `.dex` using d8, then disassembles to smali
3. Injects smali into the decompiled EasyEUICC APK
4. Rebuilds and signs the APK with [9eSIM Community Key](https://github.com/9esim/9eSIMCommunityKey)
5. Installs via `adb install`

**Requirements:** apktool, adb, java, javac

**Included:** Build tools in `tools/`, Java dependencies in `deps/`

## Content Authority

```
content://lpa/<endpoint>
```

## Endpoints

| Endpoint | Description | Input | Output |
|----------|-------------|-------|--------|
| `cards` | List eSIM cards | — | `slot`, `port`, `eid` |
| `profiles` | Get profiles on a card | `slot`, `port` | `iccid`, `enabled`, `provider`, `nickname` |
| `downloadProfile` | Download a profile | `slot`, `port`, `activationCode`¹, `address`¹, `matchingId`?, `confirmationCode`?, `imei`?, `callbackUrl`? | `iccid`, `enabled`, `provider`, `nickname` |
| `deleteProfile` | Delete a profile | `slot`, `port`, `iccid` | `success` |
| `enableProfile` | Enable a profile | `slot`, `port`, `iccid`, `refresh`?=true | `success` |
| `setProfileNickname` | Set/clear profile nickname | `slot`, `port`, `iccid`, `nickname`?="" | `success` |
| `preferences` | Get all preferences | — | `name`, `enabled` |
| `setPreference` | Set a preference | `name`, `enabled` | `success` |

¹ Provide either `activationCode` OR `address`  
? = optional

## Usage Examples

#### List cards

```bash
adb shell content query --uri 'content://lpa/cards'
```
```
Row: 0 slot=0, port=0, eid=89049032123456789012345678901234
Row: 1 slot=1, port=0, eid=89044012345678901234567890123456
```

#### List profiles

```bash
adb shell content query --uri 'content://lpa/profiles?slot=0&port=0'
```
```
Row: 0 iccid=8901234567890123456, enabled=true, provider=Example Carrier, nickname=Work
Row: 1 iccid=8909876543210987654, enabled=false, provider=Another Carrier, nickname=NULL
```

#### Download profile

With activation code:
```bash
adb shell content query --uri 'content://lpa/downloadProfile?slot=0&port=0&activationCode=LPA:1$smdp.example.com$ABC123'
```

With address and matching ID:
```bash
adb shell content query --uri 'content://lpa/downloadProfile?slot=0&port=0&address=smdp.example.com&matchingId=ABC123'
```

With confirmation code:
```bash
adb shell content query --uri 'content://lpa/downloadProfile?slot=0&port=0&activationCode=LPA:1$smdp.example.com$ABC123&confirmationCode=1234'
```

With callback URL:
```bash
adb shell content query --uri 'content://lpa/downloadProfile?slot=0&port=0&activationCode=LPA:1$smdp.example.com$ABC123&callbackUrl=https://example.com/callback'
```
```
Row: 0 iccid=8901234567890123456, enabled=true, provider=Example Carrier, nickname=NULL
```

#### Delete profile

```bash
adb shell content query --uri 'content://lpa/deleteProfile?slot=0&port=0&iccid=8901234567890123456'
```
```
Row: 0 success=true
```

#### Enable profile

```bash
adb shell content query --uri 'content://lpa/enableProfile?slot=0&port=0&iccid=8901234567890123456'
```
```
Row: 0 success=true
```

#### Set profile nickname

```bash
adb shell content query --uri 'content://lpa/setProfileNickname?slot=0&port=0&iccid=8901234567890123456&nickname=Work'
```

Clear profile nickname:
```bash
adb shell content query --uri 'content://lpa/setProfileNickname?slot=0&port=0&iccid=8901234567890123456'
```
```
Row: 0 success=true
```

#### Get preferences

```bash
adb shell content query --uri 'content://lpa/preferences'
```
```
Row: 0 name=verboseLogging, enabled=false
Row: 1 name=safeguardActiveProfile, enabled=true
Row: 2 name=filterProfileList, enabled=true
Row: 3 name=ignoreTlsCertificate, enabled=true
Row: 4 name=notificationsDownload, enabled=false
Row: 5 name=notificationsDelete, enabled=false
Row: 6 name=notificationsEnableDisable, enabled=false
```

#### Set preference

```bash
adb shell content query --uri 'content://lpa/setPreference?name=ignoreTlsCertificate&enabled=true'
```
```
Row: 0 success=true
```

#### Error example

```bash
adb shell content query --uri 'content://lpa/profiles'
```
```
Row: 0 error=missing_arg_slot
```

### JSON Output

Add the `json` parameter to any endpoint to receive results as JSON in a single `rows` column.

```bash
adb shell content query --uri 'content://lpa/profiles?slot=0&port=0&json'
```

Example outputs:

```json
// cards
[
  {"slot":0,"port":0,"eid":"89049032123456789012345678901234"},
  {"slot":1,"port":0,"eid":"89044012345678901234567890123456"}
]

// profiles
[
  {"iccid":"8901234567890123456","enabled":true,"provider":"Example Carrier","nickname":"Work"},
  {"iccid":"8909876543210987654","enabled":false,"provider":"Another Carrier","nickname":null}
]

// preferences
[
  {"name":"verboseLogging","enabled":false},
  {"name":"safeguardActiveProfile","enabled":true},
  {"name":"filterProfileList","enabled":true},
  {"name":"ignoreTlsCertificate","enabled":true},
  {"name":"notificationsDownload","enabled":false},
  {"name":"notificationsDelete","enabled":false},
  {"name":"notificationsEnableDisable","enabled":false}
]

// success response (enableProfile, deleteProfile, setProfileNickname, setPreference)
[
  {"success":true}
]

// error response
[
  {"error":"missing_arg_slot"}
]
```

## Errors

Errors are returned in an `error` column:
- `no_endpoint` - No endpoint specified
- `unknown_endpoint` - Endpoint not found
- `missing_arg_<name>` - Required argument missing
- `unknown_preference_name` - Invalid preference name
- `safeguard_active_profile` - Operation blocked by safeguard

## Preferences

| Name | Description |
|------|-------------|
| `verboseLogging` | Enable verbose logging |
| `forceUseTelephonyManager` | Force use TelephonyManager API (privileged only) |
| `safeguardActiveProfile` | Prevent operations on active profile |
| `filterProfileList` | Filter to show only operational profiles |
| `ignoreTlsCertificate` | Ignore TLS certificate errors |
| `notificationsDownload` | Process download notifications |
| `notificationsDelete` | Process delete notifications |
| `notificationsEnableDisable` | Process enable/disable notifications |

## Download profile callback URL

When `callbackUrl` is provided for `downloadProfile`, progress updates are POSTed as JSON:

```json
{
  "timestamp": 1770108790,
  "state": "Authenticating",
  "progress": 40,
  "address": "smdp.example.com",
  "matchingId": "ABC123",
  "confirmationCode": null,
  "imei": null
}
```
