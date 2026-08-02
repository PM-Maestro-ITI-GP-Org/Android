// GENERATED from assistant-core/data/dtc_seed.sql — do not edit by hand.
#pragma once
namespace seed {
inline const char* kDtcSeedSql = R"SQL(
-- ---------------------------------------------------------------------------
-- Fault knowledge base.
--
-- One row per fault code the assistant can explain. `base_severity` is the
-- starting point; the rules engine may escalate it using live freeze-frame
-- data (e.g. an over-temperature code becomes StopNow above a threshold).
--
-- severity ints match assistant::Severity:
--   0 Info | 1 Advisory | 2 Soon | 3 Urgent | 4 StopNow
--
-- Codes prefixed PRED_ are outputs of the predictive-maintenance model rather
-- than active OBD-II DTCs; they flow through the same pipeline.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS faults (
    code            TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    system          TEXT NOT NULL,
    explanation     TEXT NOT NULL,
    base_severity   INTEGER NOT NULL,
    drive_affecting INTEGER NOT NULL,   -- 0/1
    base_action     TEXT NOT NULL
);

-- Active OBD-II diagnostic trouble codes ------------------------------------

INSERT OR REPLACE INTO faults VALUES
('P0217', 'Engine coolant over-temperature', 'Cooling',
 'Your engine is running hotter than it should. This usually means low coolant, a cooling-system leak, or a failing water pump or thermostat. Continuing to drive while it overheats can permanently damage the engine.',
 3, 1,
 'Reduce load and find a safe place to stop soon. If the temperature gauge is in the red, pull over and switch off the engine.');

INSERT OR REPLACE INTO faults VALUES
('P0524', 'Engine oil pressure too low', 'Lubrication',
 'Oil pressure has dropped below the safe range. Without proper oil pressure the engine can seize within minutes. This is one of the most serious warnings a car can give.',
 4, 1,
 'Pull over safely and switch off the engine as soon as it is safe. Do not keep driving.');

INSERT OR REPLACE INTO faults VALUES
('C1201', 'Traction control / ESC fault', 'Stability',
 'The electronic stability and traction control system has faulted and is currently disabled. The car still drives and brakes normally, but it will not automatically help you if a wheel loses grip.',
 2, 1,
 'Drive gently, especially in the wet, and have the system checked in the next day or two.');

INSERT OR REPLACE INTO faults VALUES
('C0035', 'Left front wheel speed sensor', 'Brakes',
 'A wheel-speed sensor has failed. This can disable ABS and stability control because the car can no longer measure how fast that wheel is turning. Normal braking still works.',
 2, 1,
 'Avoid hard braking on slippery surfaces and book a service soon.');

INSERT OR REPLACE INTO faults VALUES
('P0300', 'Engine misfire detected', 'Engine',
 'One or more cylinders are misfiring. You may feel the engine shaking or losing power. Prolonged misfiring can overheat and destroy the catalytic converter.',
 3, 1,
 'Ease off the accelerator and avoid high revs. Get it looked at today, especially if the warning light is flashing.');

INSERT OR REPLACE INTO faults VALUES
('P0420', 'Catalytic converter efficiency low', 'Emissions',
 'The catalytic converter is not cleaning the exhaust as well as it should. The car is safe to drive, but it will not pass an emissions test and fuel economy may suffer.',
 1, 0,
 'No urgent action. Have it diagnosed at your next service.');

INSERT OR REPLACE INTO faults VALUES
('P0562', 'System voltage low', 'Electrical',
 'The electrical system voltage is low, which usually points to a failing alternator or battery. If the alternator has failed, the car may lose electrical power and stall once the battery drains.',
 3, 1,
 'Switch off non-essential electrics (AC, heated seats) and head to a service station. If lights dim or the battery light is on, stop soon.');

INSERT OR REPLACE INTO faults VALUES
('P0128', 'Coolant temp below regulating temperature', 'Cooling',
 'The engine is taking too long to warm up, usually a stuck-open thermostat. Not dangerous, but it hurts fuel economy and cabin heating.',
 1, 0,
 'No urgent action. Mention it at your next service.');

INSERT OR REPLACE INTO faults VALUES
('U0100', 'Lost communication with ECM/PCM', 'Network',
 'A control module has stopped talking to the engine computer over the internal network. Behaviour is unpredictable and warning lights may be inaccurate.',
 3, 1,
 'Treat other warnings with caution and have the vehicle diagnosed today.');

INSERT OR REPLACE INTO faults VALUES
('B1000', 'Airbag / restraint system fault', 'Safety',
 'The airbag system has a fault and may not deploy in a crash. The car drives normally but a key safety system is compromised.',
 2, 0,
 'Drive with extra care and have the restraint system checked as soon as possible.');

-- Predictive-maintenance model outputs --------------------------------------

INSERT OR REPLACE INTO faults VALUES
('PRED_BRAKE_WEAR', 'Brake pads wearing thin', 'Brakes',
 'The maintenance system predicts the brake pads are close to their wear limit based on recent driving. Braking is fine right now, but the pads will need replacing soon.',
 1, 0,
 'Book a brake inspection within the next couple of weeks.');

INSERT OR REPLACE INTO faults VALUES
('PRED_BATTERY_HEALTH', 'Battery health declining', 'Electrical',
 'The battery is showing signs of ageing and may struggle to start the car in cold weather. It has not failed yet.',
 1, 0,
 'Consider replacing the battery before winter or long trips.');

INSERT OR REPLACE INTO faults VALUES
('PRED_COOLANT_TREND', 'Coolant temperature trending high', 'Cooling',
 'Over recent trips the engine has been running slightly hotter than usual. Nothing is wrong yet, but this is often an early sign of a cooling-system issue.',
 2, 0,
 'Have the cooling system checked soon, before it becomes an active fault.');

INSERT OR REPLACE INTO faults VALUES
('PRED_TIRE_PRESSURE', 'Slow tyre pressure loss detected', 'Tyres',
 'One tyre is losing pressure slowly over time, which can point to a small puncture or a failing valve. It is not flat, but it is trending down.',
 2, 1,
 'Check and top up the tyre soon, and have it inspected for a slow puncture.');
)SQL";
}
