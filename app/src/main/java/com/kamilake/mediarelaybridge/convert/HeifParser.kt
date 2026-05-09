package com.kamilake.mediarelaybridge.convert

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Minimal ISO BMFF / HEIF parser, scoped to what we need for direct HEVC decoding:
 *  - locate the primary item
 *  - if it is `hvc1`, return its compressed payload + matching `hvcC` configuration
 *  - if it is `grid`, return grid metadata (rows, cols, output W/H, tile item ids)
 *
 * We intentionally avoid pulling in a generic mp4 library; the HEIF subset we touch is small
 * and this keeps the app dependency-free.
 */
object HeifParser {

    private const val TAG = "HeifParser"

    sealed interface PrimaryItem {
        data class HevcStill(
            val width: Int,
            val height: Int,
            val hvcC: ByteArray,
            val payload: ByteArray
        ) : PrimaryItem

        data class HevcGrid(
            val outputWidth: Int,
            val outputHeight: Int,
            val rows: Int,
            val cols: Int,
            val tileWidth: Int,
            val tileHeight: Int,
            val hvcC: ByteArray,
            val tilePayloads: List<ByteArray>
        ) : PrimaryItem

        data class Unsupported(val reason: String) : PrimaryItem
    }

    fun parse(resolver: ContentResolver, uri: Uri): PrimaryItem? {
        val bytes = readAllBytes(resolver, uri) ?: return null
        return runCatching { parseInternal(bytes) }
            .onFailure { Log.w(TAG, "parse failed", it) }
            .getOrNull()
    }

    private fun readAllBytes(resolver: ContentResolver, uri: Uri): ByteArray? {
        return runCatching {
            resolver.openInputStream(uri)?.use { it.readAllBytesCompat() }
        }.getOrNull()
    }

    private fun InputStream.readAllBytesCompat(): ByteArray {
        val buf = ByteArrayOutputStream(1 shl 20)
        val tmp = ByteArray(64 * 1024)
        while (true) {
            val n = read(tmp)
            if (n <= 0) break
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    // ---- core parsing -------------------------------------------------------

    private class Reader(val data: ByteArray, var pos: Int = 0, val end: Int = data.size) {
        fun remaining() = end - pos
        fun u8(): Int = (data[pos++].toInt() and 0xFF)
        fun u16(): Int = (u8() shl 8) or u8()
        fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()
        fun u32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun u64(): Long {
            val hi = u32().toLong() and 0xFFFFFFFFL
            val lo = u32().toLong() and 0xFFFFFFFFL
            return (hi shl 32) or lo
        }
        fun bytes(n: Int): ByteArray {
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
        fun skip(n: Int) { pos += n }
        fun str4(): String = String(bytes(4), Charsets.US_ASCII)
        /** Returns (size, type, payloadStart, boxEnd). After call, pos == payloadStart. */
        fun readBoxHeader(): Box {
            val start = pos
            var size = u32().toLong() and 0xFFFFFFFFL
            val type = str4()
            var hdr = 8
            if (size == 1L) {
                size = u64()
                hdr = 16
            } else if (size == 0L) {
                size = (end - start).toLong()
            }
            val boxEnd = start + size.toInt()
            return Box(type, start, pos, boxEnd, hdr)
        }
    }

    private data class Box(
        val type: String,
        val start: Int,
        val payloadStart: Int,
        val end: Int,
        val headerSize: Int
    ) {
        val payloadSize: Int get() = end - payloadStart
    }

    private data class ItemLoc(val itemId: Int, val offset: Long, val length: Long)
    private data class ItemInfo(val itemId: Int, val type: String)
    private data class IspeProp(val width: Int, val height: Int)

    private class Boxes {
        var primaryId: Int = -1
        var hvcC: ByteArray? = null
        val infe = mutableListOf<ItemInfo>()
        val iloc = mutableListOf<ItemLoc>()
        val properties = mutableListOf<Any>()       // entries in ipco order
        val ipmaForItem = mutableMapOf<Int, IntArray>() // itemId -> 1-based property indices
        val irefDimg = mutableMapOf<Int, IntArray>()    // fromId -> toIds (grid -> tiles)
    }

    private fun parseInternal(data: ByteArray): PrimaryItem {
        val r = Reader(data)
        // ftyp
        val ftyp = r.readBoxHeader()
        require(ftyp.type == "ftyp") { "not an ISO BMFF file" }
        r.pos = ftyp.end

        // walk top-level
        var meta: Box? = null
        var mdatStart = -1
        while (r.remaining() > 8) {
            val box = r.readBoxHeader()
            when (box.type) {
                "meta" -> meta = box
                "mdat" -> if (mdatStart < 0) mdatStart = box.start
            }
            r.pos = box.end
        }
        require(meta != null) { "missing meta box" }

        val boxes = Boxes()
        parseMeta(data, meta, boxes)

        val primaryId = boxes.primaryId
        require(primaryId > 0) { "missing primary item" }
        val primaryInfo = boxes.infe.firstOrNull { it.itemId == primaryId }
            ?: return PrimaryItem.Unsupported("primary item info missing")

        when (primaryInfo.type) {
            "hvc1" -> {
                val ispe = ispeFor(boxes, primaryId)
                    ?: return PrimaryItem.Unsupported("primary hvc1 missing ispe")
                val loc = boxes.iloc.firstOrNull { it.itemId == primaryId }
                    ?: return PrimaryItem.Unsupported("primary hvc1 missing iloc")
                val hvcC = hvcCFor(boxes, primaryId)
                    ?: return PrimaryItem.Unsupported("primary hvc1 missing hvcC")
                val payload = data.copyOfRange(loc.offset.toInt(), (loc.offset + loc.length).toInt())
                return PrimaryItem.HevcStill(
                    width = ispe.width,
                    height = ispe.height,
                    hvcC = hvcC,
                    payload = payload
                )
            }
            "grid" -> {
                val ispe = ispeFor(boxes, primaryId)
                    ?: return PrimaryItem.Unsupported("grid missing ispe")
                val gridLoc = boxes.iloc.firstOrNull { it.itemId == primaryId }
                    ?: return PrimaryItem.Unsupported("grid missing iloc")
                val grid = parseGridDescriptor(
                    data.copyOfRange(gridLoc.offset.toInt(), (gridLoc.offset + gridLoc.length).toInt())
                ) ?: return PrimaryItem.Unsupported("grid descriptor invalid")
                val tileIds = boxes.irefDimg[primaryId]
                    ?: return PrimaryItem.Unsupported("grid missing dimg refs")
                val firstTile = tileIds.firstOrNull()
                    ?: return PrimaryItem.Unsupported("grid has no tiles")
                val hvcC = hvcCFor(boxes, firstTile)
                    ?: return PrimaryItem.Unsupported("grid tile missing hvcC")
                val tileIspe = ispeFor(boxes, firstTile)
                    ?: return PrimaryItem.Unsupported("grid tile missing ispe")
                val payloads = tileIds.map { id ->
                    val loc = boxes.iloc.firstOrNull { it.itemId == id }
                        ?: return PrimaryItem.Unsupported("grid tile $id missing iloc")
                    data.copyOfRange(loc.offset.toInt(), (loc.offset + loc.length).toInt())
                }
                return PrimaryItem.HevcGrid(
                    outputWidth = ispe.width,
                    outputHeight = ispe.height,
                    rows = grid.rows,
                    cols = grid.cols,
                    tileWidth = tileIspe.width,
                    tileHeight = tileIspe.height,
                    hvcC = hvcC,
                    tilePayloads = payloads
                )
            }
            else -> return PrimaryItem.Unsupported("primary item type=${primaryInfo.type}")
        }
    }

    private fun ispeFor(boxes: Boxes, itemId: Int): IspeProp? {
        val indices = boxes.ipmaForItem[itemId] ?: return null
        for (idx in indices) {
            val prop = boxes.properties.getOrNull(idx - 1) ?: continue
            if (prop is IspeProp) return prop
        }
        return null
    }

    private fun hvcCFor(boxes: Boxes, itemId: Int): ByteArray? {
        val indices = boxes.ipmaForItem[itemId] ?: return boxes.hvcC
        for (idx in indices) {
            val prop = boxes.properties.getOrNull(idx - 1) ?: continue
            if (prop is ByteArray) return prop  // we store hvcC payload as ByteArray
        }
        return boxes.hvcC
    }

    private fun parseMeta(data: ByteArray, meta: Box, boxes: Boxes) {
        // meta is a FullBox: skip 4 bytes (version+flags) before children
        val r = Reader(data, meta.payloadStart + 4, meta.end)
        while (r.remaining() > 8) {
            val box = r.readBoxHeader()
            when (box.type) {
                "pitm" -> {
                    val v = data[box.payloadStart].toInt() and 0xFF
                    boxes.primaryId = if (v == 0) {
                        ((data[box.payloadStart + 4].toInt() and 0xFF) shl 8) or
                            (data[box.payloadStart + 5].toInt() and 0xFF)
                    } else {
                        beU32(data, box.payloadStart + 4)
                    }
                }
                "iinf" -> parseIinf(data, box, boxes)
                "iloc" -> parseIloc(data, box, boxes)
                "iprp" -> parseIprp(data, box, boxes)
                "iref" -> parseIref(data, box, boxes)
            }
            r.pos = box.end
        }
    }

    private fun parseIinf(data: ByteArray, box: Box, boxes: Boxes) {
        val r = Reader(data, box.payloadStart, box.end)
        val ver = r.u8(); r.skip(3) // flags
        val count = if (ver == 0) r.u16() else r.u32()
        for (i in 0 until count) {
            if (r.remaining() < 8) break
            val sub = r.readBoxHeader()
            if (sub.type == "infe") {
                val sr = Reader(data, sub.payloadStart, sub.end)
                val infeVer = sr.u8(); sr.skip(3)
                if (infeVer >= 2) {
                    val itemId = if (infeVer == 2) sr.u16() else sr.u32()
                    sr.skip(2) // protection_index
                    val itemType = sr.str4()
                    boxes.infe += ItemInfo(itemId, itemType)
                }
            }
            r.pos = sub.end
        }
    }

    private fun parseIloc(data: ByteArray, box: Box, boxes: Boxes) {
        val r = Reader(data, box.payloadStart, box.end)
        val ver = r.u8(); r.skip(3) // flags
        val sizes = r.u8()
        val offsetSize = (sizes shr 4) and 0xF
        val lengthSize = sizes and 0xF
        val sizes2 = r.u8()
        val baseOffsetSize = (sizes2 shr 4) and 0xF
        val indexSize = if (ver == 1 || ver == 2) sizes2 and 0xF else 0

        val itemCount = if (ver < 2) r.u16() else r.u32()
        for (i in 0 until itemCount) {
            val itemId = if (ver < 2) r.u16() else r.u32()
            if (ver == 1 || ver == 2) r.u16() // construction_method etc.
            r.u16() // data_reference_index
            val baseOffset = readVarUInt(r, baseOffsetSize)
            val extentCount = r.u16()
            for (j in 0 until extentCount) {
                if ((ver == 1 || ver == 2) && indexSize > 0) readVarUInt(r, indexSize)
                val extentOffset = readVarUInt(r, offsetSize)
                val extentLength = readVarUInt(r, lengthSize)
                if (j == 0) {
                    boxes.iloc += ItemLoc(itemId, baseOffset + extentOffset, extentLength)
                }
            }
        }
    }

    private fun readVarUInt(r: Reader, byteCount: Int): Long {
        var v = 0L
        for (i in 0 until byteCount) v = (v shl 8) or r.u8().toLong()
        return v
    }

    private fun parseIprp(data: ByteArray, box: Box, boxes: Boxes) {
        val r = Reader(data, box.payloadStart, box.end)
        while (r.remaining() > 8) {
            val sub = r.readBoxHeader()
            when (sub.type) {
                "ipco" -> parseIpco(data, sub, boxes)
                "ipma" -> parseIpma(data, sub, boxes)
            }
            r.pos = sub.end
        }
    }

    private fun parseIpco(data: ByteArray, ipco: Box, boxes: Boxes) {
        val r = Reader(data, ipco.payloadStart, ipco.end)
        while (r.remaining() > 8) {
            val sub = r.readBoxHeader()
            when (sub.type) {
                "ispe" -> {
                    val sr = Reader(data, sub.payloadStart, sub.end)
                    sr.u8(); sr.skip(3)
                    val w = sr.u32()
                    val h = sr.u32()
                    boxes.properties += IspeProp(w, h)
                }
                "hvcC" -> {
                    val payload = data.copyOfRange(sub.payloadStart, sub.end)
                    if (boxes.hvcC == null) boxes.hvcC = payload
                    boxes.properties += payload
                }
                else -> boxes.properties += sub.type   // placeholder so indices line up
            }
            r.pos = sub.end
        }
    }

    private fun parseIpma(data: ByteArray, box: Box, boxes: Boxes) {
        val r = Reader(data, box.payloadStart, box.end)
        val ver = r.u8()
        val flags = r.u24()
        val entryCount = r.u32()
        for (i in 0 until entryCount) {
            val itemId = if (ver < 1) r.u16() else r.u32()
            val assocCount = r.u8()
            val indices = IntArray(assocCount)
            for (j in 0 until assocCount) {
                if ((flags and 0x1) != 0) {
                    val v = r.u16()
                    indices[j] = v and 0x7FFF
                } else {
                    val v = r.u8()
                    indices[j] = v and 0x7F
                }
            }
            boxes.ipmaForItem[itemId] = indices
        }
    }

    private fun parseIref(data: ByteArray, box: Box, boxes: Boxes) {
        // iref is a FullBox: skip 4 bytes (version+flags)
        val ver = data[box.payloadStart].toInt() and 0xFF
        val r = Reader(data, box.payloadStart + 4, box.end)
        while (r.remaining() > 8) {
            val sub = r.readBoxHeader()
            if (sub.type == "dimg") {
                val sr = Reader(data, sub.payloadStart, sub.end)
                val fromId = if (ver == 0) sr.u16() else sr.u32()
                val count = sr.u16()
                val to = IntArray(count) {
                    if (ver == 0) sr.u16() else sr.u32()
                }
                boxes.irefDimg[fromId] = to
            }
            r.pos = sub.end
        }
    }

    /** ImageGrid descriptor (HEIF). */
    private data class GridDesc(val rows: Int, val cols: Int, val outputW: Int, val outputH: Int)
    private fun parseGridDescriptor(payload: ByteArray): GridDesc? {
        if (payload.size < 8) return null
        // version, flags, rows_minus_one, columns_minus_one, output_width, output_height
        val flags = payload[1].toInt() and 0xFF
        val rows = (payload[2].toInt() and 0xFF) + 1
        val cols = (payload[3].toInt() and 0xFF) + 1
        val use32 = (flags and 0x1) != 0
        val w: Int
        val h: Int
        if (use32) {
            if (payload.size < 12) return null
            w = beU32(payload, 4)
            h = beU32(payload, 8)
        } else {
            if (payload.size < 8) return null
            w = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
            h = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        }
        return GridDesc(rows, cols, w, h)
    }

    private fun beU32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)
}
