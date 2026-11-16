package io.github.whywood.deepdeep.plugin.sysu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
internal data class SysuSchedule(
    val term: String? = null,
    val week: JsonElement? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val days: List<SysuScheduleDay> = emptyList(),
)

@Serializable
internal data class SysuScheduleDay(
    val date: String? = null,
    val weekday: String? = null,
    val items: List<SysuScheduleItem> = emptyList(),
)

@Serializable
internal data class SysuScheduleItem(
    val type: String? = null,
    val name: String? = null,
    val teacher: String? = null,
    val place: String? = null,
    val time: String? = null,
    val sections: String? = null,
)

@Serializable
internal data class SysuScoreSemester(
    val semester: String? = null,
    val count: JsonElement? = null,
    val credit: JsonElement? = null,
    val gpa: JsonElement? = null,
    val courses: List<SysuScoreCourse> = emptyList(),
)

@Serializable
internal data class SysuScoreCourse(
    val scoCourseNumber: String? = null,
    val scoCourseName: String? = null,
    val scoCredit: JsonElement? = null,
    val scoFinalScore: JsonElement? = null,
    val scoPoint: JsonElement? = null,
)

@Serializable
internal data class SysuExamTerm(
    val term: String? = null,
    val exams: List<SysuExam> = emptyList(),
)

@Serializable
internal data class SysuExam(
    val week: JsonElement? = null,
    val date: String? = null,
    val weekday: String? = null,
    val type: String? = null,
    val name: String? = null,
    val teacher: String? = null,
    val place: String? = null,
    val time: String? = null,
    val sections: String? = null,
)

@Serializable
internal data class SysuCreditPlan(
    val studentNo: String? = null,
    val professionNumber: String? = null,
    val gradeProfessionCode: String? = null,
    val grade: String? = null,
    val trainCateCode: String? = null,
    val teachPlanNumber: String? = null,
    val minGraduationCredit: JsonElement? = null,
    val finishCredit: JsonElement? = null,
    val publicMinGraduationCredit: JsonElement? = null,
    val publicMinCredit: JsonElement? = null,
    val publicFinishCredit: JsonElement? = null,
    val majorMinCredit: JsonElement? = null,
    val majorFinishCredit: JsonElement? = null,
    val majorSelectMinCredit: JsonElement? = null,
    val majorSelectFinishCredit: JsonElement? = null,
    val publicSelectMinCredit: JsonElement? = null,
    val publicSelectFinishCredit: JsonElement? = null,
    val honorCourseMinCredit: JsonElement? = null,
    val honorCourseFinishCredit: JsonElement? = null,
    val finishCreditRate: JsonElement? = null,
)

@Serializable
internal data class SysuLmsCourse(
    val id: JsonElement? = null,
    val name: String? = null,
    val short: String? = null,
    val progress: JsonElement? = null,
    val start: String? = null,
    val end: String? = null,
    val hidden: Boolean? = null,
)

@Serializable
internal data class SysuLmsTodo(
    val name: String? = null,
    val course: String? = null,
    val at: String? = null,
    val url: String? = null,
    val action: String? = null,
)

@Serializable
internal data class SysuGymSlots(
    val ok: Boolean? = null,
    val types: List<SysuGymType> = emptyList(),
    val errors: List<SysuGymError> = emptyList(),
    val error: SysuGymFailure? = null,
)

@Serializable
internal data class SysuGymType(
    val id: String? = null,
    val name: String? = null,
    val window: SysuGymWindow? = null,
    val fee: SysuGymFee? = null,
    val notice: String? = null,
    val venues: List<SysuGymVenue> = emptyList(),
    val error: String? = null,
)

@Serializable
internal data class SysuGymWindow(
    val start: String? = null,
    val end: String? = null,
)

@Serializable
internal data class SysuGymFee(
    @SerialName("Identity") val identity: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("UserRole") val userRole: String? = null,
    @SerialName("Timeslot") val timeslot: SysuGymTimeslot? = null,
    @SerialName("CreditFee") val creditFee: JsonElement? = null,
    @SerialName("CashFee") val cashFee: JsonElement? = null,
)

@Serializable
internal data class SysuGymTimeslot(
    @SerialName("DayType") val dayType: String? = null,
    @SerialName("StartTime") val startTime: String? = null,
    @SerialName("EndTime") val endTime: String? = null,
)

@Serializable
internal data class SysuGymVenue(
    val id: String? = null,
    val name: String? = null,
    val shortName: String? = null,
    val slots: List<SysuGymSlot> = emptyList(),
)

@Serializable
internal data class SysuGymSlot(
    val date: String? = null,
    val start: String? = null,
    val end: String? = null,
    val available: JsonElement? = null,
)

@Serializable
internal data class SysuGymError(
    val id: String? = null,
    val name: String? = null,
    val error: String? = null,
)

@Serializable
internal data class SysuGymFailure(
    val message: String? = null,
    val code: JsonElement? = null,
)

@Serializable
internal data class SysuNetpayRoot(
    val info: JsonObject? = null,
    val services: List<SysuNetpayService> = emptyList(),
)

@Serializable
internal data class SysuNetpayService(
    val no: String? = null,
    val service: String? = null,
    val address: String? = null,
    val mac: String? = null,
    val dept: String? = null,
    val user: String? = null,
    val status: String? = null,
    val expire: String? = null,
    val paused: String? = null,
    val id: String? = null,
    val daysLeft: JsonElement? = null,
)

internal object SysuJson {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun schedule(payload: JsonElement): SysuSchedule? = decode(payload)

    fun scores(payload: JsonElement): List<SysuScoreSemester>? = decode(payload)

    fun exams(payload: JsonElement): SysuExamTerm? = decode(payload)

    fun credits(payload: JsonElement): List<SysuCreditPlan>? = decode(payload)

    fun lmsCourses(payload: JsonElement): List<SysuLmsCourse>? = decode(payload)

    fun lmsTodo(payload: JsonElement): List<SysuLmsTodo>? = decode(payload)

    fun gymSlots(payload: JsonElement): SysuGymSlots? = decode(payload)

    fun netpay(payload: JsonElement): SysuNetpayRoot? = decode(payload)

    private inline fun <reified T> decode(payload: JsonElement): T? =
        runCatching { json.decodeFromJsonElement<T>(payload) }.getOrNull()
}

internal fun JsonElement?.scalarText(): String? {
    if (this == null || this is JsonNull) return null
    val primitive = this as? JsonPrimitive ?: return null
    val content = primitive.contentOrNull?.trim() ?: return null
    if (content.isEmpty() || content == "null" || content == "undefined") return null
    return content
}

internal fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

internal fun JsonElement?.doubleOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull

internal fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "—"
