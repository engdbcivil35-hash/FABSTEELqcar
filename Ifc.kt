package ir.fabsteel.arqc

import java.io.InputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** مدل خوانده‌شده از IFC. مختصات بر حسب متر، در دستگاه مختصات AR (محور Y بالا) و نسبت به گوشه‌ی مینیمم مدل. */
class Member(
    val name: String,
    val isHole: Boolean,
    val edges: FloatArray,
    val tris: FloatArray,
    val samples: FloatArray
)

class IfcModel(val members: List<Member>, val sizeM: FloatArray)

object ModelStore {
    @Volatile var model: IfcModel? = null
    @Volatile var fileName: String = ""
}

class FList(cap: Int = 256) {
    var a = FloatArray(cap)
    var n = 0
    fun add(x: Float, y: Float, z: Float) {
        if (n + 3 > a.size) a = a.copyOf(a.size * 2)
        a[n++] = x; a[n++] = y; a[n++] = z
    }
    fun toArray(): FloatArray = a.copyOf(n)
}

private class Ref(val id: Int)

private class Ent(val name: String, val raw: String) {
    val args: List<Any?> by lazy {
        try { StepArgs(raw).parseList() } catch (e: Exception) { emptyList() }
    }
}

private class StepArgs(private val s: String) {
    private var i = 0

    fun parseList(): List<Any?> {
        val out = ArrayList<Any?>()
        skipWs()
        while (i < s.length) {
            out.add(parseValue())
            skipWs()
            if (i < s.length && s[i] == ',') { i++; skipWs() } else break
        }
        return out
    }

    private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun parseValue(): Any? {
        skipWs()
        if (i >= s.length) return null
        val c = s[i]
        return when {
            c == '(' -> {
                i++
                val l = ArrayList<Any?>()
                skipWs()
                if (i < s.length && s[i] == ')') { i++; return l }
                while (i < s.length) {
                    l.add(parseValue())
                    skipWs()
                    if (i < s.length && s[i] == ',') i++ else { i++; break }
                }
                l
            }
            c == '#' -> {
                i++
                val st = i
                while (i < s.length && s[i].isDigit()) i++
                Ref(s.substring(st, i).toInt())
            }
            c == '\'' -> {
                i++
                val st = i
                while (i < s.length) {
                    if (s[i] == '\'') {
                        if (i + 1 < s.length && s[i + 1] == '\'') { i += 2; continue }
                        break
                    }
                    i++
                }
                val r = s.substring(st, min(i, s.length))
                i++
                r
            }
            c == '$' -> { i++; null }
            c == '*' -> { i++; "*" }
            c == '.' -> {
                i++
                val st = i
                while (i < s.length && s[i] != '.') i++
                val r = s.substring(st, min(i, s.length))
                i++
                ".$r."
            }
            else -> {
                val st = i
                while (i < s.length && s[i] != ',' && s[i] != ')' && s[i] != '(') i++
                val tok = s.substring(st, i).trim()
                if (i < s.length && s[i] == '(') {
                    val inner = parseValue()           // مقدار تایپ‌دار مثل IFCLENGTHMEASURE(5.)
                    (inner as? List<*>)?.firstOrNull()
                } else tok.toDoubleOrNull() ?: tok
            }
        }
    }
}

private class Geo(val s: Double) {
    val edges = FList()
    val tris = FList()
    private fun c(p: DoubleArray, l: FList) {
        // IFC (Z بالا) ← AR (Y بالا)
        l.add((p[0] * s).toFloat(), (p[2] * s).toFloat(), (-p[1] * s).toFloat())
    }
    fun edge(p: DoubleArray, q: DoubleArray) { c(p, edges); c(q, edges) }
    fun tri(p: DoubleArray, q: DoubleArray, r: DoubleArray) { c(p, tris); c(q, tris); c(r, tris) }
}

// ---------- ریاضی ----------
private fun ident() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)

private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
    val r = DoubleArray(12)
    for (i in 0..2) {
        for (j in 0..2) {
            r[i * 4 + j] = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j]
        }
        r[i * 4 + 3] = a[i * 4] * b[3] + a[i * 4 + 1] * b[7] + a[i * 4 + 2] * b[11] + a[i * 4 + 3]
    }
    return r
}

private fun app(m: DoubleArray, x: Double, y: Double, z: Double) = doubleArrayOf(
    m[0] * x + m[1] * y + m[2] * z + m[3],
    m[4] * x + m[5] * y + m[6] * z + m[7],
    m[8] * x + m[9] * y + m[10] * z + m[11]
)

private fun inv(m: DoubleArray): DoubleArray {
    val r = DoubleArray(12)
    for (i in 0..2) for (j in 0..2) r[i * 4 + j] = m[j * 4 + i]
    for (i in 0..2) r[i * 4 + 3] = -(r[i * 4] * m[3] + r[i * 4 + 1] * m[7] + r[i * 4 + 2] * m[11])
    return r
}

private fun fromAxes(x: DoubleArray, y: DoubleArray, z: DoubleArray, t: DoubleArray) = doubleArrayOf(
    x[0], y[0], z[0], t[0],
    x[1], y[1], z[1], t[1],
    x[2], y[2], z[2], t[2]
)

private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
    a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
)
private fun norm(a: DoubleArray): DoubleArray {
    val l = sqrt(dot(a, a))
    return if (l < 1e-12) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(a[0] / l, a[1] / l, a[2] / l)
}
private fun d2(x: Double, y: Double) = doubleArrayOf(x, y)

private fun area(p: List<DoubleArray>): Double {
    var s = 0.0
    for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a[0] * b[1] - b[0] * a[1] }
    return s / 2
}

private fun cr(a: DoubleArray, b: DoubleArray, c: DoubleArray) =
    (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])

private fun inTri(p: DoubleArray, a: DoubleArray, b: DoubleArray, c: DoubleArray): Boolean {
    val d1 = cr(a, b, p); val d2 = cr(b, c, p); val d3 = cr(c, a, p)
    return d1 >= -1e-9 && d2 >= -1e-9 && d3 >= -1e-9
}

/** مثلث‌بندی چندضلعی ساده‌ی ساعتگرد-خلاف (CCW). خروجی: اندیس‌ها سه‌تا سه‌تا */
private fun earClip(p: List<DoubleArray>): IntArray {
    val idx = ArrayList<Int>()
    for (k in p.indices) idx.add(k)
    val out = ArrayList<Int>()
    var guard = 0
    while (idx.size > 3 && guard++ < p.size * p.size + 10) {
        var clipped = false
        for (k in idx.indices) {
            val a = idx[(k + idx.size - 1) % idx.size]
            val b = idx[k]
            val c = idx[(k + 1) % idx.size]
            if (cr(p[a], p[b], p[c]) <= 1e-9) continue
            var inside = false
            for (o in idx) {
                if (o == a || o == b || o == c) continue
                if (inTri(p[o], p[a], p[b], p[c])) { inside = true; break }
            }
            if (inside) continue
            out.add(a); out.add(b); out.add(c)
            idx.removeAt(k)
            clipped = true
            break
        }
        if (!clipped) break
    }
    if (idx.size == 3) { out.add(idx[0]); out.add(idx[1]); out.add(idx[2]) }
    return out.toIntArray()
}

// ---------- پارسر ----------
class IfcParser(private val progress: (String) -> Unit) {
    private val ents = HashMap<Int, Ent>(1 shl 16)
    private val lpCache = HashMap<Int, DoubleArray>()
    private var scale = 0.001

    fun parse(input: InputStream): IfcModel {
        progress("در حال خواندن فایل…")
        readEntities(input)
        detectUnits()
        return build()
    }

    private fun readEntities(input: InputStream) {
        val br = input.bufferedReader(Charsets.ISO_8859_1)
        val sb = StringBuilder()
        var inData = false
        var n = 0
        while (true) {
            val line = br.readLine() ?: break
            val t = line.trim()
            if (!inData) { if (t.startsWith("DATA")) inData = true; continue }
            if (t.startsWith("ENDSEC")) continue
            sb.append(t)
            if (t.endsWith(";")) {
                handle(sb.toString())
                sb.setLength(0)
                if (++n % 20000 == 0) progress("خواندن ساختار… $n")
            }
        }
        br.close()
    }

    private fun handle(s: String) {
        if (s.isEmpty() || s[0] != '#') return
        val eq = s.indexOf('=')
        if (eq < 0) return
        val id = s.substring(1, eq).trim().toIntOrNull() ?: return
        val rest = s.substring(eq + 1).trim()
        val p = rest.indexOf('(')
        if (p <= 0) return
        val q = rest.lastIndexOf(')')
        if (q <= p) return
        val name = rest.substring(0, p).trim().uppercase()
        // موجودیت‌های بی‌ربط به هندسه را نگه نمی‌داریم (در فایل تکلا بیش از ۳۰٪ حجم‌اند) تا حافظه کم مصرف شود
        if (name.startsWith("IFCPROPERTY") || name.startsWith("IFCREL") || name.startsWith("IFCSTYLED") ||
            name.startsWith("IFCQUANTITY") || name.startsWith("IFCELEMENTQUANTITY") || name.startsWith("IFCPRESENTATION")) {
            if (!name.startsWith("IFCREPRESENTATIONMAP")) return
        }
        ents[id] = Ent(name, rest.substring(p + 1, q))
    }

    private fun detectUnits() {
        for (e in ents.values) {
            if (e.name == "IFCSIUNIT") {
                val a = e.args
                if (a.size >= 4 && a[1] == ".LENGTHUNIT.") {
                    scale = when (a[2]) {
                        ".MILLI." -> 0.001
                        ".CENTI." -> 0.01
                        ".DECI." -> 0.1
                        null -> 1.0
                        else -> 1.0
                    }
                    return
                }
            }
        }
    }

    private fun entAt(a: List<Any?>, i: Int): Ent? = (a.getOrNull(i) as? Ref)?.let { ents[it.id] }

    private fun ptE(e: Ent): DoubleArray {
        val l = e.args.getOrNull(0) as? List<*> ?: return doubleArrayOf(0.0, 0.0, 0.0)
        return DoubleArray(max(3, l.size)) { (l.getOrNull(it) as? Number)?.toDouble() ?: 0.0 }
    }

    private fun pt(ref: Any?): DoubleArray {
        val e = (ref as? Ref)?.let { ents[it.id] } ?: return doubleArrayOf(0.0, 0.0, 0.0)
        return ptE(e)
    }

    private fun placement3D(ref: Any?): DoubleArray {
        val e = (ref as? Ref)?.let { ents[it.id] } ?: return ident()
        val a = e.args
        val loc = pt(a.getOrNull(0))
        val z = if (a.getOrNull(1) is Ref) norm(pt(a[1])) else doubleArrayOf(0.0, 0.0, 1.0)
        var xr = if (a.getOrNull(2) is Ref) pt(a[2]) else doubleArrayOf(1.0, 0.0, 0.0)
        if (abs(dot(norm(xr), z)) > 0.999) xr = if (abs(z[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val k = dot(xr, z)
        val x = norm(doubleArrayOf(xr[0] - z[0] * k, xr[1] - z[1] * k, xr[2] - z[2] * k))
        val y = cross(z, x)
        return fromAxes(x, y, z, doubleArrayOf(loc[0], loc[1], loc[2]))
    }

    private fun placement2D(ref: Any?): DoubleArray {
        val e = (ref as? Ref)?.let { ents[it.id] } ?: return ident()
        val a = e.args
        val loc = pt(a.getOrNull(0))
        val rd = if (a.getOrNull(1) is Ref) pt(a[1]) else doubleArrayOf(1.0, 0.0, 0.0)
        val x = norm(doubleArrayOf(rd[0], rd[1], 0.0))
        val y = doubleArrayOf(-x[1], x[0], 0.0)
        return fromAxes(x, y, doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(loc[0], loc[1], 0.0))
    }

    private fun localPlacement(ref: Any?): DoubleArray {
        val r = ref as? Ref ?: return ident()
        lpCache[r.id]?.let { return it }
        val e = ents[r.id] ?: return ident()
        val res = when (e.name) {
            "IFCLOCALPLACEMENT" -> {
                val parent = if (e.args.getOrNull(0) is Ref) localPlacement(e.args[0]) else ident()
                mul(parent, placement3D(e.args.getOrNull(1)))
            }
            else -> ident()
        }
        lpCache[r.id] = res
        return res
    }

    private fun cto(e: Ent): DoubleArray {
        val a = e.args
        val x = if (a.getOrNull(0) is Ref) norm(pt(a[0])) else doubleArrayOf(1.0, 0.0, 0.0)
        val y = if (a.getOrNull(1) is Ref) norm(pt(a[1])) else doubleArrayOf(0.0, 1.0, 0.0)
        val z = if (a.getOrNull(4) is Ref) norm(pt(a[4])) else cross(x, y)
        val o = if (a.getOrNull(2) is Ref) pt(a[2]) else doubleArrayOf(0.0, 0.0, 0.0)
        val s = (a.getOrNull(3) as? Number)?.toDouble() ?: 1.0
        return fromAxes(
            doubleArrayOf(x[0] * s, x[1] * s, x[2] * s),
            doubleArrayOf(y[0] * s, y[1] * s, y[2] * s),
            doubleArrayOf(z[0] * s, z[1] * s, z[2] * s),
            doubleArrayOf(o[0], o[1], o[2])
        )
    }

    // ---------- پروفیل‌ها ----------
    private fun polyline(e: Ent?): List<DoubleArray>? {
        if (e == null || e.name != "IFCPOLYLINE") return null
        val l = e.args.getOrNull(0) as? List<*> ?: return null
        val pts = ArrayList<DoubleArray>()
        for (r in l) { val p = pt(r); pts.add(d2(p[0], p[1])) }
        if (pts.size > 1) {
            val f = pts.first(); val la = pts.last()
            if (abs(f[0] - la[0]) < 1e-9 && abs(f[1] - la[1]) < 1e-9) pts.removeAt(pts.size - 1)
        }
        return if (pts.size >= 3) pts else null
    }

    private fun profile(e: Ent?): List<DoubleArray>? {
        if (e == null) return null
        val a = e.args
        fun num(i: Int) = (a.getOrNull(i) as? Number)?.toDouble() ?: 0.0
        val pts: List<DoubleArray> = when (e.name) {
            "IFCRECTANGLEPROFILEDEF" -> {
                val x = num(3) / 2; val y = num(4) / 2
                listOf(d2(-x, -y), d2(x, -y), d2(x, y), d2(-x, y))
            }
            "IFCISHAPEPROFILEDEF" -> {
                val w = num(3) / 2; val d = num(4) / 2; val tw = num(5) / 2; val tf = num(6)
                listOf(
                    d2(-w, -d), d2(w, -d), d2(w, -d + tf), d2(tw, -d + tf),
                    d2(tw, d - tf), d2(w, d - tf), d2(w, d), d2(-w, d),
                    d2(-w, d - tf), d2(-tw, d - tf), d2(-tw, -d + tf), d2(-w, -d + tf)
                )
            }
            "IFCCIRCLEPROFILEDEF", "IFCCIRCLEHOLLOWPROFILEDEF" -> {
                val r = num(3)
                List(16) { val t = 2 * PI * it / 16; d2(r * cos(t), r * sin(t)) }
            }
            "IFCLSHAPEPROFILEDEF" -> {
                val d = num(3) / 2; val w = (if (a.getOrNull(4) is Number) num(4) else num(3)) / 2; val t = num(5)
                listOf(d2(-w, -d), d2(w, -d), d2(w, -d + t), d2(-w + t, -d + t), d2(-w + t, d), d2(-w, d))
            }
            "IFCARBITRARYCLOSEDPROFILEDEF", "IFCARBITRARYPROFILEDEFWITHVOIDS" ->
                return polyline(entAt(a, 2))
            else -> return null
        }
        val p2 = placement2D(a.getOrNull(2))
        return pts.map { val r = app(p2, it[0], it[1], 0.0); d2(r[0], r[1]) }
    }

    // ---------- هندسه ----------
    private fun addItem(e: Ent?, xf: DoubleArray, g: Geo, depth: Int) {
        if (e == null || depth > 6) return
        val a = e.args
        when (e.name) {
            "IFCEXTRUDEDAREASOLID" -> extrude(a, xf, g)
            "IFCFACETEDBREP", "IFCFACETEDBREPWITHVOIDS" -> brep(a, xf, g)
            "IFCTRIANGULATEDFACESET" -> triSet(a, xf, g)
            "IFCMAPPEDITEM" -> mapped(a, xf, g, depth)
            "IFCBOOLEANCLIPPINGRESULT", "IFCBOOLEANRESULT" -> addItem(entAt(a, 1), xf, g, depth + 1)
        }
    }

    private fun extrude(a: List<Any?>, xf: DoubleArray, g: Geo) {
        val prof = profile(entAt(a, 0)) ?: return
        val pos = mul(xf, placement3D(a.getOrNull(1)))
        val dir = if (a.getOrNull(2) is Ref) norm(pt(a[2])) else doubleArrayOf(0.0, 0.0, 1.0)
        val d = (a.getOrNull(3) as? Number)?.toDouble() ?: return
        val poly = ArrayList(prof)
        if (area(poly) < 0) poly.reverse()
        val n = poly.size
        val b = Array(n) { app(pos, poly[it][0], poly[it][1], 0.0) }
        val t = Array(n) { app(pos, poly[it][0] + dir[0] * d, poly[it][1] + dir[1] * d, dir[2] * d) }
        for (i in 0 until n) {
            val j = (i + 1) % n
            g.edge(b[i], b[j]); g.edge(t[i], t[j]); g.edge(b[i], t[i])
            g.tri(b[i], b[j], t[j]); g.tri(b[i], t[j], t[i])
        }
        val idx = earClip(poly)
        var k = 0
        while (k + 2 < idx.size) {
            g.tri(b[idx[k]], b[idx[k + 1]], b[idx[k + 2]])
            g.tri(t[idx[k]], t[idx[k + 1]], t[idx[k + 2]])
            k += 3
        }
    }

    private fun polygon3D(pts: List<DoubleArray>, g: Geo) {
        val n = pts.size
        if (n < 3) return
        for (i in 0 until n) g.edge(pts[i], pts[(i + 1) % n])
        if (n == 3) { g.tri(pts[0], pts[1], pts[2]); return }
        // نرمال نیوول
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        for (i in 0 until n) {
            val p = pts[i]; val q = pts[(i + 1) % n]
            nx += (p[1] - q[1]) * (p[2] + q[2])
            ny += (p[2] - q[2]) * (p[0] + q[0])
            nz += (p[0] - q[0]) * (p[1] + q[1])
        }
        val ax = abs(nx); val ay = abs(ny); val az = abs(nz)
        val p2 = pts.map {
            when {
                ax >= ay && ax >= az -> d2(it[1], it[2])
                ay >= ax && ay >= az -> d2(it[2], it[0])
                else -> d2(it[0], it[1])
            }
        }
        val order = (0 until n).toMutableList()
        var list2 = p2
        if (area(p2) < 0) { order.reverse(); list2 = order.map { p2[it] } }
        val idx = earClip(list2)
        var k = 0
        while (k + 2 < idx.size) {
            g.tri(pts[order[idx[k]]], pts[order[idx[k + 1]]], pts[order[idx[k + 2]]])
            k += 3
        }
    }

    private fun brep(a: List<Any?>, xf: DoubleArray, g: Geo) {
        val shell = entAt(a, 0) ?: return
        val faces = shell.args.getOrNull(0) as? List<*> ?: return
        for (fr in faces) {
            val f = (fr as? Ref)?.let { ents[it.id] } ?: continue
            val bounds = f.args.getOrNull(0) as? List<*> ?: continue
            var outer: Ent? = null
            for (br in bounds) {
                val b = (br as? Ref)?.let { ents[it.id] } ?: continue
                if (b.name == "IFCFACEOUTERBOUND") { outer = b; break }
                if (outer == null) outer = b
            }
            val ob = outer ?: continue
            val loop = entAt(ob.args, 0) ?: continue
            val pr = loop.args.getOrNull(0) as? List<*> ?: continue
            val pts = ArrayList<DoubleArray>()
            for (r in pr) { val p = pt(r); pts.add(app(xf, p[0], p[1], p[2])) }
            polygon3D(pts, g)
        }
    }

    private fun triSet(a: List<Any?>, xf: DoubleArray, g: Geo) {
        val cl = entAt(a, 0) ?: return
        val coords = cl.args.getOrNull(0) as? List<*> ?: return
        val pts = ArrayList<DoubleArray>(coords.size)
        for (c in coords) {
            val l = c as? List<*> ?: continue
            pts.add(app(xf, (l[0] as Number).toDouble(), (l[1] as Number).toDouble(), (l[2] as Number).toDouble()))
        }
        val tl = a.getOrNull(3) as? List<*> ?: return
        val first = HashMap<Long, DoubleArray>()
        val shared = HashSet<Long>()
        for (t in tl) {
            val l = t as? List<*> ?: continue
            if (l.size < 3) continue
            val i0 = (l[0] as Number).toInt() - 1
            val i1 = (l[1] as Number).toInt() - 1
            val i2 = (l[2] as Number).toInt() - 1
            if (i0 !in pts.indices || i1 !in pts.indices || i2 !in pts.indices) continue
            val p0 = pts[i0]; val p1 = pts[i1]; val p2 = pts[i2]
            g.tri(p0, p1, p2)
            val nrm = norm(cross(
                doubleArrayOf(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]),
                doubleArrayOf(p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2])
            ))
            val ed = arrayOf(intArrayOf(i0, i1), intArrayOf(i1, i2), intArrayOf(i2, i0))
            for (e in ed) {
                val u = min(e[0], e[1]).toLong(); val v = max(e[0], e[1]).toLong()
                val key = (u shl 32) or v
                val prev = first[key]
                if (prev == null) first[key] = nrm
                else {
                    shared.add(key)
                    if (dot(prev, nrm) < 0.9) g.edge(pts[u.toInt()], pts[v.toInt()])
                }
            }
        }
        for ((key, _) in first) {
            if (key !in shared) g.edge(pts[(key shr 32).toInt()], pts[(key and 0xFFFFFFFFL).toInt()])
        }
    }

    private fun mapped(a: List<Any?>, xf: DoubleArray, g: Geo, depth: Int) {
        val src = entAt(a, 0) ?: return
        val tgt = entAt(a, 1)
        val origin = placement3D(src.args.getOrNull(0))
        val rep = entAt(src.args, 1) ?: return
        val t = if (tgt != null) cto(tgt) else ident()
        val x2 = mul(xf, mul(t, inv(origin)))
        val items = rep.args.getOrNull(3) as? List<*> ?: return
        for (ir in items) addItem((ir as? Ref)?.let { ents[it.id] }, x2, g, depth + 1)
    }

    // ---------- ساخت مدل ----------
    private class Raw(val name: String, val hole: Boolean, val e: FloatArray, val t: FloatArray)

    private fun build(): IfcModel {
        val prodNames = setOf(
            "IFCBEAM", "IFCCOLUMN", "IFCPLATE", "IFCMEMBER", "IFCMECHANICALFASTENER", "IFCFASTENER",
            "IFCBUILDINGELEMENTPROXY", "IFCOPENINGELEMENT", "IFCDISCRETEACCESSORY", "IFCSLAB", "IFCWALL"
        )
        val holeNames = setOf("IFCOPENINGELEMENT", "IFCMECHANICALFASTENER", "IFCFASTENER")
        val raws = ArrayList<Raw>()
        val keys = ents.entries.filter { it.value.name in prodNames }
        var cnt = 0
        for ((id, e) in keys) {
            if (++cnt % 50 == 0) progress("ساخت هندسه… $cnt / ${keys.size}")
            try {
                val a = e.args
                val repDef = entAt(a, 6) ?: continue
                if (repDef.name != "IFCPRODUCTDEFINITIONSHAPE") continue
                val xf = localPlacement(a.getOrNull(5))
                val g = Geo(scale)
                val reps = repDef.args.getOrNull(2) as? List<*> ?: continue
                for (rr in reps) {
                    val rep = (rr as? Ref)?.let { ents[it.id] } ?: continue
                    val rid = rep.args.getOrNull(1) as? String
                    if (rid == "Axis" || rid == "Box" || rid == "FootPrint") continue
                    val items = rep.args.getOrNull(3) as? List<*> ?: continue
                    for (ir in items) addItem((ir as? Ref)?.let { ents[it.id] }, xf, g, 0)
                }
                if (g.edges.n == 0) continue
                val nm = (a.getOrNull(2) as? String)?.takeIf { it.isNotBlank() } ?: "#$id"
                raws.add(Raw(nm, e.name in holeNames, g.edges.toArray(), g.tris.toArray()))
            } catch (ex: Exception) {
                // قطعه‌ی خراب را رد می‌کنیم
            }
        }
        if (raws.isEmpty()) throw IllegalStateException("هیچ هندسه‌ی قابل‌نمایشی در فایل پیدا نشد")

        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (r in raws) {
            var i = 0
            while (i + 2 < r.e.size) {
                for (k in 0..2) { mn[k] = min(mn[k], r.e[i + k]); mx[k] = max(mx[k], r.e[i + k]) }
                i += 3
            }
        }
        fun shift(arr: FloatArray) { for (i in arr.indices) arr[i] -= mn[i % 3] }
        val members = ArrayList<Member>(raws.size)
        for (r in raws) {
            shift(r.e); shift(r.t)
            members.add(Member(r.name, r.hole, r.e, r.t, samplesOf(r.e, r.t)))
        }
        return IfcModel(members, floatArrayOf(mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2]))
    }

    private fun samplesOf(edges: FloatArray, tris: FloatArray): FloatArray {
        val out = FList(512)
        val maxN = 150
        if (tris.size >= 9) {
            val nt = tris.size / 9
            val step = max(1, nt / maxN)
            var t = 0
            while (t < nt) {
                val o = t * 9
                out.add(
                    (tris[o] + tris[o + 3] + tris[o + 6]) / 3f,
                    (tris[o + 1] + tris[o + 4] + tris[o + 7]) / 3f,
                    (tris[o + 2] + tris[o + 5] + tris[o + 8]) / 3f
                )
                t += step
            }
        } else {
            val ne = edges.size / 6
            val step = max(1, ne / maxN)
            var t = 0
            while (t < ne) {
                val o = t * 6
                out.add((edges[o] + edges[o + 3]) / 2f, (edges[o + 1] + edges[o + 4]) / 2f, (edges[o + 2] + edges[o + 5]) / 2f)
                t += step
            }
        }
        return out.toArray()
    }
}
