package xyz.chulup.dicestats.recognition.internal

import xyz.chulup.dicestats.recognition.BoundingBox
import java.util.ArrayDeque

/**
 * Framework-free classical-CV detection pipeline (the MVP spike, DEVPLAN.md step 2).
 *
 * Dice are saturated, bright, roughly-square objects; typical backgrounds (gray
 * desk, black tile, white mug, wood) are not. The pipeline therefore segments by
 * **HSV saturation + value**, cleans the mask with morphology, labels connected
 * components, splits touching dice via a distance-transform watershed, and keeps
 * only die-shaped blobs.
 *
 * It takes a plain ARGB pixel array so it can be unit-tested on the JVM (fed by
 * `BufferedImage`) and reused on Android (fed by `Bitmap.getPixels`) with
 * identical behaviour.
 */
internal object DiceDetectionPipeline {

    data class Params(
        /** Longest edge the image is scaled to before analysis. */
        val workingMaxEdge: Int = 512,
        /** Min HSV saturation (0..255) for a pixel to count as a colored die surface. */
        val minSaturation: Int = 100,
        /** Min HSV value/brightness (0..255); rejects dark backgrounds. */
        val minValue: Int = 90,
        /** Morphology radii (square structuring element): open removes speckle, close fills pips. */
        val openRadius: Int = 1,
        val closeRadius: Int = 3,
        // Low enough to catch dice photographed at arm's length (small in frame).
        val minAreaFraction: Float = 0.0008f,
        val maxAreaFraction: Float = 0.12f,
        // A d6 top face is close to square; this rejects elongated blobs (fingers, edges).
        val minAspect: Float = 0.7f,
        val maxAspect: Float = 1.4f,
        // A die is a solid saturated square; this rejects looser blobs (paper/pencil bits).
        val minFill: Float = 0.6f,
        /** A blob is split where the distance transform exceeds this fraction of its max. */
        val seedFraction: Float = 0.55f,
    )

    /**
     * @param argb row-major ARGB pixels (as from `Bitmap.getPixels` / `BufferedImage.getRGB`).
     */
    fun detect(argb: IntArray, width: Int, height: Int, params: Params = Params()): List<BoundingBox> {
        require(argb.size == width * height) { "argb size must equal width * height" }
        if (width == 0 || height == 0) return emptyList()

        val scaled = downscale(argb, width, height, params.workingMaxEdge)
        val w = scaled.width
        val h = scaled.height

        val mask = saturationValueMask(scaled.pixels, params.minSaturation, params.minValue)
        morphOpen(mask, w, h, params.openRadius)
        morphClose(mask, w, h, params.closeRadius)

        val total = w * h
        val minArea = (params.minAreaFraction * total).toInt().coerceAtLeast(1)
        val maxArea = (params.maxAreaFraction * total).toInt()

        val boxes = ArrayList<BoundingBox>()
        for (region in connectedComponents(mask, w, h, minArea)) {
            for (blob in splitTouching(region, w, h, params.seedFraction)) {
                val boxW = blob.right - blob.left
                val boxH = blob.bottom - blob.top
                if (blob.area < minArea || blob.area > maxArea) continue
                val aspect = boxW.toFloat() / boxH.toFloat()
                if (aspect < params.minAspect || aspect > params.maxAspect) continue
                val fill = blob.area.toFloat() / (boxW.toFloat() * boxH.toFloat())
                if (fill < params.minFill) continue

                boxes.add(
                    BoundingBox(
                        left = blob.left.toFloat() / w,
                        top = blob.top.toFloat() / h,
                        right = blob.right.toFloat() / w,
                        bottom = blob.bottom.toFloat() / h,
                    ),
                )
            }
        }
        return boxes
    }

    private class Scaled(val pixels: IntArray, val width: Int, val height: Int)

    /** Box-averaging downscale (akin to OpenCV INTER_AREA). Returns the source if already small. */
    private fun downscale(argb: IntArray, width: Int, height: Int, maxEdge: Int): Scaled {
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return Scaled(argb, width, height)

        val scale = maxEdge.toFloat() / longest
        val dw = (width * scale).toInt().coerceAtLeast(1)
        val dh = (height * scale).toInt().coerceAtLeast(1)
        val out = IntArray(dw * dh)

        for (dy in 0 until dh) {
            val sy0 = dy * height / dh
            val sy1 = ((dy + 1) * height / dh).coerceAtLeast(sy0 + 1)
            for (dx in 0 until dw) {
                val sx0 = dx * width / dw
                val sx1 = ((dx + 1) * width / dw).coerceAtLeast(sx0 + 1)
                var r = 0L
                var g = 0L
                var b = 0L
                var count = 0
                for (sy in sy0 until sy1) {
                    val rowBase = sy * width
                    for (sx in sx0 until sx1) {
                        val p = argb[rowBase + sx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                }
                val rr = (r / count).toInt()
                val gg = (g / count).toInt()
                val bb = (b / count).toInt()
                out[dy * dw + dx] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return Scaled(out, dw, dh)
    }

    /** Foreground (1) where HSV saturation and value both clear their thresholds. */
    private fun saturationValueMask(pixels: IntArray, minSat: Int, minVal: Int): ByteArray {
        val mask = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val sat = if (max == 0) 0 else (max - min) * 255 / max
            if (sat >= minSat && max >= minVal) mask[i] = 1
        }
        return mask
    }

    private fun morphOpen(mask: ByteArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0) return
        erode(mask, w, h, radius)
        dilate(mask, w, h, radius)
    }

    private fun morphClose(mask: ByteArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0) return
        dilate(mask, w, h, radius)
        erode(mask, w, h, radius)
    }

    /** Erosion with a square structuring element; out-of-bounds counts as background. */
    private fun erode(mask: ByteArray, w: Int, h: Int, radius: Int) {
        val src = mask.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var keep = true
                loop@ for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) { keep = false; break }
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w || src[ny * w + nx].toInt() == 0) { keep = false; break@loop }
                    }
                }
                mask[y * w + x] = if (keep) 1 else 0
            }
        }
    }

    private fun dilate(mask: ByteArray, w: Int, h: Int, radius: Int) {
        val src = mask.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var hit = false
                loop@ for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        if (nx in 0 until w && src[ny * w + nx].toInt() == 1) { hit = true; break@loop }
                    }
                }
                mask[y * w + x] = if (hit) 1 else 0
            }
        }
    }

    /** A connected blob: its pixel indices plus bounding box and area. */
    private class Region(
        val pixels: IntArray,
        val left: Int,
        val top: Int,
        val right: Int, // exclusive
        val bottom: Int, // exclusive
    ) {
        val area: Int get() = pixels.size
    }

    private fun connectedComponents(mask: ByteArray, w: Int, h: Int, minArea: Int): List<Region> {
        val visited = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val regions = ArrayList<Region>()
        val buffer = IntArray(mask.size)

        for (start in mask.indices) {
            if (visited[start] || mask[start].toInt() == 0) {
                visited[start] = true
                continue
            }
            var size = 0
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            while (sp > 0) {
                val p = stack[--sp]
                buffer[size++] = p
                val x = p % w
                val y = p / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                var dy = -1
                while (dy <= 1) {
                    var dx = -1
                    while (dx <= 1) {
                        if (dx != 0 || dy != 0) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until w && ny in 0 until h) {
                                val np = ny * w + nx
                                if (!visited[np] && mask[np].toInt() == 1) {
                                    visited[np] = true
                                    stack[sp++] = np
                                }
                            }
                        }
                        dx++
                    }
                    dy++
                }
            }
            if (size >= minArea) {
                regions.add(Region(buffer.copyOf(size), minX, minY, maxX + 1, maxY + 1))
            }
        }
        return regions
    }

    /** Result of (possibly) splitting a region. */
    private class Blob(val left: Int, val top: Int, val right: Int, val bottom: Int, val area: Int)

    /**
     * Splits a region into multiple dice where a distance transform reveals several
     * well-separated centers (touching dice), assigning each pixel to its nearest
     * seed. Returns the region unchanged when it has a single center.
     */
    private fun splitTouching(region: Region, w: Int, h: Int, seedFraction: Float): List<Blob> {
        val rw = region.right - region.left
        val rh = region.bottom - region.top
        val local = ByteArray(rw * rh)
        for (p in region.pixels) {
            val lx = p % w - region.left
            val ly = p / w - region.top
            local[ly * rw + lx] = 1
        }

        val dist = distanceTransform(local, rw, rh)
        var maxDist = 0
        for (d in dist) if (d > maxDist) maxDist = d
        if (maxDist == 0) return listOf(region.toBlob())

        val seedThreshold = seedFraction * maxDist
        val seedMask = ByteArray(local.size)
        for (i in local.indices) if (dist[i] >= seedThreshold) seedMask[i] = 1

        val seedLabels = IntArray(local.size) { -1 }
        val seedCount = labelSeeds(seedMask, rw, rh, seedLabels)
        if (seedCount <= 1) return listOf(region.toBlob())

        // Multi-source BFS: assign every foreground pixel to the nearest seed.
        val labels = seedLabels.copyOf()
        val queue = ArrayDeque<Int>()
        for (i in local.indices) if (labels[i] >= 0) queue.add(i)
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            val x = p % rw
            val y = p / rw
            val lbl = labels[p]
            for (dir in 0 until 4) {
                val nx = x + DX4[dir]
                val ny = y + DY4[dir]
                if (nx in 0 until rw && ny in 0 until rh) {
                    val np = ny * rw + nx
                    if (local[np].toInt() == 1 && labels[np] < 0) {
                        labels[np] = lbl
                        queue.add(np)
                    }
                }
            }
        }

        val minX = IntArray(seedCount) { rw }
        val minY = IntArray(seedCount) { rh }
        val maxX = IntArray(seedCount) { -1 }
        val maxY = IntArray(seedCount) { -1 }
        val areas = IntArray(seedCount)
        for (i in local.indices) {
            val lbl = labels[i]
            if (lbl < 0) continue
            val x = i % rw
            val y = i / rw
            if (x < minX[lbl]) minX[lbl] = x
            if (x > maxX[lbl]) maxX[lbl] = x
            if (y < minY[lbl]) minY[lbl] = y
            if (y > maxY[lbl]) maxY[lbl] = y
            areas[lbl]++
        }

        val blobs = ArrayList<Blob>(seedCount)
        for (lbl in 0 until seedCount) {
            if (areas[lbl] == 0) continue
            blobs.add(
                Blob(
                    left = region.left + minX[lbl],
                    top = region.top + minY[lbl],
                    right = region.left + maxX[lbl] + 1,
                    bottom = region.top + maxY[lbl] + 1,
                    area = areas[lbl],
                ),
            )
        }
        return blobs
    }

    private fun Region.toBlob() = Blob(left, top, right, bottom, area)

    /** Labels connected seed clusters (8-connected) into [out]; returns the cluster count. */
    private fun labelSeeds(seedMask: ByteArray, w: Int, h: Int, out: IntArray): Int {
        val stack = IntArray(seedMask.size)
        var label = 0
        for (start in seedMask.indices) {
            if (seedMask[start].toInt() == 0 || out[start] >= 0) continue
            var sp = 0
            stack[sp++] = start
            out[start] = label
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                var dy = -1
                while (dy <= 1) {
                    var dx = -1
                    while (dx <= 1) {
                        if (dx != 0 || dy != 0) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until w && ny in 0 until h) {
                                val np = ny * w + nx
                                if (seedMask[np].toInt() == 1 && out[np] < 0) {
                                    out[np] = label
                                    stack[sp++] = np
                                }
                            }
                        }
                        dx++
                    }
                    dy++
                }
            }
            label++
        }
        return label
    }

    /**
     * Two-pass chamfer distance transform (3-4 weights) of [mask] foreground to the
     * nearest background pixel. Values are in chamfer units; only their relative
     * magnitude matters for seed finding.
     */
    private fun distanceTransform(mask: ByteArray, w: Int, h: Int): IntArray {
        val inf = w * h * 4
        val dist = IntArray(mask.size) { if (mask[it].toInt() == 1) inf else 0 }

        // Forward pass.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (dist[i] == 0) continue
                var best = dist[i]
                if (x > 0) best = minOf(best, dist[i - 1] + 3)
                if (y > 0) best = minOf(best, dist[i - w] + 3)
                if (x > 0 && y > 0) best = minOf(best, dist[i - w - 1] + 4)
                if (x < w - 1 && y > 0) best = minOf(best, dist[i - w + 1] + 4)
                dist[i] = best
            }
        }
        // Backward pass.
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (dist[i] == 0) continue
                var best = dist[i]
                if (x < w - 1) best = minOf(best, dist[i + 1] + 3)
                if (y < h - 1) best = minOf(best, dist[i + w] + 3)
                if (x < w - 1 && y < h - 1) best = minOf(best, dist[i + w + 1] + 4)
                if (x > 0 && y < h - 1) best = minOf(best, dist[i + w - 1] + 4)
                dist[i] = best
            }
        }
        return dist
    }

    private val DX4 = intArrayOf(1, -1, 0, 0)
    private val DY4 = intArrayOf(0, 0, 1, -1)
}
