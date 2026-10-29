package net.torosaba.train.model

import net.torosaba.train.util.Nbt
import net.torosaba.train.util.NbtCompound
import net.torosaba.train.util.NbtList
import java.io.File

/**
 * WorldEdit のスケマティック (.schem、Sponge 形式の版 1 - 3) を、Structure の形式 (.nbt) に写す。
 * ブロックエンティティ (看板の文字やチェストの中身) のデータもそのまま写すので失われない。
 * DataVersion も写すので、古い版で作ったスケマティックは読み込むときにゲームが今の版に直す。
 */
object SchematicConverter {
    class Result(val width: Int, val height: Int, val length: Int, val blocks: Int)

    fun convert(schem: File, out: File): Result {
        val (_, root) = Nbt.readFile(schem)
        val s = root.compound("Schematic") ?: root
        val width = s.int("Width") ?: error("Width がありません")
        val height = s.int("Height") ?: error("Height がありません")
        val length = s.int("Length") ?: error("Length がありません")
        val dataVersion = s.int("DataVersion") ?: 3465

        // 版 3 は Blocks の中、版 1・2 はいちばん外にある
        val blocks = s.compound("Blocks")
        val paletteTag = (blocks ?: s).compound("Palette") ?: error("Palette がありません")
        val data = ((blocks ?: s)[if (blocks != null) "Data" else "BlockData"] as? ByteArray) ?: error("ブロックのデータがありません")
        val entities = (blocks ?: s).list("BlockEntities") ?: s.list("TileEntities")

        val names = HashMap<Int, String>()
        for ((name, id) in paletteTag) names[(id as Number).toInt()] = name

        // ブロックエンティティを位置で引けるようにする
        val entityAt = HashMap<Int, NbtCompound>()
        for (e in entities?.items.orEmpty()) {
            val tag = e as? NbtCompound ?: continue
            val pos = tag["Pos"] as? IntArray ?: continue
            val nbt = NbtCompound()
            val inner = tag.compound("Data")
            if (inner != null) nbt.putAll(inner) else tag.forEach { (k, v) -> if (k != "Pos" && k != "Id") nbt[k] = v }
            tag.string("Id")?.let { nbt["id"] = it }
            nbt.remove("x"); nbt.remove("y"); nbt.remove("z")
            entityAt[pos[0] + pos[2] * width + pos[1] * width * length] = nbt
        }

        // Structure のパレットは使うものだけにする
        val structurePalette = ArrayList<Any>()
        val paletteIndex = HashMap<Int, Int>()
        val structureBlocks = ArrayList<Any>()
        var index = 0
        var offset = 0
        while (offset < data.size && index < width * height * length) {
            // 可変長の整数 (varint) でパレットの番号が並んでいる
            var value = 0
            var shift = 0
            while (true) {
                val b = data[offset++].toInt()
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            val name = names[value] ?: "minecraft:air"
            if (!isAir(name)) {
                val x = index % width
                val z = (index / width) % length
                val y = index / (width * length)
                val state = paletteIndex.getOrPut(value) {
                    structurePalette += paletteEntry(name)
                    structurePalette.size - 1
                }
                val block = NbtCompound()
                block["pos"] = NbtList(listOf(x, y, z))
                block["state"] = state
                entityAt[index]?.let { block["nbt"] = it }
                structureBlocks += block
            }
            index++
        }

        val structure = NbtCompound()
        structure["DataVersion"] = dataVersion
        structure["size"] = NbtList(listOf(width, height, length))
        structure["palette"] = NbtList(Nbt.COMPOUND, structurePalette)
        structure["blocks"] = NbtList(Nbt.COMPOUND, structureBlocks)
        structure["entities"] = NbtList(Nbt.COMPOUND, ArrayList())
        Nbt.writeFile(out, "", structure)
        return Result(width, height, length, structureBlocks.size)
    }

    private fun isAir(name: String) =
        name == "minecraft:air" || name == "minecraft:cave_air" || name == "minecraft:void_air" || name == "minecraft:structure_void"

    // "minecraft:oak_stairs[facing=north,half=bottom]" → {Name: ..., Properties: {facing: north, half: bottom}}
    private fun paletteEntry(text: String): NbtCompound {
        val entry = NbtCompound()
        val bracket = text.indexOf('[')
        entry["Name"] = if (bracket < 0) text else text.substring(0, bracket)
        if (bracket >= 0 && text.endsWith("]")) {
            val props = NbtCompound()
            for (pair in text.substring(bracket + 1, text.length - 1).split(',')) {
                val eq = pair.indexOf('=')
                if (eq > 0) props[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
            if (props.isNotEmpty()) entry["Properties"] = props
        }
        return entry
    }
}
