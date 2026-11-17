package ir.fabsteel.arqc

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class QcRow(val name: String, val isHole: Boolean, val devMm: Float, val tolMm: Float, val ok: Boolean)

class QcResult(
    val evaluated: Int,
    val okCount: Int,
    val mismatch: Int,
    val worstDevMm: Float,
    val worstName: String,
    val noiseLimited: Boolean,
    val rows: List<QcRow>
) {
    val percent: Int get() = if (evaluated == 0) 0 else okCount * 100 / evaluated
}

class ArRenderer(private val ctx: Context, private val listener: Listener) : GLSurfaceView.Renderer {

    interface Listener {
        fun onStatus(tracking: Boolean, quality: Int, depthOk: Boolean, hasHit: Boolean)
        fun onQc(r: QcResult)
        fun onSnapshot(bmp: Bitmap)
        fun onFixed(fixed: Boolean)
        /** distMm >= 0: فاصله‌ی دو نقطه ؛ -1: هنوز نقطه‌ی دوم لازم است ؛ -2: سطحی زیر انگشت پیدا نشد */
        fun onMeasure(distMm: Float, count: Int)
    }

    // ---- ابزارهای فیلم: برش، شفافیت، متر ----
    @Volatile var fillAlpha = 0.35f
    @Volatile var clipOn = false
    @Volatile var clipAxis = 1             // 0=X ، 1=Y (بالا) ، 2=Z ؛ در دستگاه مختصات خود مدل
    @Volatile var clipPos = 1f             // 0..1 از ابعاد مدل روی همان محور
    @Volatile var clipFlip = false
    @Volatile var tapX = -1f
    @Volatile var tapY = -1f
    private val mPts = ArrayList<FloatArray>()
    private val mBuf: FloatBuffer = fb(FloatArray(48))
    private var lnAxis = 0
    private var lnD = 0
    private var lnClipOn = 0

    fun clearMeasure() {
        synchronized(mPts) { mPts.clear() }
        listener.onMeasure(-1f, 0)
    }

    @Volatile var session: Session? = null
    @Volatile var tolMemberMm = 5f
    @Volatile var tolHoleMm = 3f
    @Volatile var noisePct = 1.0f          // حد نویز حسگر عمق، درصد فاصله
    @Volatile var yawDeg = 0f
    @Volatile var fixRequest = 0           // 1 = فیکس ، 2 = آزاد
    @Volatile var snapRequest = false
    @Volatile var depthEnabled = false
    private var pendingModel: IfcModel? = null
    private var clearModel = false

    // ---- GL ----
    private var camTex = 0
    private var bgProg = 0
    private var lineProg = 0
    private var bgPos = 0
    private var bgUv = 0
    private var lnPos = 0
    private var lnMvp = 0
    private var lnColor = 0
    private val quad: FloatBuffer = fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uvBuf: FloatBuffer = fb(FloatArray(8))
    private val quadArr = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uvArr = FloatArray(8)
    private var vw = 1
    private var vh = 1
    private var boundSession: Session? = null

    // همه‌ی قطعات در دو بافر بزرگ (خطوط و مثلث‌ها) هستند؛ هر قطعه فقط محدوده‌ی خودش را دارد.
    private class Gpu(val m: Member, val firstE: Int, val nE: Int, val firstT: Int, val nT: Int) {
        @Volatile var red = false
        var dev = -1f              // اختلاف میانه (mm)؛ منفی = هنوز بررسی نشده
        var tol = 0f
        var noiseLimited = false
    }

    private var gpu: List<Gpu> = emptyList()
    private var bigE = 0
    private var bigT = 0
    private var totE = 0
    private var totT = 0
    private var qcCursor = 0

    // ---- وضعیت AR ----
    private var anchor: Anchor? = null
    private var fixed = false
    private var lastHit: Pose? = null
    private var lastQcMs = 0L
    private var lastStatusMs = 0L

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mdl = FloatArray(16)
    private val mvp = FloatArray(16)
    private val mv = FloatArray(16)

    fun setModel(m: IfcModel?) { synchronized(this) { pendingModel = m; clearModel = m == null } }

    private fun fb(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        camTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        bgProg = program(
            "attribute vec4 a_Position; attribute vec2 a_Uv; varying vec2 v_Uv; void main(){ gl_Position=a_Position; v_Uv=a_Uv; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v_Uv; uniform samplerExternalOES s; void main(){ gl_FragColor=texture2D(s,v_Uv); }"
        )
        bgPos = GLES20.glGetAttribLocation(bgProg, "a_Position")
        bgUv = GLES20.glGetAttribLocation(bgProg, "a_Uv")
        lineProg = program(
            "uniform mat4 u_Mvp; attribute vec3 a_Pos; varying vec3 v_Local; " +
                "void main(){ v_Local=a_Pos; gl_Position=u_Mvp*vec4(a_Pos,1.0); }",
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
                "uniform vec4 u_Color; uniform vec3 u_Axis; uniform float u_D; uniform float u_ClipOn; varying vec3 v_Local; " +
                "void main(){ if(u_ClipOn>0.5 && dot(v_Local,u_Axis)>u_D) discard; gl_FragColor=u_Color; }"
        )
        lnPos = GLES20.glGetAttribLocation(lineProg, "a_Pos")
        lnMvp = GLES20.glGetUniformLocation(lineProg, "u_Mvp")
        lnColor = GLES20.glGetUniformLocation(lineProg, "u_Color")
        lnAxis = GLES20.glGetUniformLocation(lineProg, "u_Axis")
        lnD = GLES20.glGetUniformLocation(lineProg, "u_D")
        lnClipOn = GLES20.glGetUniformLocation(lineProg, "u_ClipOn")
        boundSession = null
        gpu = emptyList()                   // بافرهای قبلی با از دست رفتن context بی‌اعتبارند
        bigE = 0; bigT = 0; totE = 0; totT = 0
        synchronized(this) { if (ModelStore.model != null && pendingModel == null) pendingModel = ModelStore.model }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        vw = width; vh = height
        GLES20.glViewport(0, 0, width, height)
        boundSession = null
    }

    @Suppress("DEPRECATION")
    private fun rotation(): Int =
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay?.rotation ?: Surface.ROTATION_90

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        uploadPending()
        val s = session ?: return
        if (boundSession !== s) {
            s.setCameraTextureName(camTex)
            s.setDisplayGeometry(rotation(), vw, vh)
            boundSession = s
        }
        val frame: Frame = try { s.update() } catch (e: Exception) { return }
        val cam = frame.camera
        if (frame.timestamp != 0L) drawBackground(frame)

        val tracking = cam.trackingState == TrackingState.TRACKING
        val now = System.currentTimeMillis()

        if (tracking) {
            if (!fixed) {
                var hp: Pose? = null
                for (h in frame.hitTest(vw / 2f, vh / 2f)) {
                    val tr = h.trackable
                    if ((tr is Plane && tr.isPoseInPolygon(h.hitPose)) || tr is Point || tr is DepthPoint) {
                        hp = h.hitPose; break
                    }
                }
                lastHit = hp
            }
            val fr = fixRequest
            if (fr == 1 && !fixed && lastHit != null) {
                anchor?.detach()
                anchor = s.createAnchor(Pose(lastHit!!.translation, floatArrayOf(0f, 0f, 0f, 1f)))
                fixed = true
                listener.onFixed(true)
            } else if (fr == 2 && fixed) {
                anchor?.detach(); anchor = null; fixed = false
                resetStates()
                listener.onFixed(false)
            }
            fixRequest = 0
        } else fixRequest = 0

        if (now - lastStatusMs > 250) {
            lastStatusMs = now
            var q = 0
            if (tracking) {
                val pc = frame.acquirePointCloud()
                try { q = minOf(100, pc.points.remaining() / 4 * 100 / 120) } finally { pc.release() }
            }
            listener.onStatus(tracking, q, depthEnabled, lastHit != null || fixed)
        }

        val base: FloatArray? = when {
            fixed && anchor?.trackingState == TrackingState.TRACKING -> anchor!!.pose.translation
            !fixed -> lastHit?.translation
            else -> null
        }
        if (!tracking) { maybeSnap(); return }

        cam.getProjectionMatrix(proj, 0, 0.05f, 50f)
        cam.getViewMatrix(view, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        handleTap(frame)

        if (base != null && gpu.isNotEmpty()) {
            Matrix.setIdentityM(mdl, 0)
            Matrix.translateM(mdl, 0, base[0], base[1], base[2])
            Matrix.rotateM(mdl, 0, yawDeg, 0f, 1f, 0f)
            Matrix.multiplyMM(mvp, 0, vp, 0, mdl, 0)
            Matrix.multiplyMM(mv, 0, view, 0, mdl, 0)
            drawModel()
            if (fixed && depthEnabled && now - lastQcMs > 500) {
                lastQcMs = now
                runQc(frame)
            }
        }
        drawMeasure()
        maybeSnap()
    }

    // ---------- ابزار متر ----------
    private fun handleTap(frame: Frame) {
        val x = tapX
        val y = tapY
        if (x < 0f) return
        tapX = -1f; tapY = -1f
        for (h in frame.hitTest(x, y)) {
            val tr = h.trackable
            if ((tr is Plane && tr.isPoseInPolygon(h.hitPose)) || tr is Point || tr is DepthPoint) {
                val t = h.hitPose.translation
                var d = -1f
                var n = 0
                synchronized(mPts) {
                    if (mPts.size >= 2) mPts.clear()
                    mPts.add(floatArrayOf(t[0], t[1], t[2]))
                    n = mPts.size
                    if (n == 2) {
                        val a = mPts[0]; val b = mPts[1]
                        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
                        d = sqrt(dx * dx + dy * dy + dz * dz) * 1000f
                    }
                }
                listener.onMeasure(d, n)
                return
            }
        }
        listener.onMeasure(-2f, synchronized(mPts) { mPts.size })
    }

    private fun drawMeasure() {
        val pts = synchronized(mPts) { ArrayList(mPts) }
        if (pts.isEmpty()) return
        val a = FloatArray(48)
        var n = 0
        fun add(x: Float, y: Float, z: Float) { a[n++] = x; a[n++] = y; a[n++] = z }
        val r = 0.02f
        for (p in pts) {
            add(p[0] - r, p[1], p[2]); add(p[0] + r, p[1], p[2])
            add(p[0], p[1] - r, p[2]); add(p[0], p[1] + r, p[2])
            add(p[0], p[1], p[2] - r); add(p[0], p[1], p[2] + r)
        }
        if (pts.size == 2) {
            add(pts[0][0], pts[0][1], pts[0][2]); add(pts[1][0], pts[1][1], pts[1][2])
        }
        mBuf.position(0); mBuf.put(a, 0, n); mBuf.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(lineProg)
        GLES20.glUniformMatrix4fv(lnMvp, 1, false, vp, 0)
        GLES20.glUniform1f(lnClipOn, 0f)
        GLES20.glUniform4f(lnColor, 1f, 0.85f, 0.1f, 1f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(lnPos)
        GLES20.glVertexAttribPointer(lnPos, 3, GLES20.GL_FLOAT, false, 0, mBuf)
        GLES20.glLineWidth(5f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, n / 3)
        GLES20.glDisableVertexAttribArray(lnPos)
    }

    // ---------- پس‌زمینه ----------
    private fun drawBackground(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadArr,
                Coordinates2d.TEXTURE_NORMALIZED, uvArr
            )
            uvBuf.position(0); uvBuf.put(uvArr); uvBuf.position(0)
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        quad.position(0)
        GLES20.glVertexAttribPointer(bgPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(bgUv, 2, GLES20.GL_FLOAT, false, 0, uvBuf)
        GLES20.glEnableVertexAttribArray(bgPos)
        GLES20.glEnableVertexAttribArray(bgUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(bgPos)
        GLES20.glDisableVertexAttribArray(bgUv)
        GLES20.glDepthMask(true)
    }

    // ---------- مدل ----------
    private fun uploadPending() {
        var tmp: IfcModel? = null
        var clr = false
        synchronized(this) { tmp = pendingModel; clr = clearModel; pendingModel = null; clearModel = false }
        val pm: IfcModel? = tmp
        if (pm == null && !clr) return
        freeGpu()
        fixed = false; anchor?.detach(); anchor = null
        if (pm == null) return
        var te = 0
        var tt = 0
        for (m in pm.members) { te += m.edges.size; tt += m.tris.size }
        val allE = FloatArray(te)
        val allT = FloatArray(tt)
        val list = ArrayList<Gpu>(pm.members.size)
        var oe = 0
        var ot = 0
        for (m in pm.members) {
            System.arraycopy(m.edges, 0, allE, oe, m.edges.size)
            System.arraycopy(m.tris, 0, allT, ot, m.tris.size)
            list.add(Gpu(m, oe / 3, m.edges.size / 3, ot / 3, m.tris.size / 3))
            oe += m.edges.size
            ot += m.tris.size
        }
        bigE = vbo(allE); bigT = vbo(allT)
        totE = te / 3; totT = tt / 3
        qcCursor = 0
        gpu = list
    }

    private fun vbo(a: FloatArray): Int {
        if (a.isEmpty()) return 0
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, a.size * 4, fb(a), GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    private fun freeGpu() {
        if (bigE != 0 || bigT != 0) GLES20.glDeleteBuffers(2, intArrayOf(bigE, bigT), 0)
        bigE = 0; bigT = 0; totE = 0; totT = 0
        gpu = emptyList()
    }

    private fun resetStates() { for (g in gpu) { g.red = false; g.dev = -1f } }

    private fun drawModel() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(lineProg)
        GLES20.glUniformMatrix4fv(lnMvp, 1, false, mvp, 0)
        GLES20.glEnableVertexAttribArray(lnPos)
        // صفحه‌ی برش (در مختصات خود مدل)
        val sz = ModelStore.model?.sizeM
        if (clipOn && sz != null) {
            val sign = if (clipFlip) -1f else 1f
            val ax = clipAxis.coerceIn(0, 2)
            GLES20.glUniform3f(lnAxis, if (ax == 0) sign else 0f, if (ax == 1) sign else 0f, if (ax == 2) sign else 0f)
            GLES20.glUniform1f(lnD, sign * clipPos * sz[ax])
            GLES20.glUniform1f(lnClipOn, 1f)
        } else {
            GLES20.glUniform1f(lnClipOn, 0f)
        }
        val fa = fillAlpha
        // ۱) همه‌ی قطعات آبی، با دو فراخوانی رسم (مثلث‌ها و خطوط)
        if (bigT != 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bigT)
            GLES20.glVertexAttribPointer(lnPos, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glUniform4f(lnColor, 0.15f, 0.45f, 0.95f, fa)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, totT)
        }
        if (bigE != 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bigE)
            GLES20.glVertexAttribPointer(lnPos, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glLineWidth(6f)
            GLES20.glUniform4f(lnColor, 0.15f, 0.55f, 1f, 0.28f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, totE)
            GLES20.glLineWidth(2.5f)
            GLES20.glUniform4f(lnColor, 0.15f, 0.55f, 1f, 1f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, totE)
        }
        // ۲) قطعات مغایر: فقط محدوده‌ی خودشان، قرمز و ضخیم‌تر روی آبی
        for (g in gpu) {
            if (!g.red) continue
            if (bigT != 0 && g.nT > 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bigT)
                GLES20.glVertexAttribPointer(lnPos, 3, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glUniform4f(lnColor, 1f, 0.12f, 0.12f, minOf(0.85f, fa + 0.2f))
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, g.firstT, g.nT)
            }
            if (bigE != 0 && g.nE > 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bigE)
                GLES20.glVertexAttribPointer(lnPos, 3, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glLineWidth(9f)
                GLES20.glUniform4f(lnColor, 1f, 0.12f, 0.12f, 0.40f)
                GLES20.glDrawArrays(GLES20.GL_LINES, g.firstE, g.nE)
                GLES20.glLineWidth(3.5f)
                GLES20.glUniform4f(lnColor, 1f, 0.12f, 0.12f, 1f)
                GLES20.glDrawArrays(GLES20.GL_LINES, g.firstE, g.nE)
            }
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(lnPos)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ---------- کنترل QC با نقشه‌ی عمق ----------
    private fun runQc(frame: Frame) {
        val img = try { frame.acquireDepthImage16Bits() } catch (e: Exception) { return }
        try {
            val w = img.width; val h = img.height
            val plane = img.planes[0]
            val buf = plane.buffer.order(ByteOrder.nativeOrder())
            val rs = plane.rowStride; val ps = plane.pixelStride

            val inPts = floatArrayOf(0f, 0f, vw.toFloat(), 0f, 0f, vh.toFloat())
            val o = FloatArray(6)
            frame.transformCoordinates2d(Coordinates2d.VIEW, inPts, Coordinates2d.IMAGE_NORMALIZED, o)
            val dux = (o[2] - o[0]) / vw; val duy = (o[4] - o[0]) / vh
            val dvx = (o[3] - o[1]) / vw; val dvy = (o[5] - o[1]) / vh

            val rows = ArrayList<QcRow>()
            var ok = 0; var bad = 0; var worst = 0f; var worstName = ""; var noiseLimited = false
            val diffs = FloatArray(160)

            // در مدل‌های بزرگ در هر دور فقط حدود ۴۰۰ قطعه بررسی می‌شود و دورها می‌چرخند
            val total = gpu.size
            val step = max(1, total / 400)
            val startIdx = if (step > 1) qcCursor++ % step else 0
            var gi = startIdx
            while (gi < total) {
                val g = gpu[gi]
                gi += step
                val sm = g.m.samples
                var cnt = 0
                var zSum = 0f
                var i = 0
                while (i + 2 < sm.size && cnt < diffs.size) {
                    val x = sm[i]; val y = sm[i + 1]; val z = sm[i + 2]
                    i += 3
                    val cx = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
                    val cy = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
                    val cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
                    if (cw <= 0.05f) continue
                    val px = (cx / cw * 0.5f + 0.5f) * vw
                    val py = (1f - (cy / cw * 0.5f + 0.5f)) * vh
                    if (px < 0 || py < 0 || px >= vw || py >= vh) continue
                    val ez = mv[2] * x + mv[6] * y + mv[10] * z + mv[14]
                    val zMm = -ez * 1000f
                    if (zMm < 300f || zMm > 6000f) continue
                    val u = o[0] + px * dux + py * duy
                    val v = o[1] + px * dvx + py * dvy
                    val iu = (u * w).toInt().coerceIn(0, w - 1)
                    val iv = (v * h).toInt().coerceIn(0, h - 1)
                    val d = buf.getShort(iv * rs + iu * ps).toInt() and 0xFFFF
                    if (d == 0) continue
                    diffs[cnt++] = abs(d - zMm)
                    zSum += zMm
                }
                if (cnt < 6) { g.red = false; g.dev = -1f; continue }
                java.util.Arrays.sort(diffs, 0, cnt)
                val med = diffs[cnt / 2]
                if (med > 300f) { g.red = false; g.dev = -1f; continue }   // پشت مانع یا خارج از برد معتبر
                val userTol = if (g.m.isHole) tolHoleMm else tolMemberMm
                val noise = noisePct / 100f * (zSum / cnt)
                g.tol = max(userTol, noise)
                g.noiseLimited = noise > userTol
                g.dev = med
                g.red = med > g.tol
            }
            // جمع‌بندی روی همه‌ی قطعاتی که تا الان بررسی شده‌اند (نه فقط این دور)
            for (g in gpu) {
                if (g.dev < 0f) continue
                if (g.noiseLimited) noiseLimited = true
                if (g.red) { bad++; if (g.dev > worst) { worst = g.dev; worstName = g.m.name } } else ok++
                rows.add(QcRow(g.m.name, g.m.isHole, g.dev, g.tol, !g.red))
            }
            listener.onQc(QcResult(ok + bad, ok, bad, worst, worstName, noiseLimited, rows))
        } finally {
            img.close()
        }
    }

    // ---------- عکس QC ----------
    private fun maybeSnap() {
        if (!snapRequest) return
        snapRequest = false
        val buf = ByteBuffer.allocateDirect(vw * vh * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, vw, vh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val px = IntArray(vw * vh)
        buf.asIntBuffer().get(px)
        // RGBA(byte) → ARGB ، و وارونه‌کردن عمودی
        val out = IntArray(vw * vh)
        for (y in 0 until vh) {
            for (x in 0 until vw) {
                val c = px[y * vw + x]
                val r = c and 0xFF
                val gg = (c shr 8) and 0xFF
                val b = (c shr 16) and 0xFF
                out[(vh - 1 - y) * vw + x] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
            }
        }
        listener.onSnapshot(Bitmap.createBitmap(out, vw, vh, Bitmap.Config.ARGB_8888))
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }
}
