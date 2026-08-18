"""Batch-renders app/src/main/assets/voice_ar/clips/*.wav from voice_ar_clips_todo.json.

Manifest keys are the ORIGINAL Egyptian-dialect sentence text (must match what
Assistant.cpp/dtc_seed.sql literally emit, since ArabicClipTts.clipFor looks up
by that text). The audio itself is rendered from CLEANED_TEXT below -- standard
Arabic phrasing with colloquial word-forms swapped out, since edge-tts's Arabic
frontend mispronounces colloquial orthography regardless of voice/locale chosen.
"""
import asyncio
import json
import os
import subprocess

import edge_tts
import imageio_ffmpeg

VOICE = "ar-EG-SalmaNeural"
RATE = "+8%"
PITCH = "+15Hz"
SAMPLE_RATE = 24_000

ROOT = "/home/yasmine/Desktop/gp/Android/MotorGuardApp"
CLIPS_DIR = f"{ROOT}/app/src/main/assets/voice_ar/clips"
MANIFEST_PATH = f"{ROOT}/app/src/main/assets/voice_ar/manifest.json"
TODO_PATH = f"{ROOT}/scripts/voice_ar_clips_todo.json"
FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()

CLEANED_TEXT = {
    "p0217_name": "حرارة المحرك مرتفعة عن الحد",
    "p0217_expl": "محركك سخن أكثر من اللازم.",
    "p0217_expl_2": "غالبًا السبب مياه التبريد ناقصة، أو يوجد تسرب في نظام التبريد، أو مضخة المياه أو الترموستات معطلة.",
    "p0217_expl_3": "إذا استمررت في القيادة والسيارة بهذه الحرارة، فقد يتلف المحرك بشكل دائم.",
    "p0217_action": "قلل من سرعتك وابحث عن مكان آمن للتوقف بسرعة.",
    "p0217_action_2": "إذا وصل مؤشر الحرارة إلى المنطقة الحمراء، توقف على جانب الطريق وأطفئ المحرك فورًا.",
    "p0524_name": "ضغط زيت المحرك منخفض جدًا",
    "p0524_expl": "انخفض ضغط الزيت عن المعدل الآمن.",
    "p0524_expl_2": "بدون ضغط زيت جيد، قد يتوقف المحرك تمامًا خلال دقائق.",
    "p0524_expl_3": "هذا من أخطر التحذيرات التي يمكن أن تظهرها لك السيارة.",
    "p0524_action": "توقف على جانب الطريق بأمان وأطفئ المحرك في أقرب وقت ممكن.",
    "p0524_action_2": "لا تُكمل القيادة إطلاقًا.",
    "c1201_name": "عطل في نظام التحكم بالثبات",
    "c1201_expl": "نظام التحكم في الثبات والجر تعرض لعطل، والآن لا يعمل.",
    "c1201_expl_2": "لا تزال السيارة تعمل بشكل طبيعي في القيادة والفرملة، لكنها لن تساعدك تلقائيًا إذا انزلقت إحدى العجلات.",
    "c1201_action": "قُد بهدوء، خصوصًا في الشتاء أو على الطريق المبلل، وخذ السيارة للفحص خلال يوم أو يومين.",
    "c0035_name": "حساس سرعة العجلة الأمامية اليسرى معطل",
    "c0035_expl": "حساس سرعة العجلة معطل.",
    "c0035_expl_2": "هذا يمكن أن يعطّل نظام الـ ABS والتحكم في الثبات لأن السيارة لم تعد قادرة على قياس سرعة دوران هذه العجلة.",
    "c0035_expl_3": "الفرامل العادية لا تزال تعمل بشكل جيد.",
    "c0035_action": "تجنب الفرملة القوية على الأرض الزلقة واحجز موعد صيانة قريبًا.",
    "p0300_name": "تفويت اشتعال في المحرك",
    "p0300_expl": "سلندر واحد أو أكثر يفوّت الاشتعال.",
    "p0300_expl_2": "قد تشعر أن المحرك يهتز أو يفقد قوته.",
    "p0300_expl_3": "الاستمرار في التفويت لفترة طويلة قد يؤدي إلى ارتفاع حرارة المحول الحفاز وتلفه.",
    "p0300_action": "خفف الضغط على دواسة الوقود وتجنب السرعات العالية.",
    "p0300_action_2": "خذها للفحص اليوم، خصوصًا إذا كان الضوء يومض وليس ثابتًا.",
    "p0420_name": "كفاءة المحول الحفاز منخفضة",
    "p0420_expl": "المحول الحفاز لا ينظّف العادم كما ينبغي.",
    "p0420_expl_2": "قيادة السيارة آمنة، لكنها لن تجتاز فحص العادم، وقد يزيد استهلاك الوقود.",
    "p0420_action": "لا يوجد أمر مستعجل.",
    "p0420_action_2": "خذها للفحص في الصيانة القادمة.",
    "p0562_name": "جهد النظام الكهربائي منخفض",
    "p0562_expl": "جهد النظام الكهربائي منخفض، وهذا غالبًا يعني أن المولّد أو البطارية تالفة.",
    "p0562_expl_2": "إذا كان المولّد تالفًا، فقد تفقد السيارة الكهرباء وتتوقف عندما تفرغ البطارية.",
    "p0562_action": "أغلق الأجهزة الكهربائية غير الضرورية (التكييف، تدفئة المقاعد) وتوجه إلى أقرب محطة خدمة.",
    "p0562_action_2": "إذا كانت الأضواء تخفت أو ضوء تحذير البطارية مضاء، توقف بسرعة.",
    "p0128_name": "حرارة المياه أقل من درجة التشغيل",
    "p0128_expl": "يأخذ المحرك وقتًا طويلاً حتى يسخن، وغالبًا السبب أنّ الترموستات عالقة في وضع مفتوح.",
    "p0128_expl_2": "ليس خطيرًا، لكنه يؤثر على استهلاك الوقود والتدفئة داخل السيارة.",
    "p0128_action_2": "اذكرها في الصيانة القادمة.",
    "u0100_name": "فقدان الاتصال مع كمبيوتر المحرك",
    "u0100_expl": "وحدة تحكم توقفت عن الاتصال بكمبيوتر المحرك عبر الشبكة الداخلية.",
    "u0100_expl_2": "قد يصبح تصرف السيارة غير متوقع، وقد تعطي الأضواء إشارات خاطئة.",
    "u0100_action": "تعامل مع أي تحذير آخر بحذر وخذها للفحص اليوم.",
    "b1000_name": "عطل في نظام الوسائد الهوائية",
    "b1000_expl": "يوجد عطل في نظام الوسائد الهوائية، وقد لا تنفتح إذا وقع حادث.",
    "b1000_expl_2": "السيارة تسير بشكل طبيعي، لكن نظام أمان مهم لا يعمل بشكل صحيح.",
    "b1000_action": "قُد بحذر أكبر وخذ السيارة لفحص نظام الحماية في أقرب وقت ممكن.",
    "pred_brake_wear_name": "وسادات الفرامل أوشكت على النفاد",
    "pred_brake_wear_expl": "يتوقع نظام الصيانة التنبؤي أنّ وسادات الفرامل اقتربت من حد الاستهلاك بناءً على قيادتك الأخيرة.",
    "pred_brake_wear_expl_2": "الفرملة جيدة الآن، لكن الوسادات ستحتاج إلى تغيير قريبًا.",
    "pred_brake_wear_action": "احجز كشف فرامل خلال الأسبوعين القادمين.",
    "pred_battery_health_name": "حالة البطارية تضعف",
    "pred_battery_health_expl": "تظهر على البطارية علامات التقدم في العمر، وقد تواجه صعوبة في تشغيل السيارة في الجو البارد.",
    "pred_battery_health_expl_2": "لا تزال تعمل ولم تتوقف إطلاقًا.",
    "pred_battery_health_action": "فكّر في تغيير البطارية قبل الشتاء أو قبل أي رحلة طويلة.",
    "pred_coolant_trend_name": "حرارة المياه تميل للارتفاع",
    "pred_coolant_trend_expl": "في الرحلات الأخيرة، المحرك يسخن قليلاً أكثر من المعتاد.",
    "pred_coolant_trend_expl_2": "لا يوجد خطأ الآن، لكن هذا غالبًا علامة مبكرة على مشكلة في نظام التبريد.",
    "pred_coolant_trend_action": "خذ نظام التبريد للفحص قريبًا، قبل أن تصبح مشكلة فعلية.",
    "pred_tire_pressure_name": "تسريب بطيء في ضغط الإطار",
    "pred_tire_pressure_expl": "يوجد إطار واحد يفقد ضغطه ببطء مع الوقت، وهذا قد يكون بسبب ثقب صغير أو صمام تالف.",
    "pred_tire_pressure_expl_2": "ليس فارغًا تمامًا، لكن الضغط ينخفض تدريجيًا.",
    "pred_tire_pressure_action": "افحص وزوّد هواء الإطار قريبًا، وخذه للفحص إذا كان به ثقب.",
    "rule_coolant_stopnow": "المحرك سخن لدرجة خطيرة جدًا.",
    "rule_coolant_stopnow_2": "توقف على جانب الطريق بأمان وأطفئ المحرك فورًا حتى تتجنب تلفًا دائمًا.",
    "rule_coolant_trend_soon": "حرارة المحرك ترتفع أسرع من المعتاد.",
    "rule_coolant_trend_soon_2": "خذ نظام التبريد للفحص خلال يوم أو يومين.",
    "rule_voltage_urgent": "البطارية تفرغ والسيارة قد تتوقف فجأة.",
    "rule_voltage_urgent_2": "توجه إلى أقرب نقطة خدمة الآن وتجنب أن تُطفئ المحرك.",
    "rule_misfire_urgent": "التفويت شديد لدرجة أنه قد يتلف نظام العادم.",
    "rule_misfire_urgent_2": "قلل السرعة وخذها للفحص اليوم.",
    "unrecognised_name": "كود عطل غير معروف",
    "unrecognised_expl": "ليس لدي تفاصيل عن هذا الكود بالتحديد، لذلك لا أستطيع شرحه لك بالكامل.",
    "unrecognised_action_urgent": "حتى تطمئن، تعامل مع هذا الكود على أنه خطير وخذها للفحص في أقرب وقت ممكن.",
    "unrecognised_action_routine": "خذها إلى مركز صيانة في أقرب وقت يناسبك.",
    "repeat_nothing_said": "لم أقل شيئًا بعد.",
    "cancel_ok": "حسنًا.",
    "unknown_fallback": "عذرًا، لم أسمع جيدًا.",
    "unknown_fallback_2": "يمكنك أن تسألني لأشرح لك ضوء تحذير، أو أخبرك إذا كان خطيرًا، أو أبحث لك عن أقرب ورشة.",
    "no_active_warnings": "خبر جيد، لا توجد أي تحذيرات نشطة الآن.",
    "nothing_active_severity": "لا يوجد شيء نشط يقلقك الآن.",
    "no_location_provider": "لا أستطيع البحث عن ورش صيانة قريبة في هذه النسخة بعد، لكن بناءً على التحذير يجب أن تأخذها للفحص قريبًا.",
    "no_station_nearby": "لم أستطع أن أجد محطة خدمة قريبة الآن.",
    "here_is_nearby": "هذه أقرب نتيجة لك.",
    "no_faults_everything_fine": "لا توجد أي أعطال الآن.",
    "no_faults_everything_fine_2": "كل شيء على ما يرام.",
    "list_faults_ask_more": "اسألني عن أي منها حتى أشرح لك أكثر.",
    "help_text": "أنا مساعدك في الصيانة.",
    "help_text_2": "يمكنك أن تسألني أمورًا مثل: ما هذا الضوء، هل الأمر خطير، هل يمكنني الاستمرار في القيادة أم لا، أو أين أقرب ورشة.",
    "help_text_3": "وسأخبرك بنفسي إذا حدث أمر طارئ.",
    "severity_lead_stopnow": "الأمر عاجل.",
    "severity_lead_urgent": "الأمر خطير.",
    "severity_lead_soon": "يستحق أن تتصرف فيه قريبًا.",
    "severity_lead_advisory": "الأمر بسيط.",
    "predicted_heads_up": "هذا تنبيه استباقي وليس عطلاً نشطًا الآن.",
    "assess_stopnow": "لا — يجب أن تتوقف بمجرد أن يصبح ذلك آمنًا.",
    "assess_urgent": "يمكنك القيادة بحذر الآن، لكن لا تؤجل الأمر.",
    "assess_soon": "نعم، يمكنك الاستمرار في القيادة، لكن خذها للفحص قريبًا.",
    "assess_fine": "نعم، من الجيد أن تستمر في القيادة.",
    "assess_escalated": "رفعت درجة الخطورة بسبب قراءات الحساسات الحالية.",
}


async def render_one(clip_id: str, text: str) -> None:
    tmp_mp3 = f"/tmp/{clip_id}.mp3"
    out_wav = f"{CLIPS_DIR}/{clip_id}.wav"
    communicate = edge_tts.Communicate(text, VOICE, rate=RATE, pitch=PITCH)
    await communicate.save(tmp_mp3)
    subprocess.run(
        [FFMPEG, "-y", "-i", tmp_mp3, "-ac", "1", "-ar", str(SAMPLE_RATE), "-c:a", "pcm_s16le", out_wav],
        check=True,
        capture_output=True,
    )
    os.remove(tmp_mp3)


async def main() -> None:
    os.makedirs(CLIPS_DIR, exist_ok=True)
    with open(TODO_PATH, encoding="utf-8") as f:
        todo = json.load(f)

    missing = [k for k in todo if k not in CLEANED_TEXT]
    if missing:
        raise SystemExit(f"CLEANED_TEXT missing entries for: {missing}")

    manifest: dict[str, str] = {}
    for i, (clip_id, original_text) in enumerate(todo.items(), 1):
        await render_one(clip_id, CLEANED_TEXT[clip_id])
        manifest[original_text] = clip_id
        print(f"[{i}/{len(todo)}] rendered {clip_id}")

    with open(MANIFEST_PATH, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print(f"wrote manifest with {len(manifest)} entries")


if __name__ == "__main__":
    asyncio.run(main())
