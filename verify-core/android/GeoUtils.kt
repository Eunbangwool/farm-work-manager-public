package com.sangwolnongsan.farmwork.data.location

import org.json.JSONArray
import org.json.JSONObject

/**
 * GeoJSON Polygon 안에 좌표가 포함되는지 판정.
 *
 * Ray casting 알고리즘 - O(n) 시간, polygon 변 개수에 비례.
 * 위/경도를 평면 좌표로 단순 처리. 농지 단위(수십~수백 m)는 충분히 정확.
 */
object GeoUtils {

    /**
     * @param polygonGeoJson FarmLand.polygonGeoJson (Polygon 또는 MultiPolygon)
     * @return 안에 있으면 true
     */
    fun isPointInPolygon(lat: Double, lng: Double, polygonGeoJson: String): Boolean {
        return try {
            val geometry = JSONObject(polygonGeoJson)
            when (geometry.optString("type")) {
                "Polygon" -> {
                    val ring = geometry.getJSONArray("coordinates").getJSONArray(0)
                    isInside(lat, lng, ring)
                }
                "MultiPolygon" -> {
                    val polygons = geometry.getJSONArray("coordinates")
                    var inside = false
                    for (i in 0 until polygons.length()) {
                        val ring = polygons.getJSONArray(i).getJSONArray(0)
                        if (isInside(lat, lng, ring)) { inside = true; break }
                    }
                    inside
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * GeoJSON ring (외곽선) 안에 점이 있는지.
     * ring은 [[lng, lat], [lng, lat], ...] 배열.
     */
    private fun isInside(lat: Double, lng: Double, ring: JSONArray): Boolean {
        var inside = false
        val n = ring.length()
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring.getJSONArray(i).getDouble(0)  // lng
            val yi = ring.getJSONArray(i).getDouble(1)  // lat
            val xj = ring.getJSONArray(j).getDouble(0)
            val yj = ring.getJSONArray(j).getDouble(1)

            // Ray going east from (lng, lat) crosses edge (xi,yi)-(xj,yj)?
            val intersect = ((yi > lat) != (yj > lat)) &&
                    (lng < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * 영역 bbox [minLng, minLat, maxLng, maxLat] + 면적(degree²) 계산.
     * 면적은 평면 근사 — 한국 cadastral 비교용으로 충분 (위도 35~38° 범위).
     */
    data class PolyBox(val minLng: Double, val minLat: Double, val maxLng: Double, val maxLat: Double) {
        val areaDeg: Double get() = (maxLng - minLng) * (maxLat - minLat)
        fun contains(other: PolyBox): Boolean =
            minLng <= other.minLng && maxLng >= other.maxLng &&
            minLat <= other.minLat && maxLat >= other.maxLat
    }

    /**
     * 점에서 영역 경계선까지의 최소 거리 (미터). 내부/외부 무관 가장 가까운 변까지.
     * 평면 근사 — 위도에 따른 경도 스케일 보정. 한국(35~38°) 농지 단위에 충분.
     * 판정 불가(파싱 실패/링 없음) 시 null.
     */
    fun distanceToPolygonMeters(lat: Double, lng: Double, polygonGeoJson: String): Double? = try {
        val geom = JSONObject(polygonGeoJson)
        val rings: List<JSONArray> = when (geom.optString("type")) {
            "Polygon" -> listOf(geom.getJSONArray("coordinates").getJSONArray(0))
            "MultiPolygon" -> {
                val polys = geom.getJSONArray("coordinates")
                List(polys.length()) { polys.getJSONArray(it).getJSONArray(0) }
            }
            else -> emptyList()
        }
        if (rings.isEmpty()) null
        else {
            // 위/경도 → 미터 변환 계수 (위도 기준 로컬 평면)
            val mPerDegLat = 111_320.0
            val mPerDegLng = 111_320.0 * kotlin.math.cos(Math.toRadians(lat))
            val px = lng * mPerDegLng
            val py = lat * mPerDegLat
            var best = Double.POSITIVE_INFINITY
            for (ring in rings) {
                val n = ring.length()
                var j = n - 1
                for (i in 0 until n) {
                    val ax = ring.getJSONArray(j).getDouble(0) * mPerDegLng
                    val ay = ring.getJSONArray(j).getDouble(1) * mPerDegLat
                    val bx = ring.getJSONArray(i).getDouble(0) * mPerDegLng
                    val by = ring.getJSONArray(i).getDouble(1) * mPerDegLat
                    best = minOf(best, distPointToSegment(px, py, ax, ay, bx, by))
                    j = i
                }
            }
            if (best.isInfinite()) null else best
        }
    } catch (_: Exception) {
        null
    }

    private fun distPointToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= 1e-9) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return kotlin.math.hypot(px - cx, py - cy)
    }

    /** 경계 안쪽으로 볼 버퍼 (m). 측위오차(accuracy)를 더한 값이 필지 밖 허용 거리. */
    const val INSIDE_BUFFER_M = 15.0

    /**
     * 촬영 좌표 판정.
     * - 폴리곤 내부, 또는 경계에서 (측위정확도 + [INSIDE_BUFFER_M]) 이내 → INSIDE
     * - 그 외 → OUTSIDE
     * - 영역 미등록 → UNKNOWN
     */
    fun verify(
        lat: Double,
        lng: Double,
        accuracyM: Float,
        polygonGeoJson: String?,
    ): com.sangwolnongsan.farmwork.data.PhotoVerifyResult {
        if (polygonGeoJson.isNullOrBlank()) {
            return com.sangwolnongsan.farmwork.data.PhotoVerifyResult.UNKNOWN
        }
        if (isPointInPolygon(lat, lng, polygonGeoJson)) {
            return classify(insidePolygon = true, distToEdgeM = 0.0, accuracyM = accuracyM)
        }
        val dist = distanceToPolygonMeters(lat, lng, polygonGeoJson)
        return classify(insidePolygon = false, distToEdgeM = dist, accuracyM = accuracyM)
    }

    /**
     * [INSIDE_BUFFER_M] + 측위오차 이내면 INSIDE. 폴리곤 파싱 실패(거리 null)는 UNKNOWN.
     */
    fun classify(
        insidePolygon: Boolean,
        distToEdgeM: Double?,
        accuracyM: Float,
    ): com.sangwolnongsan.farmwork.data.PhotoVerifyResult {
        if (insidePolygon) return com.sangwolnongsan.farmwork.data.PhotoVerifyResult.INSIDE
        if (distToEdgeM == null) return com.sangwolnongsan.farmwork.data.PhotoVerifyResult.UNKNOWN
        val accuracy = if (accuracyM > 0) accuracyM.toDouble() else 0.0
        return if (distToEdgeM <= INSIDE_BUFFER_M + accuracy)
            com.sangwolnongsan.farmwork.data.PhotoVerifyResult.INSIDE
        else com.sangwolnongsan.farmwork.data.PhotoVerifyResult.OUTSIDE
    }

    fun polygonBbox(polygonGeoJson: String): PolyBox? = try {
        val geom = JSONObject(polygonGeoJson)
        val rings: List<JSONArray> = when (geom.optString("type")) {
            "Polygon" -> listOf(geom.getJSONArray("coordinates").getJSONArray(0))
            "MultiPolygon" -> {
                val polys = geom.getJSONArray("coordinates")
                List(polys.length()) { polys.getJSONArray(it).getJSONArray(0) }
            }
            else -> emptyList()
        }
        if (rings.isEmpty()) null
        else {
            var minLng = Double.POSITIVE_INFINITY
            var minLat = Double.POSITIVE_INFINITY
            var maxLng = Double.NEGATIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            for (ring in rings) {
                for (i in 0 until ring.length()) {
                    val pt = ring.getJSONArray(i)
                    val lng = pt.getDouble(0)
                    val lat = pt.getDouble(1)
                    if (lng < minLng) minLng = lng
                    if (lng > maxLng) maxLng = lng
                    if (lat < minLat) minLat = lat
                    if (lat > maxLat) maxLat = lat
                }
            }
            if (minLng.isInfinite()) null else PolyBox(minLng, minLat, maxLng, maxLat)
        }
    } catch (_: Exception) {
        null
    }
}
