# DPS Alarm Fix + Missed Alarm Recovery

## Problem 1: DPS Issue
If alarm was set for 16:45 and phone restarted at 16:44 while locked, the alarm wouldn't fire at 16:45.

## Problem 2: Missed Alarm Issue  
If alarm was set for 16:55 and phone was off from 16:44 to 17:00, the alarm wouldn't fire when device turned on.

## Solution Added

### 1. DPS Fix (Original Request)
Modified `DirectBootRestoreReceiver` to reschedule all future alarms during locked boot, not just restore actively ringing alarms.

### 2. Missed Alarm Recovery (New Feature)
Added `checkAndFireMissedAlarms()` function to `BootReceiver` that:
- Detects alarms missed in the last 30 minutes when device was off
- Fires missed alarms immediately when device boots up
- Only fires alarms missed by 1-30 minutes (to avoid very old alarms)

## Test Steps

### DPS Fix Test:
1. Set an alarm for 2 minutes from now (e.g., if current time is 16:43, set for 16:45)
2. Restart the phone at 16:44 (before the alarm time)
3. Keep phone locked (don't unlock)
4. Wait until 16:45
5. **Alarm should fire** 

### Missed Alarm Recovery Test:
1. Set an alarm for 16:55
2. Turn phone completely OFF at 16:44
3. Turn phone ON at 17:00
4. **Alarm should fire immediately** when device boots 

### Edge Case Test (Same Time Boot):
1. Set an alarm for 16:55
2. Turn phone completely OFF before 16:55
3. Turn phone ON exactly at 16:55
4. **Alarm should fire immediately** when device boots (now handled) 

## Key Changes

### DirectBootRestoreReceiver:
- Added `rescheduleAllAlarmsInLockedMode()` function
- Reads all enabled alarms from Device Protected Storage during locked boot
- Schedules alarms that will fire within next 24 hours
- Added `checkAndFireMissedAlarms()` function
- Detects alarms missed in last 30 minutes
- Fires missed alarms immediately on boot

### BootReceiver:
- No changes (missed alarm logic moved to DirectBootRestoreReceiver for immediate execution)

### AlarmReceiver:
- Added handler for `MISSED_ALARM_IMMEDIATE` action
- Processes missed alarms as normal alarm triggers

## Expected Logs

### DPS Fix:
```
=== DPS LOCKED BOOT RESCHEDULE START ===
Found X alarms in DPS storage
DPS Alarm: ID=Y, Enabled=true, Time=HH:MM
 SCHEDULED DPS Alarm ID Y for HH:MM (in Z minutes)
=== DPS LOCKED BOOT RESCHEDULE COMPLETE: X alarms scheduled ===
```

### Missed Alarm Recovery:
```
=== MISSED ALARM CHECK START ===
🔔 MISSED ALARM: ID=Y, Time=HH:MM, Missed by=Z minutes, Next in=W minutes
🚨 FIRING MISSED ALARM ID=Y immediately
✅ Missed alarm ID=Y triggered immediately
=== MISSED ALARM CHECK COMPLETE: Fired X missed alarms ===
```

**Note**: Smart dual ringing prevention is now implemented:
```
🚫 DUAL PREVENTION: Alarm ID=123 was scheduled for future and next in 3 minutes, preventing dual ringing
✅ MISSED ALARM ALLOWED: ID=456 was scheduled for future but next in 15 minutes (>5), allowing missed alarm
⏭️ NOT MISSED: ID=789, Not in missed window (missed by=X minutes)
⏭️ SKIPPING MISSED ALARM: ID=101, Missed by=X minutes but next alarm is in Y minutes (prevents dual ringing)
```

**Logic**: Only prevents dual ringing when alarm was scheduled for near future (≤5 minutes). Allows missed alarms if next occurrence is far enough away.

**Dual Audio Fix**: Missed alarms now use single audio source:
- Primary: AlarmForegroundService (handles all audio)
- Backup: AlarmReceiver with `skip_audio=true` flag (no duplicate audio)
- Result: Only one ringtone plays (no default + selected ringtone)

## Verification
-  DPS alarms fire at correct time even if phone remains locked
-  Missed alarms fire immediately when device turns on
-  Existing reboot ring functionality preserved
-  No duplicate alarms scheduled
-  Only alarms missed by 1-30 minutes fire (avoiding very old alarms)
