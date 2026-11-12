package com.hcode91.uv2k

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Front photo + side photo → face texture in a chosen UV layout, in three steps:
 * 1 Layout (the standard face layout, or one learned from a reference texture of your game's head),
 * 2 Photos (faces are found automatically; pins can be fixed if needed), 3 Texture (preview and save).
 */
class MainActivity : ComponentActivity() {

    private companion object {
        const val LAYOUT = 0; const val PHOTOS = 1; const val RESULT = 2
        const val FOLDER = "UV-2K"
        val SIZES = intArrayOf(0, 1024, 2048, 4096) // 0 = automatic
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var detector: LandmarkDetector? = null
    private lateinit var topo: FaceTopology
    private lateinit var mirror: IntArray

    private lateinit var steps: StepBar
    private lateinit var status: TextView
    private lateinit var layoutPane: LinearLayout
    private lateinit var refTile: PhotoTile
    private lateinit var standardNote: TextView
    private lateinit var refView: PinEditorView
    private lateinit var photosPane: View
    private lateinit var frontTile: PhotoTile
    private lateinit var sideTile: PhotoTile
    private lateinit var frontView: PinEditorView
    private lateinit var sideMeshView: PinEditorView
    private lateinit var sidePinView: PointPinView
    private lateinit var resultView: ImageView
    private val optionRows = ArrayList<View>()
    private lateinit var standardButton: Button
    private lateinit var gameButton: Button
    private lateinit var resetLayoutButton: Button
    private lateinit var fixFrontButton: Button
    private lateinit var fixSideButton: Button
    private lateinit var flipButton: Button
    private lateinit var resetPinsButton: Button
    private lateinit var outsideButton: Button
    private lateinit var sizeButton: Button
    private lateinit var sideUseButton: Button
    private lateinit var backButton: Button
    private lateinit var nextButton: Button

    private var step = LAYOUT
    private var gameLayout = false
    private var ref: Bitmap? = null
    private var front: Bitmap? = null
    private var side: Bitmap? = null
    private var sideDetected = false
    private var sidePinsPlaced = false
    /** 0 = showing the photo tiles, 1 = fixing front pins, 2 = fixing side pins. */
    private var fixing = 0
    /** 0 = reference texture, 1 = skin colour, 2 = transparent. */
    private var outside = 0
    private var sizeChoice = 0
    private var useSide = true
    private var result: Bitmap? = null
    private var busy = false

    private val imageOnly get() = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    private val pickRef = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadReference(uri) }
    private val pickFront = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) loadFront(uri) }
    private val pickSide = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) loadSide(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        topo = FaceTopology.load(this)
        mirror = UvBaker.mirrorMap(topo.canon)
        buildUi()
        setStep(LAYOUT)
        restoreLayout()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        root.addView(Ui.titleBar(this, "UV-2K face texture", showBack = false))
        steps = StepBar(this, listOf("Layout", "Photos", "Texture")) { setStep(it) }
        root.addView(steps)
        status = Ui.hint(this)
        root.addView(status)

        val stage = FrameLayout(this)
        val full = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        // ---- step 1: layout
        refTile = PhotoTile(this, "Reference texture", "One existing face texture from your game, as a PNG or JPG") { pickRef.launch(arrayOf("image/*")) }
        standardNote = Ui.hint(this).apply {
            text = "Standard layout: the face is unwrapped flat, centred in a square image. It matches heads made in FaceDroid, not a game's own head.\n\n" +
                "For a game: switch to “My game's layout” and choose any one face texture exported from that game. The app works out where the eyes, nose and mouth sit in it, and every texture you make will line up the same way. You only do this once."
        }
        refView = PinEditorView(this).apply { topology = topo; onChanged = { result = null; saveLayout() } }
        layoutPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(refTile); addView(standardNote)
            addView(refView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        stage.addView(layoutPane, full)
        // ---- step 2: photos
        frontTile = PhotoTile(this, "Front photo", "Needed – face looking straight at the camera") { pickFront.launch(imageOnly) }
        sideTile = PhotoTile(this, "Side photo", "Recommended – fills in the cheeks, temples and jaw") { pickSide.launch(imageOnly) }
        photosPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(frontTile); addView(sideTile)
            addView(Ui.hint(context).apply { text = "Tips: even lighting with no hard shadows, neutral expression, hair off the face. The side photo should be the same person, turned to the side; either side is fine and it's mirrored for the other." })
        }
        stage.addView(photosPane, full)
        frontView = PinEditorView(this).apply { topology = topo; onChanged = { result = null } }
        sideMeshView = PinEditorView(this).apply { topology = topo; onChanged = { result = null } }
        sidePinView = PointPinView(this).apply { onChanged = { sidePinsPlaced = true; result = null; sideTile.set(side, "Chosen ✓ – pins placed by hand") } }
        stage.addView(frontView, full); stage.addView(sideMeshView, full); stage.addView(sidePinView, full)
        // ---- step 3: texture
        resultView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.rgb(40, 40, 44)) }
        stage.addView(resultView, full)
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- options for each step
        fun options(build: LinearLayout.() -> Unit): View = Ui.scroll(Ui.row(this).apply(build)).also { optionRows.add(it); root.addView(it) }
        options {
            standardButton = Ui.secondary(context, "Standard layout") { gameLayout = false; result = null; setStep(LAYOUT) }
            gameButton = Ui.secondary(context, "My game's layout") { gameLayout = true; result = null; setStep(LAYOUT) }
            resetLayoutButton = Ui.secondary(context, "Reset pins") { refView.resetPins() }
            addView(standardButton); addView(gameButton); addView(resetLayoutButton)
        }
        options {
            fixFrontButton = Ui.secondary(context, "Fix front pins") { fixing = if (fixing == 1) 0 else 1; setStep(PHOTOS) }
            fixSideButton = Ui.secondary(context, "Fix side pins") { fixing = if (fixing == 2) 0 else 2; setStep(PHOTOS) }
            flipButton = Ui.secondary(context, "Flip (face points the other way)") { side?.let { sidePinView.setPoints(SideProfile.flip(sidePinView.points, it.width)) } }
            resetPinsButton = Ui.secondary(context, "Reset pins") {
                when {
                    fixing == 1 -> frontView.resetPins()
                    fixing == 2 && sideDetected -> sideMeshView.resetPins()
                    fixing == 2 -> side?.let { sidePinView.setPoints(SideProfile.defaultPoints(it.width, it.height)) }
                }
            }
            addView(fixFrontButton); addView(fixSideButton); addView(flipButton); addView(resetPinsButton)
        }
        options {
            addView(Ui.label(context, "Around the face"))
            outsideButton = Ui.secondary(context, "") { outside = (outside + 1) % 3; result = null; showResult() }
            addView(outsideButton)
            addView(Ui.label(context, "  Size"))
            sizeButton = Ui.secondary(context, "") { sizeChoice = (sizeChoice + 1) % SIZES.size; result = null; showResult() }
            addView(sizeButton)
            addView(Ui.label(context, "  Side photo"))
            sideUseButton = Ui.secondary(context, "") { useSide = !useSide; result = null; showResult() }
            addView(sideUseButton)
        }

        val bottom = Ui.row(this).apply { setPadding(Ui.dp(context, 8f), Ui.dp(context, 2f), Ui.dp(context, 8f), Ui.dp(context, 8f)) }
        backButton = Ui.secondary(this, "‹ Back") { if (step == PHOTOS && fixing != 0) { fixing = 0; setStep(PHOTOS) } else setStep(step - 1) }
        nextButton = Ui.primary(this, "Next") {
            when {
                step == LAYOUT -> setStep(PHOTOS)
                step == PHOTOS && fixing != 0 -> { fixing = 0; setStep(PHOTOS) }
                step == PHOTOS -> setStep(RESULT)
                else -> save()
            }
        }
        bottom.addView(backButton)
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(nextButton)
        root.addView(bottom)
        setContentView(root)
    }

    private val layoutReady get() = !gameLayout || ref != null

    private fun setStep(target: Int) {
        var s = target.coerceIn(LAYOUT, RESULT)
        var note: String? = null
        if (s > LAYOUT && !layoutReady) { s = LAYOUT; note = "Choose a reference texture first, or switch to the standard layout." }
        else if (s == RESULT && front == null) { s = PHOTOS; note = "Choose a front photo first." }
        if (s != PHOTOS) fixing = 0
        if (fixing == 2 && side == null) fixing = 0
        if (fixing == 1 && front == null) fixing = 0
        step = s
        steps.select(s)
        steps.setUsable(PHOTOS, layoutReady); steps.setUsable(RESULT, layoutReady && front != null)

        layoutPane.visibility = if (s == LAYOUT) View.VISIBLE else View.GONE
        refTile.visibility = if (gameLayout) View.VISIBLE else View.GONE
        standardNote.visibility = if (gameLayout) View.GONE else View.VISIBLE
        refView.visibility = if (gameLayout && ref != null) View.VISIBLE else View.INVISIBLE
        Ui.setChecked(standardButton, !gameLayout); Ui.setChecked(gameButton, gameLayout)
        resetLayoutButton.visibility = if (gameLayout && ref != null) View.VISIBLE else View.GONE

        photosPane.visibility = if (s == PHOTOS && fixing == 0) View.VISIBLE else View.GONE
        frontView.visibility = if (s == PHOTOS && fixing == 1) View.VISIBLE else View.GONE
        sideMeshView.visibility = if (s == PHOTOS && fixing == 2 && sideDetected) View.VISIBLE else View.GONE
        sidePinView.visibility = if (s == PHOTOS && fixing == 2 && !sideDetected) View.VISIBLE else View.GONE
        fixFrontButton.visibility = if (front != null) View.VISIBLE else View.GONE
        fixSideButton.visibility = if (side != null) View.VISIBLE else View.GONE
        Ui.setChecked(fixFrontButton, fixing == 1); Ui.setChecked(fixSideButton, fixing == 2)
        flipButton.visibility = if (fixing == 2 && !sideDetected) View.VISIBLE else View.GONE
        resetPinsButton.visibility = if (fixing != 0) View.VISIBLE else View.GONE

        resultView.visibility = if (s == RESULT) View.VISIBLE else View.GONE
        for ((i, row) in optionRows.withIndex()) row.visibility = if (i == s) View.VISIBLE else View.GONE
        backButton.visibility = if (s == LAYOUT) View.INVISIBLE else View.VISIBLE
        nextButton.text = when {
            s == LAYOUT -> "Next: photos"
            s == PHOTOS && fixing != 0 -> "Done"
            s == PHOTOS -> "Next: make texture"
            else -> "Save PNG"
        }
        Ui.setUsable(nextButton, if (s == LAYOUT) layoutReady else front != null)

        status.text = note ?: when {
            s == LAYOUT && !gameLayout -> "Pick the layout your textures should follow."
            s == LAYOUT && ref == null -> "Choose one face texture from your game. You only need to do this once."
            s == LAYOUT -> "Check the mesh sits on the face in the reference: eyes on eyes, mouth on mouth. Drag the yellow pins if it's off. Pinch to zoom. Saved automatically."
            fixing == 1 -> "Drag any yellow pins that are off onto the eyes, nose, mouth and jaw. Pinch to zoom."
            fixing == 2 && sideDetected -> "Drag any pins that are off. Only the side facing the camera matters."
            fixing == 2 -> "Drag the 5 pins onto the nose tip, chin, top of head, back of head and ear."
            s == PHOTOS -> "Choose a front photo and a side photo. Faces are found automatically."
            else -> "Making the texture…"
        }
        if (s == RESULT) showResult()
    }

    // ---- loading -----------------------------------------------------------------------------------

    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = minOf(1f, maxSide.toFloat() / maxOf(info.size.width, info.size.height))
            if (s < 1f) dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun detect(bmp: Bitmap): FloatArray? = (detector ?: LandmarkDetector(this).also { detector = it }).detect(bmp)

    private fun load(uri: Uri, maxSide: Int, what: String, done: (Bitmap, FloatArray?) -> Unit) {
        if (busy) return
        busy = true
        status.text = "Finding the face in the $what…"
        worker.execute {
            try {
                val bmp = decode(uri, maxSide)
                val lm = detect(bmp)
                runOnUiThread { busy = false; result = null; done(bmp, lm) }
            } catch (e: Throwable) {
                runOnUiThread { busy = false; status.text = "Couldn't open that image: ${e.message}" }
            }
        }
    }

    private fun loadReference(uri: Uri) = load(uri, 4096, "reference texture") { bmp, lm ->
        ref = bmp; gameLayout = true
        refView.setFace(bmp, lm ?: LandmarkDetector.templateLandmarks(topo, bmp.width, bmp.height))
        refTile.set(bmp, if (lm != null) "Chosen ✓ – face found (${bmp.width}×${bmp.height})" else "Chosen – face not found, place the pins by hand")
        saveLayout(withImage = true)
        setStep(LAYOUT)
        if (lm == null) status.text = "The face wasn't found automatically in this texture, so a template was placed. Drag the yellow pins onto the eyes, nose, mouth and jaw in the texture."
    }

    private fun loadFront(uri: Uri) = load(uri, 2048, "front photo") { bmp, lm ->
        front = bmp
        frontView.setFace(bmp, lm ?: LandmarkDetector.templateLandmarks(topo, bmp.width, bmp.height))
        frontTile.set(bmp, if (lm != null) "Chosen ✓ – face found" else "Chosen – face not found, tap Fix front pins")
        fixing = 0
        setStep(PHOTOS)
        if (lm == null) status.text = "No face was found in the front photo. Tap “Fix front pins” and drag the pins onto the features, or try a clearer photo."
    }

    private fun loadSide(uri: Uri) = load(uri, 2048, "side photo") { bmp, lm ->
        side = bmp; sideDetected = lm != null; sidePinsPlaced = false
        if (lm != null) sideMeshView.setFace(bmp, lm)
        else sidePinView.set(bmp, SideProfile.PINS, SideProfile.defaultPoints(bmp.width, bmp.height), SideProfile.LINES)
        sideTile.set(bmp, if (lm != null) "Chosen ✓ – face found" else "Chosen – needs 5 pins, tap Fix side pins")
        fixing = 0
        setStep(PHOTOS)
        if (lm == null) status.text = "The face wasn't found automatically in the side photo (common with a full side-on view). Tap “Fix side pins” and place 5 pins, or it will be left out."
    }

    // ---- saved layout ------------------------------------------------------------------------------

    private val layoutImage get() = File(filesDir, "layout.png")
    private val layoutPoints get() = File(filesDir, "layout.txt")

    private fun saveLayout(withImage: Boolean = false) {
        val bmp = ref ?: return
        val lm = refView.current ?: return
        val pts = FloatArray(FaceTopology.N * 2) { lm[(it / 2) * 3 + it % 2] }
        worker.execute {
            try {
                if (withImage) layoutImage.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                layoutPoints.writeText(UvBaker.layoutToText(bmp.width, bmp.height, pts))
            } catch (e: Exception) { /* the layout still works for this session */ }
        }
    }

    private fun restoreLayout() {
        worker.execute {
            try {
                if (!layoutImage.exists() || !layoutPoints.exists()) return@execute
                val saved = UvBaker.layoutFromText(layoutPoints.readText()) ?: return@execute
                val bmp = BitmapFactory.decodeFile(layoutImage.path) ?: return@execute
                if (saved.third.size != FaceTopology.N * 2 || bmp.width != saved.first || bmp.height != saved.second) return@execute
                val lm = FloatArray(FaceTopology.N * 3) { k -> if (k % 3 == 2) 0f else saved.third[(k / 3) * 2 + k % 3] }
                runOnUiThread {
                    if (ref != null) return@runOnUiThread
                    ref = bmp; gameLayout = true
                    refView.setFace(bmp, lm)
                    refTile.set(bmp, "Saved layout ✓ (${bmp.width}×${bmp.height})")
                    setStep(LAYOUT)
                    status.text = "Your game's layout is loaded. Tap Next to add photos, or choose a different reference texture."
                }
            } catch (e: Exception) { /* start without a saved layout */ }
        }
    }

    // ---- baking ------------------------------------------------------------------------------------

    private fun image(b: Bitmap) = Image(b.width, b.height, IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) })

    private fun outSize(): IntArray {
        val r = if (gameLayout) ref else null
        val pick = SIZES[sizeChoice]
        return when {
            r == null -> { val s = if (pick == 0) 2048 else pick; intArrayOf(s, s) }
            pick == 0 -> intArrayOf(r.width, r.height)
            r.width >= r.height -> intArrayOf(pick, maxOf(1, pick * r.height / r.width))
            else -> intArrayOf(maxOf(1, pick * r.width / r.height), pick)
        }
    }

    private fun showResult() {
        val size = outSize()
        val canUseRef = gameLayout && ref != null
        if (outside == 0 && !canUseRef) outside = 1
        outsideButton.text = when (outside) { 0 -> "Reference texture"; 1 -> "Skin colour"; else -> "Transparent" }
        sizeButton.text = (if (SIZES[sizeChoice] == 0) "Auto " else "") + "${size[0]}×${size[1]}"
        sideUseButton.visibility = if (side != null) View.VISIBLE else View.GONE
        sideUseButton.text = if (useSide) "Used" else "Ignored"
        Ui.setChecked(sideUseButton, useSide)
        result?.let { resultView.setImageBitmap(it); status.text = "Texture ready (${it.width}×${it.height}). Tap Save PNG."; return }
        val f = front ?: return
        val lmF = frontView.current?.copyOf() ?: return
        if (busy) return
        busy = true
        status.text = "Making the texture…"
        val s = side
        val sideLm = if (useSide && s != null && sideDetected) sideMeshView.current?.copyOf() else null
        val sidePins = if (useSide && s != null && !sideDetected && sidePinsPlaced) sidePinView.points else null
        val r = if (canUseRef) ref else null
        val refLm = if (canUseRef) refView.current?.copyOf() else null
        val mode = outside
        worker.execute {
            try {
                val n = FaceTopology.N
                val w = size[0]; val h = size[1]
                val pos = UvBaker.toFace3d(lmF, topo.canonEyeDistance)
                val frontImg = image(f)
                val frontPx = FloatArray(n * 2) { lmF[(it / 2) * 3 + it % 2] }

                var sidePx: FloatArray? = null
                if (sideLm != null) sidePx = UvBaker.sidePixels(sideLm, topo.canon, mirror)
                else if (sidePins != null) {
                    val map = SideProfile.Mapping(sidePins, pos)
                    sidePx = FloatArray(n * 2).also { for (i in 0 until n) map.toPhoto(pos[i * 3 + 1], pos[i * 3 + 2], it, i * 2) }
                }
                val sideImg = if (sidePx != null && s != null) image(s) else null
                val weights = if (sidePx != null) UvBaker.sideWeights(UvBaker.normals(pos, topo.tris)) else FloatArray(n)

                // Match the side photo's colour to the front photo using both cheeks.
                val pr = maxOf(3, f.width / 150)
                fun cheeks(img: Image, px: FloatArray): FloatArray? {
                    val a = UvBaker.patch(img, px[50 * 2], px[50 * 2 + 1], pr); val b = UvBaker.patch(img, px[280 * 2], px[280 * 2 + 1], pr)
                    return if (a == null) b else if (b == null) a else FloatArray(3) { (a[it] + b[it]) / 2 }
                }
                val skin = cheeks(frontImg, frontPx)
                val gain = if (sideImg != null && sidePx != null) UvBaker.gain(cheeks(sideImg, sidePx), skin) else floatArrayOf(1f, 1f, 1f)

                val dst = if (refLm != null && r != null) FloatArray(n * 2) { refLm[(it / 2) * 3 + it % 2] * (if (it % 2 == 0) w.toFloat() / r.width else h.toFloat() / r.height) }
                    else FloatArray(n * 2) { val i = it / 2; if (it % 2 == 0) topo.uv[i * 2] * w else (1f - topo.uv[i * 2 + 1]) * h }
                val alpha = FloatArray(n) { 1f }
                for (v in topo.outline) alpha[v] = 0f

                val background: IntArray? = when {
                    mode == 0 && r != null -> image(if (r.width == w && r.height == h) r else Bitmap.createScaledBitmap(r, w, h, true)).px
                    mode == 2 -> null
                    else -> {
                        val c = skin ?: floatArrayOf(200f, 160f, 135f)
                        val col = Color.rgb(c[0].toInt(), c[1].toInt(), c[2].toInt())
                        IntArray(w * h) { col }
                    }
                }
                val px = UvBaker.bake(UvBaker.Input(topo.tris, dst, frontImg, frontPx, sideImg, sidePx, weights, gain, alpha, w, h, background))
                val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                val sideNote = when {
                    s == null -> " No side photo, so the cheeks and jaw edge come from the front photo and may look stretched."
                    !useSide -> ""
                    sidePx == null -> " The side photo was left out because its pins haven't been placed (Photos → Fix side pins)."
                    else -> ""
                }
                runOnUiThread {
                    busy = false
                    result = bmp
                    if (step == RESULT) { resultView.setImageBitmap(bmp); status.text = "Texture ready ($w×$h). Tap Save PNG.$sideNote" }
                }
            } catch (e: Throwable) {
                runOnUiThread { busy = false; status.text = if (e is OutOfMemoryError) "Not enough memory at this size – pick a smaller size." else "Couldn't make the texture: ${e.message}" }
            }
        }
    }

    private fun save() {
        val bmp = result ?: return
        if (busy) return
        busy = true
        status.text = "Saving…"
        val name = "face_uv_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        worker.execute {
            val msg = try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no file")
                try {
                    contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
                "Saved $name to Download/$FOLDER"
            } catch (e: Throwable) { "Couldn't save the file." }
            runOnUiThread { busy = false; status.text = msg; Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onDestroy() {
        worker.execute { detector?.close() }
        worker.shutdown()
        super.onDestroy()
    }
}
