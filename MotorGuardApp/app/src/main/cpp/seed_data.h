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
--
-- The _ar columns are Egyptian Arabic (not MSA) translations of name/
-- explanation/base_action, selected by DiagnosticsEngine::lookup() when
-- Assistant::current_language_ is ArabicEgypt. Keep them in sync with the
-- English columns by hand -- there is no machine translation in this build,
-- deliberately: action/severity wording is safety-critical, so it is
-- human-written and human-reviewed like the English, never auto-translated
-- at runtime.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS faults (
    code            TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    system          TEXT NOT NULL,
    explanation     TEXT NOT NULL,
    base_severity   INTEGER NOT NULL,
    drive_affecting INTEGER NOT NULL,   -- 0/1
    base_action     TEXT NOT NULL,
    name_ar         TEXT NOT NULL,
    explanation_ar  TEXT NOT NULL,
    base_action_ar  TEXT NOT NULL
);

-- Active OBD-II diagnostic trouble codes ------------------------------------

INSERT OR REPLACE INTO faults VALUES
('P0217', 'Engine coolant over-temperature', 'Cooling',
 'Your engine is running hotter than it should. This usually means low coolant, a cooling-system leak, or a failing water pump or thermostat. Continuing to drive while it overheats can permanently damage the engine.',
 3, 1,
 'Reduce load and find a safe place to stop soon. If the temperature gauge is in the red, pull over and switch off the engine.',
 'حرارة المحرك زايدة عن الحد',
 'المحرك بتاعك سخن أكتر من اللازم. غالبًا السبب مياه التبريد ناقصة، أو في تسريب في نظام التبريد، أو طرمبة المية أو الترموستات بايظة. لو استمريت تسوق والعربية سخنة كده، ممكن تتلف المحرك بشكل دائم.',
 'قلل من سرعتك ودوّر على مكان آمن توقف فيه بسرعة. لو مؤشر الحرارة وصل للمنطقة الحمرا، قف على جنب واطفي المحرك فورًا.');

INSERT OR REPLACE INTO faults VALUES
('P0524', 'Engine oil pressure too low', 'Lubrication',
 'Oil pressure has dropped below the safe range. Without proper oil pressure the engine can seize within minutes. This is one of the most serious warnings a car can give.',
 4, 1,
 'Pull over safely and switch off the engine as soon as it is safe. Do not keep driving.',
 'ضغط زيت المحرك واطي جدًا',
 'ضغط الزيت نزل تحت المعدل الآمن. من غير ضغط زيت كويس، المحرك ممكن يقف تمامًا خلال دقايق. ده من أخطر التحذيرات اللي ممكن العربية تديهالك.',
 'قف على جنب بأمان واطفي المحرك أول ما تقدر. متكملش السواقة خالص.');

INSERT OR REPLACE INTO faults VALUES
('C1201', 'Traction control / ESC fault', 'Stability',
 'The electronic stability and traction control system has faulted and is currently disabled. The car still drives and brakes normally, but it will not automatically help you if a wheel loses grip.',
 2, 1,
 'Drive gently, especially in the wet, and have the system checked in the next day or two.',
 'عطل في نظام التحكم بالثبات',
 'نظام التحكم في الثبات والجر حصله عطل ودلوقتي مش شغال. العربية لسه بتقود وبتفرمل عادي، بس مش هتساعدك تلقائيًا لو عجلة زحلقت.',
 'سوق بهدوء، خصوصًا في الشتا أو الطريق المبلول، وودّي العربية تتفحص في أقرب يوم أو يومين.');

INSERT OR REPLACE INTO faults VALUES
('C0035', 'Left front wheel speed sensor', 'Brakes',
 'A wheel-speed sensor has failed. This can disable ABS and stability control because the car can no longer measure how fast that wheel is turning. Normal braking still works.',
 2, 1,
 'Avoid hard braking on slippery surfaces and book a service soon.',
 'حساس سرعة عجلة الشمال الأمامية بايظ',
 'حساس سرعة العجلة بايظ. ده ممكن يعطّل نظام الـ ABS والتحكم في الثبات لأن العربية مبقتش تقدر تقيس سرعة دوران العجلة دي. الفرامل العادية لسه شغالة كويس.',
 'تجنب الفرملة القوية على الأرض الزلقة واحجز موعد صيانة قريب.');

INSERT OR REPLACE INTO faults VALUES
('P0300', 'Engine misfire detected', 'Engine',
 'One or more cylinders are misfiring. You may feel the engine shaking or losing power. Prolonged misfiring can overheat and destroy the catalytic converter.',
 3, 1,
 'Ease off the accelerator and avoid high revs. Get it looked at today, especially if the warning light is flashing.',
 'تفويت اشتعال في المحرك',
 'سلندر واحد أو أكتر بيفوّت الاشتعال. ممكن تحس إن المحرك بيهتز أو بيفقد قوته. الاستمرار في التفويت لفترة طويلة ممكن يسخّن ويتلف الكاتلينيك.',
 'خفف من الدوس على البنزين وابعد عن السرعات العالية. وديها تتشاف النهاردة، خصوصًا لو اللمبة بتلمع مش ثابتة.');

INSERT OR REPLACE INTO faults VALUES
('P0420', 'Catalytic converter efficiency low', 'Emissions',
 'The catalytic converter is not cleaning the exhaust as well as it should. The car is safe to drive, but it will not pass an emissions test and fuel economy may suffer.',
 1, 0,
 'No urgent action. Have it diagnosed at your next service.',
 'كفاءة الكاتلينيك منخفضة',
 'الكاتلينيك مش بينضف العادم زي ما المفروض. العربية آمنة إنك تسوقها، بس مش هتعدي فحص الشكمان وممكن استهلاك البنزين يزيد.',
 'مفيش حاجة مستعجلة. وديها تتفحص في الصيانة الجاية.');

INSERT OR REPLACE INTO faults VALUES
('P0562', 'System voltage low', 'Electrical',
 'The electrical system voltage is low, which usually points to a failing alternator or battery. If the alternator has failed, the car may lose electrical power and stall once the battery drains.',
 3, 1,
 'Switch off non-essential electrics (AC, heated seats) and head to a service station. If lights dim or the battery light is on, stop soon.',
 'جهد النظام الكهربائي منخفض',
 'جهد النظام الكهربائي واطي، وده غالبًا معناه إن الدينامو أو البطارية بايظة. لو الدينامو بايظ، العربية ممكن تفقد الكهربا وتقف لما البطارية تفضى.',
 'قفل الحاجات الكهربائية اللي مش ضرورية (التكييف، تدفئة الكراسي) وروح لأقرب محطة خدمة. لو النور بيخفت أو لمبة البطارية شغالة، قف بسرعة.');

INSERT OR REPLACE INTO faults VALUES
('P0128', 'Coolant temp below regulating temperature', 'Cooling',
 'The engine is taking too long to warm up, usually a stuck-open thermostat. Not dangerous, but it hurts fuel economy and cabin heating.',
 1, 0,
 'No urgent action. Mention it at your next service.',
 'حرارة المياه أقل من درجة التشغيل',
 'المحرك بياخد وقت طويل عشان يسخن، وغالبًا السبب إن الترموستات عالقة مفتوحة. مش خطر، بس بيأثر على استهلاك البنزين والتدفئة جوه العربية.',
 'مفيش حاجة مستعجلة. اذكرها في الصيانة الجاية.');

INSERT OR REPLACE INTO faults VALUES
('U0100', 'Lost communication with ECM/PCM', 'Network',
 'A control module has stopped talking to the engine computer over the internal network. Behaviour is unpredictable and warning lights may be inaccurate.',
 3, 1,
 'Treat other warnings with caution and have the vehicle diagnosed today.',
 'فقدان الاتصال مع كمبيوتر المحرك',
 'وحدة تحكم توقفت عن الاتصال بكمبيوتر المحرك عبر الشبكة الداخلية. تصرف العربية ممكن يبقى غير متوقع، واللمبات ممكن تدي إشارات غلط.',
 'تعامل مع أي تحذير تاني بحذر ووديها تتفحص النهاردة.');

INSERT OR REPLACE INTO faults VALUES
('B1000', 'Airbag / restraint system fault', 'Safety',
 'The airbag system has a fault and may not deploy in a crash. The car drives normally but a key safety system is compromised.',
 2, 0,
 'Drive with extra care and have the restraint system checked as soon as possible.',
 'عطل في نظام الوسائد الهوائية',
 'في عطل في نظام الوسائد الهوائية وممكن متنفتحش لو حصل حادث. العربية بتمشي عادي، بس نظام أمان مهم مش شغال صح.',
 'سوق بحرص زيادة ووديها نظام الحماية يتفحص أول ما تقدر.');

-- Predictive-maintenance model outputs --------------------------------------

INSERT OR REPLACE INTO faults VALUES
('PRED_BRAKE_WEAR', 'Brake pads wearing thin', 'Brakes',
 'The maintenance system predicts the brake pads are close to their wear limit based on recent driving. Braking is fine right now, but the pads will need replacing soon.',
 1, 0,
 'Book a brake inspection within the next couple of weeks.',
 'تيل الفرامل قرب يخلص',
 'نظام الصيانة التنبؤي بيتوقع إن تيل الفرامل قرب على حد الاستهلاك بناءً على سواقتك الأخيرة. الفرملة تمام دلوقتي، بس التيل هيحتاج تغيير قريب.',
 'احجز كشف فرامل خلال الأسبوعين الجايين.');

INSERT OR REPLACE INTO faults VALUES
('PRED_BATTERY_HEALTH', 'Battery health declining', 'Electrical',
 'The battery is showing signs of ageing and may struggle to start the car in cold weather. It has not failed yet.',
 1, 0,
 'Consider replacing the battery before winter or long trips.',
 'حالة البطارية بتضعف',
 'البطارية بادية عليها علامات كبر في السن وممكن تواجه صعوبة في تشغيل العربية في الجو البارد. لسه مبطلتش شغل خالص.',
 'فكر تغيّر البطارية قبل الشتا أو قبل أي رحلة طويلة.');

INSERT OR REPLACE INTO faults VALUES
('PRED_COOLANT_TREND', 'Coolant temperature trending high', 'Cooling',
 'Over recent trips the engine has been running slightly hotter than usual. Nothing is wrong yet, but this is often an early sign of a cooling-system issue.',
 2, 0,
 'Have the cooling system checked soon, before it becomes an active fault.',
 'حرارة المياه بتميل للارتفاع',
 'في الرحلات الأخيرة المحرك بيسخن شوية أكتر من المعتاد. مفيش حاجة غلط دلوقتي، بس ده غالبًا علامة مبكرة على مشكلة في نظام التبريد.',
 'ودّي نظام التبريد يتفحص قريب، قبل ما تبقى مشكلة فعلية.');

INSERT OR REPLACE INTO faults VALUES
('PRED_TIRE_PRESSURE', 'Slow tyre pressure loss detected', 'Tyres',
 'One tyre is losing pressure slowly over time, which can point to a small puncture or a failing valve. It is not flat, but it is trending down.',
 2, 1,
 'Check and top up the tyre soon, and have it inspected for a slow puncture.',
 'تسريب بطيء في ضغط الكاوتش',
 'في كاوتش واحد بيفقد ضغطه ببطء مع الوقت، وده ممكن يكون سبب ثقب صغير أو صمام بايظ. مش نايم خالص، بس الضغط بينزل تدريجيًا.',
 'افحص وزوّد هوا الكاوتش قريب، وودّيه يتفحص لو فيه ثقب.');
)SQL";
}
