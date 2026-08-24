-- ---------------------------------------------------------------------------
-- Fault knowledge base.
--
-- One row per fault code the assistant can explain. `base_severity` is the
-- starting point; the rules engine may escalate it using live freeze-frame
-- data, and may only ever raise it.
--
-- severity ints match assistant::Severity:
--   0 Info | 1 Advisory | 2 Soon | 3 Urgent | 4 StopNow
--
-- WHY THIS FILE IS THREE ROWS LONG
--
-- It used to hold ten OBD-II codes: coolant over-temperature, oil pressure,
-- misfire, catalytic converter, plus predicted brake wear and tyre pressure.
-- None of them can happen here. This vehicle is a 48 V 450 W BLDC motor on a
-- bench rig -- no coolant, no oil, no cylinders, no exhaust, and no tyre or
-- brake sensor of any kind (motorservice/README.md: "Only the motor comes off
-- this link"). A catalogue of faults the car cannot have is not a smaller
-- problem than an empty one: it invites the driver to ask about a check engine
-- light and get a confident answer about an engine that does not exist.
--
-- What this vehicle actually reports is what the cluster prints: E-21, E-31 and
-- E-01, derived from the AI board's fault class. Those are the codes here.
--
-- ON SEVERITY, WHICH IS NOT REALLY THIS TABLE'S TO GIVE
--
-- The real severity arrives live from the diagnostics unit and is passed
-- straight through by MotorVoice with no second opinion (docs/09 2.3). These
-- base values are the floor for the core's own explanation path only, for a
-- code named with no live signal behind it. They are deliberately conservative
-- and are not a judgement about how bad any particular fault is.
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

-- Cluster fault codes -------------------------------------------------------
-- The families are the meaning of the number: 2x electrical, 3x mechanical,
-- and E-01 for a fault raised but placed in neither. Mirrors Main.qml's
-- errorFault in the qt-cluster repo; if that mapping changes, this changes.

INSERT OR REPLACE INTO faults VALUES
('E-21', 'Electrical fault on the motor', 'Motor',
 'The diagnostics unit has classified the motor fault as electrical. The twenty-series codes are the electrical family: the windings, a phase, the drive electronics or the bus supply.',
 2, 1,
 'Have the motor and its drive electronics checked before running it much longer.');

INSERT OR REPLACE INTO faults VALUES
('E-31', 'Mechanical fault on the motor', 'Motor',
 'The diagnostics unit has classified the motor fault as mechanical. The thirty-series codes are the mechanical family: a bearing, the rotor, a shaft, imbalance or misalignment showing up as vibration.',
 2, 1,
 'Have the motor inspected mechanically before running it much longer.');

INSERT OR REPLACE INTO faults VALUES
('E-01', 'Unclassified motor fault', 'Motor',
 'A fault is raised, but the classifier could not place it in either the electrical or the mechanical family. It is a real fault; it simply has no name yet.',
 1, 0,
 'Worth reporting with whatever the motor was doing at the time, so the classification can be improved.');
