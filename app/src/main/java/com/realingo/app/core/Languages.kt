package com.realingo.app.core

/**
 * Language and voice tables, plus every per-protocol rule the UI branches on.
 * Ported 1:1 from ReaLingo's src/languages.ts.
 */

data class Lang(val code: String, val zh: String, val en: String, val tts: Boolean)

private val LANG_TABLE = """
zh|中文|Chinese|1
en|英语|English|1
ja|日语|Japanese|1
ko|韩语|Korean|1
fr|法语|French|1
de|德语|German|1
es|西班牙语|Spanish|1
pt|葡萄牙语|Portuguese|1
ru|俄语|Russian|1
it|意大利语|Italian|1
ar|阿拉伯语|Arabic|1
id|印度尼西亚语|Indonesian|1
th|泰语|Thai|1
vi|越南语|Vietnamese|1
tr|土耳其语|Turkish|1
hi|印地语|Hindi|1
ms|马来语|Malay|1
nl|荷兰语|Dutch|1
ur|乌尔都语|Urdu|1
nb|挪威语|Norwegian|1
sv|瑞典语|Swedish|1
da|丹麦语|Danish|1
he|希伯来语|Hebrew|1
fi|芬兰语|Finnish|1
pl|波兰语|Polish|1
is|冰岛语|Icelandic|1
cs|捷克语|Czech|1
fil|菲律宾语|Filipino|1
fa|波斯语|Persian|1
yue|粤语|Cantonese|0
el|希腊语|Greek|0
af|南非荷兰语|Afrikaans|0
ast|阿斯图里亚斯语|Asturian|0
az|阿塞拜疆语|Azerbaijani|0
be|白俄罗斯语|Belarusian|0
bg|保加利亚语|Bulgarian|0
bn|孟加拉语|Bengali|0
bs|波斯尼亚语|Bosnian|0
ca|加泰罗尼亚语|Catalan|0
ceb|宿务语|Cebuano|0
et|爱沙尼亚语|Estonian|0
gl|加利西亚语|Galician|0
gu|古吉拉特语|Gujarati|0
hr|克罗地亚语|Croatian|0
hu|匈牙利语|Hungarian|0
jv|爪哇语|Javanese|0
kk|哈萨克语|Kazakh|0
kn|卡纳达语|Kannada|0
ky|柯尔克孜语|Kyrgyz|0
lv|拉脱维亚语|Latvian|0
mk|马其顿语|Macedonian|0
ml|马拉雅拉姆语|Malayalam|0
mr|马拉地语|Marathi|0
pa|旁遮普语|Punjabi|0
ro|罗马尼亚语|Romanian|0
sk|斯洛伐克语|Slovak|0
sl|斯洛文尼亚语|Slovenian|0
sw|斯瓦希里语|Swahili|0
tg|塔吉克语|Tajik|0
uk|乌克兰语|Ukrainian|0
"""

val LANGUAGES: List<Lang> = LANG_TABLE.trim().lines().map { line ->
    val p = line.split("|")
    Lang(p[0], p[1], p[2], p[3] == "1")
}

private val BY_CODE: Map<String, Lang> = LANGUAGES.associateBy { it.code }

const val MODEL_QWEN3_5 = "qwen3.5-livetranslate-flash-realtime"
const val MODEL_QWEN3_8 = "qwen3.8-livetranslate-flash-realtime"
const val MODEL_QWEN3 = "qwen3-livetranslate-flash-realtime"
const val MODEL_OPENAI = "gpt-realtime-translate"
const val MODEL_GEMINI = "gemini-3.5-live-translate-preview"
const val ASR_MODEL = "qwen3-asr-flash-realtime"
const val OPENAI_ASR_MODEL = "gpt-realtime-whisper"
const val DEFAULT_VOICE = "Tina"

/** Message format, not vendor: Qwen 3.8 speaks a different schema from 3/3.5. */
enum class Protocol(val id: String, val vendor: Vendor) {
    QWEN35("qwen35", Vendor.QWEN),
    QWEN38("qwen38", Vendor.QWEN),
    OPENAI("openai", Vendor.OPENAI),
    GEMINI("gemini", Vendor.GEMINI);

    companion object {
        fun of(id: String?): Protocol = entries.firstOrNull { it.id == id } ?: QWEN35
    }
}

enum class Vendor { QWEN, OPENAI, GEMINI }

enum class Region(val id: String, val sharedHost: String, val labelZh: String, val labelEn: String) {
    BEIJING("cn-beijing", "dashscope.aliyuncs.com", "北京", "Beijing"),
    SINGAPORE("ap-southeast-1", "dashscope-intl.aliyuncs.com", "新加坡", "Singapore");

    companion object {
        fun of(name: String?): Region = entries.firstOrNull { it.name == name } ?: BEIJING
    }
}

/** The service picks the voice; the source is always detected; every target can be spoken. */
data class ProtocolInfo(
    val serverVoice: Boolean,
    val autoSource: Boolean,
    val speaksAll: Boolean,
    val urlAsIs: Boolean,
)

val PROTOCOLS: Map<Protocol, ProtocolInfo> = mapOf(
    Protocol.QWEN35 to ProtocolInfo(serverVoice = false, autoSource = false, speaksAll = false, urlAsIs = false),
    Protocol.QWEN38 to ProtocolInfo(serverVoice = false, autoSource = true, speaksAll = false, urlAsIs = false),
    Protocol.OPENAI to ProtocolInfo(serverVoice = true, autoSource = true, speaksAll = true, urlAsIs = false),
    Protocol.GEMINI to ProtocolInfo(serverVoice = true, autoSource = true, speaksAll = true, urlAsIs = true),
)

data class Preset(val id: String, val name: String, val langs: Int)

val PRESETS: Map<Protocol, List<Preset>> = mapOf(
    Protocol.QWEN35 to listOf(
        Preset(MODEL_QWEN3_5, "Qwen3.5", 60),
        Preset(MODEL_QWEN3, "Qwen3", 18),
    ),
    Protocol.QWEN38 to listOf(Preset(MODEL_QWEN3_8, "Qwen3.8", 60)),
    Protocol.OPENAI to listOf(Preset(MODEL_OPENAI, "GPT-Realtime-Translate", 13)),
    Protocol.GEMINI to listOf(Preset(MODEL_GEMINI, "Gemini 3.5 Live Translate", 54)),
)

fun modelName(protocol: Protocol, model: String): String {
    val presets = PRESETS.getValue(protocol)
    return presets.firstOrNull { it.id == model }?.name ?: model.ifBlank { presets.first().name }
}

private val QWEN3_CODES = "en zh ru fr de pt es it id ko ja vi th ar yue hi el tr".split(" ").toSet()
private val OPENAI_CODES = "es pt fr ja ru zh de ko hi id vi it en".split(" ").toSet()
private val GEMINI_MISSING = "yue ast bs ceb ky tg".split(" ").toSet()

fun languageCodes(protocol: Protocol, model: String): List<String> {
    val all = LANGUAGES.map { it.code }
    return when (protocol) {
        Protocol.OPENAI -> all.filter { it in OPENAI_CODES }
        Protocol.GEMINI -> all.filter { it !in GEMINI_MISSING }
        else -> if (model == MODEL_QWEN3) all.filter { it in QWEN3_CODES } else all
    }
}

fun canSpeak(protocol: Protocol, code: String): Boolean =
    PROTOCOLS.getValue(protocol).speaksAll || (BY_CODE[code]?.tts ?: false)

data class Voice(val id: String, val zh: String, val descZh: String, val descEn: String)

private val VOICE_TABLE = """
Tina|甜甜|甜美温暖的女声（默认）|Sweet, warm female (default)
Cindy|林欣宜|台湾腔，娇嗲女声|Cutesy female, Taiwanese accent
Serena|苏瑶|温柔女声|Gentle female
Maia|四月|知性温柔的女声|Poised, gentle female
Mia|舒然|舒缓治愈的生活博主|Soothing lifestyle-vlogger female
Momo|茉兔|俏皮爱撒娇|Playful, cute female
Angel|安琪|略带台湾口音的甜美女声|Sweet female, light Taiwanese accent
Qiao|小乔妹|甜美又有个性|Sweet but spirited female
Katerina|卡捷琳娜|成熟御姐，韵律感强|Mature female, rich cadence
Jennifer|詹妮弗|电影质感的美式女声|Cinematic American female
Mione|敏儿|成熟知性的英式女声|Mature British female
Sohee|素熙|温柔开朗的韩国女声|Warm, cheerful Korean female
Sonrisa|索尼莎|热情开朗的拉美女声|Lively Latin American female
Marina|玛丽娜|多元文化背景的女声|Cosmopolitan female
Hana|阿幸|成熟的越南女声|Mature Vietnamese female
Griet|海娜|成熟文艺的荷兰女声|Mature, artsy Dutch female
Eliška|艾莉卡|温润的中欧女声|Warm Central European female
Siiri|西芮|内敛温柔，语速舒缓|Reserved, unhurried female
Ingrid|林恩|淳朴的挪威女声|Down-to-earth Norwegian female
Sigga|海娜|知性的冰岛女声|Poised Icelandic female
Bea|雅娜|甜美的菲律宾女声|Sweet Filipino female
Chloe|思怡|马来西亚白领女声|Malaysian office-worker female
Roya|萝雅|爽朗自由的运动女孩|Sporty, free-spirited female
Sunnybobi|知芝|大大咧咧的邻家女孩|Easygoing girl next door
Liora Mira|清欢|温柔有烟火气的女声|Tender, homely female
Ono Anna|小野杏|古灵精怪的青梅竹马|Mischievous childhood friend
Raymond|林川野|清亮的宅男声|Clear-voiced young male
Ethan|晨煦|阳光有活力，略带北方口音|Bright, upbeat male, slight northern accent
Harvey|厚|低沉温和的成熟男声|Deep, mellow mature male
Evan|江晨|青涩的男大学生|Youthful college male
Wil|伟伦|港台腔小哥哥|Young male, Hong Kong/Taiwan accent
Ryan|甜茶|节奏强、戏感足|Punchy, dramatic male
Aiden|艾登|美式大男孩|Young American male
Lenn|莱恩|理性又带点叛逆的德国青年|Rational, edgy German male
Bodega|博德加|热情的西班牙大叔|Warm Spanish uncle
Emilien|埃米尔安|浪漫的法国男声|Romantic French male
Andre|安德雷|沉稳磁性的男声|Calm, magnetic male
Alek|阿列克|外冷内暖的俄罗斯男声|Cool-yet-warm Russian male
Rizky|阿力|有个性的印尼青年|Distinctive Indonesian young male
Arda|阿尔达|干净温润的男声|Clean, warm male
Dolce|多尔切|慵懒的意大利大叔|Laid-back Italian uncle
Jakub|雅克|磁性的波兰文艺青年|Magnetic, artsy Polish male
Theo Calm|予安|沉静治愈的声音|Calm, healing voice
Joyner|阿逗|搞笑夸张的喜剧声|Over-the-top comic
Gold|金爷|西海岸说唱风|West Coast rapper
Li Cassian|李公公|宫廷太监角色音|Imperial eunuch character
Radio Gol|拉迪奥·戈尔|足球解说风|Football commentator
"""

val VOICES: List<Voice> = VOICE_TABLE.trim().lines().map { line ->
    val p = line.split("|")
    Voice(p[0], p[1], p[2], p[3])
}

fun langName(code: String, zh: Boolean): String {
    if (code == "auto") return if (zh) "自动检测" else "Auto detect"
    val l = BY_CODE[code] ?: return code
    return if (zh) l.zh else l.en
}
