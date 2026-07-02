import kotlin.math.*

fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371e3 
    val φ1 = lat1 * PI / 180
    val φ2 = lat2 * PI / 180
    val Δφ = (lat2 - lat1) * PI / 180
    val Δλ = (lon2 - lon1) * PI / 180
    val a = sin(Δφ / 2).pow(2) + cos(φ1) * cos(φ2) * sin(Δλ / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}

println(calculateDistance(6.6257, 3.4785, 6.6257, 3.4783))
